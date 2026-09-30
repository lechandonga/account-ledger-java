# account-ledger-java

账户余额与资金流水的关系型数据库数据访问层。目标：在并发转账、重复提交与异常回滚场景下，
账户状态始终一致且可解释。

技术栈：Spring Boot 3.4 / Spring Data JPA / Flyway / H2（可切换 PostgreSQL 兼容库）。

## 数据模型

```
account                 ledger_operation (V3)        ledger_entry
--------                ---------------------        ------------
id          PK          request_id    PK（幂等键）     id            PK 自增（游标分页排序键）
balance     >= 0        kind          CHANGE|TRANSFER account_id    FK -> account.id
version     乐观锁       source_id     FK->account/null type        CREDIT | DEBIT
created_at              target_id     FK->account/null amount       > 0（分，恒正）
                        amount        > 0             balance_after 变动后余额快照（审计）
                        created_at                    request_id    幂等键（与 account_id 联合唯一）
                                                      operation_kind CHANGE | TRANSFER (V3)
                                                      created_at
                                                      description   (V2 新增，可空)
```

- 金额用 `long`（分）存储，避免浮点误差。
- `account.balance >= 0`、`ledger_entry.amount > 0` 由数据库 CHECK 约束兜底。
- 业务请求以 `ledger_operation` 一行表达：`request_id` 主键是幂等的最终防线，
  单边加减（CHANGE）与跨账户划转（TRANSFER）共用同一请求号命名空间。
- 一次划转在 `ledger_entry` 留下两条方向相反的流水（DEBIT/CREDIT），
  `uk_ledger_request_account (request_id, account_id)` 保证同一请求在同一账户至多一条流水。
- `idx_ledger_account_id (account_id, id)` 支撑游标分页查询。

## 核心约束

### 原子性（余额与流水同生共死）
`AccountService.applyChange` 在单个事务内完成：读取账户 → 校验余额 → 更新余额 →
写入流水。任何一步失败整体回滚，不存在"扣了余额没流水"或"有流水余额没变"的中间态。
嵌套调用使用默认 `REQUIRED` 传播：内层加入外层事务，外层失败时内层不会独立提交。

### 乐观并发控制
`account.version` 由 JPA `@Version` 维护。并发修改同一账户时，后提交的事务在 flush
时因版本不匹配失败，映射为 `ConcurrencyConflictException`
（类别 `CONCURRENCY_CONFLICT`），调用方可安全重试。冲突事务已整体回滚，数据保持一致，
不会出现丢失更新或余额覆盖。

### 跨账户划转（原子、双向流水）
`TransferService.transfer(source, target, amount, requestId)` 在单个事务内完成：
校验双方账户与余额 → 更新两个账户余额 → 写 1 行 `ledger_operation`（TRANSFER）+
两行方向相反的流水（转出方 DEBIT、转入方 CREDIT，含各自变动后余额快照）。

规则与失败语义：
- **要么全成要么全无**：任一步失败（含余额不足）整体回滚，不会只扣不进或只进不扣，
  失败后两边账户、流水、操作表都不留痕。
- **明确拒绝的输入**：金额 ≤ 0、转出/转入账户相同、任一账户不存在，均为
  `INVALID_ARGUMENT`；转出方余额不足为 `BUSINESS_REJECTION`（勿重试）。
- **锁序防死锁**：两个账户一律按 accountId 升序更新。双向同时互转时两个事务
  加锁顺序一致，不会循环等待；乐观锁版本冲突、锁超时/死锁统一映射为
  `CONCURRENCY_CONFLICT`，与余额不足明确区分，调用方可安全重试。
- **结果可查**：返回 `TransferResult`（含两边流水 id、方向、金额、变动后余额，
  `replayed` 标记是否重复提交）；每个账户分页翻流水只看到自己那一笔，
  用同一 requestId 可查到两边完整结果。

### 幂等（单边与划转统一）
- 同一 requestId 重复到达（包括并发重复）返回与首次完全一致的结果：单边返回同一条
  流水，划转返回同一对流水，余额只变动一次。
- 并发竞态由 `ledger_operation` 主键/流水联合唯一约束兜底；败者事务回滚后重读首次
  结果（若败者先撞上乐观锁，冲突后同样回查首次结果并按重复提交返回）。
- 单边与划转**不能串用请求号**：一个 requestId 已被 TRANSFER 占用后再用于 CHANGE
  （或反之），明确按 `INVALID_ARGUMENT` 拒绝；重复请求的账户/方向/金额与首次不一致
  也会被拒绝，不会执行第二次。

### 游标分页
`LedgerService.page(accountId, cursor, size)` 按自增主键升序取 `id > cursor` 的一段，
多取一条判断是否还有下一页。新写入的流水 id 更大，只会出现在后续页——分页过程中
有写入不会漏读或重复读取。非法参数（负游标、页大小越界）抛出
`InvalidPaginationException` 明确拒绝。

### 结构演进与实时结构核对
- Flyway 版本化迁移（`V1__init`、`V2__ledger_entry_description`、
  `V3__transfer_and_operation`），变更只追加、对已有数据安全。
- V3 兼容范围：只新增/放宽，不删除业务列；`ledger_entry.operation_kind` 带默认值
  `CHANGE` 并由存量数据回填；老的单边流水按 DEBIT→source、CREDIT→target 回填到
  `ledger_operation`；`description` 仍可空；老数据升级后照常可读，老请求的幂等语义
  不变（见 `V3MigrationCompatibilityTest`）。
- `SchemaGuard` 启动时先跑 `flyway.validateWithResult()`，再调用 `SchemaVerifier`
  直接读取 `INFORMATION_SCHEMA` 逐项核对：主键、非空、CHECK 取值范围（余额非负、
  金额为正、type/kind 枚举域）、唯一约束（`request_id`、`(request_id, account_id)`）、
  外键引用关系、分页索引。任何一项缺失或被改弱/改名都抛 `SchemaMismatchException`
  （类别 `PERSISTENCE_FAILURE`），并在日志中列出全部不一致项。
- **运行期复核**：每次单边变动或划转写库前都会再次执行 `SchemaVerifier.verify()`，
  约束即使在两次请求之间被人删掉/改掉，下一次写操作也会被明确拒绝，绝不"没看见"。
- `spring.jpa.hibernate.ddl-auto=validate`：实体映射与表结构不一致时启动失败。

## 异常归类

底层持久化异常一律不透出接口层，统一映射为 `LedgerException` 子类，
通过 `category()` 区分：

| 类别 | 异常 | 含义 | 调用方建议 |
|---|---|---|---|
| `BUSINESS_REJECTION` | `InsufficientFundsException` | 余额不足等业务拒绝 | 展示给用户，勿重试 |
| `CONCURRENCY_CONFLICT` | `ConcurrencyConflictException` | 乐观锁版本冲突 / 锁超时 / 死锁 | 可安全重试 |
| `INVALID_ARGUMENT` | `InvalidPaginationException` 及匿名 `LedgerException` | 非法游标/页大小、金额非正、账户不存在、同账户划转、请求号跨操作类型复用等 | 修正参数，勿原样重试 |
| `PERSISTENCE_FAILURE` | `PersistenceFailureException` / `SchemaMismatchException` | 底层持久化故障或结构/约束与预期不一致（已脱敏包装，cause 可查） | 告警排查；结构类问题先修复数据库 |

## 验证方法

```bash
mvn test
```

| 测试类 | 验证点 |
|---|---|
| `AccountServiceTest` | 余额变动一致性；余额不足整体回滚；顺序/并发重复提交收敛为单条流水 |
| `TransferServiceTest` | 划转双流水与双边余额；各账户只看到自己一侧；余额不足整体无痕；非法输入拒绝；顺序/并发重复返回首次结果；请求号跨 CHANGE/TRANSFER 不可复用；双向并发互转总额守恒不死锁（冲突可重试）；同转出方并发划转不丢钱 |
| `ConcurrencyConflictTest` | 过期版本更新被乐观锁拒绝；并发扣减无丢失更新且失败原因可区分 |
| `LedgerPaginationTest` | 游标分页完整覆盖；分页期间写入不漏不重；非法参数拒绝 |
| `TransactionRollbackTest` | 嵌套事务失败整体回滚无部分提交；失败后连接归还连接池（active=0） |
| `SchemaVerificationTest` | 删除/改弱唯一约束、CHECK（余额/金额/枚举域）、外键、放开非空均被明确识别并失败；运行期改约束后下一次写操作即被拒 |
| `V3MigrationCompatibilityTest` | V1/V2 老库写入老数据后升级 V3：数据无损、回填为 CHANGE、老单边请求在新结构下继续可用 |

测试日志会打印关键输入与判定依据（如余额、流水数、连接池 active 数、异常类别），
便于核对一致性结论。
