# account-ledger-java

账户余额与资金流水的关系型数据库数据访问层。目标：在并发转账、重复提交与异常回滚场景下，
账户状态始终一致且可解释。

技术栈：Spring Boot 3.4 / Spring Data JPA / Flyway / H2（可切换 PostgreSQL 兼容库）。

## 数据模型

```
account                 ledger_entry                    ledger_request (V3)
--------                ------------                    -----------------
id          PK          id            PK 自增            id            PK（= 业务请求号）
balance     >= 0        account_id    FK -> account.id   op_kind       SINGLE_CHANGE | TRANSFER
version     乐观锁       type          CREDIT | DEBIT     created_at
created_at              amount        > 0（分，恒正）
                        balance_after >= 0（审计快照）
                        request_id    FK -> ledger_request.id
                        created_at
                        description   (V2 新增，可空)
                        UNIQUE (account_id, request_id) (V3)
```

- 金额用 `long`（分）存储，避免浮点误差。
- `account.balance >= 0`、`ledger_entry.amount > 0`、`balance_after >= 0`、
  `type IN ('CREDIT','DEBIT')` 由数据库 CHECK 约束兜底。
- 幂等的最终防线在请求登记表：`ledger_request` 主键保证每个业务请求号最多被执行一次；
  流水表上是 `uk_ledger_account_request (account_id, request_id)`，
  因此一次划转可以在两个账户各留一条同请求号、方向相反的流水。
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

### 幂等（重复提交识别）
每个业务请求携带唯一 `requestId`：
1. 快路径：先查 `ledger_request`，已登记则按种类回放首次结果（`replayed=true`）；
2. 竞态路径：并发重复提交在事务内先登记请求，撞上 `ledger_request` 主键，
   本事务回滚后重新读取首次结果并返回。
两条路径都保证：重复请求返回与首次一致的结果，单边余额只扣一次、流水只有一条；
划转只发生一次、两边各只有一条流水。

### 跨账户划转（V3）
`TransferService.transfer(from, to, amount, requestId)` 在单个事务内完成：
登记请求（`ledger_request`，op_kind=TRANSFER）→ 转出方扣减 → 转入方增加 →
两边各写一条方向相反的流水（转出 DEBIT、转入 CREDIT），任一步失败整体回滚，
不存在只扣不出、只进不扣或只登记不出账。

- **失败语义**：转出方余额不足 → `InsufficientFundsException`
  （`BUSINESS_REJECTION`），两边余额、流水、请求登记全部无痕；收款账户不存在、
  自转、金额 ≤ 0 → `INVALID_ARGUMENT`，同样不留痕。
- **返回结果** `TransferResult`：两个流水标识、两边变动后余额一次给出；
  两个账户各自翻流水都能看到自己那一笔，凭同一个 `requestId`
  可查到这次划转在两边的完整结果（方向、金额、变动后余额、流水标识）。
- **防死锁**：无论划转方向如何，两个账户一律按账户 id 升序更新（立即 flush 触发行锁/
  version 校验）。双向同时互转时等待图无环，不会互相卡死；落败方得到
  `ConcurrencyConflictException`（`CONCURRENCY_CONFLICT`），可安全重试，
  失败事务已整体回滚不留半截状态。

### 单边与划转共用请求号命名空间
`ledger_request.op_kind` 记录请求种类（`SINGLE_CHANGE` / `TRANSFER`）：
- 同一种类重复到达（含并发重复）→ 返回与首次一致的结果（`replayed=true`），
  划转则返回同一对流水标识与两边余额，不重复划转；
- 一个请求号被另一种操作再次提交（单边 ↔ 划转串用）→ 抛
  `DuplicateRequestException`（类别 `DUPLICATE_REQUEST`），按重复提交处理，
  绝不执行第二种语义；
- 同请求号但参数（账户/方向/金额）与首次不一致，也按重复提交明确拒绝。

### 游标分页
`LedgerService.page(accountId, cursor, size)` 按自增主键升序取 `id > cursor` 的一段，
多取一条判断是否还有下一页。新写入的流水 id 更大，只会出现在后续页——分页过程中
有写入不会漏读或重复读取。非法参数（负游标、页大小越界）抛出
`InvalidPaginationException` 明确拒绝。

### 结构演进
- Flyway 版本化迁移（`V1__init`、`V2__ledger_entry_description`、
  `V3__ledger_request_registry`），变更只追加。V3 对已有数据安全：
  先建 `ledger_request` 并把历史流水的请求号批量登记为 `SINGLE_CHANGE`，
  再把流水表唯一约束从单列 `request_id` 换成 `(account_id, request_id)`
  并补上对登记表的外键；升级后老数据照常可读，老的单边请求在新结构下行为不变。
- `spring.jpa.hibernate.ddl-auto=validate`：实体映射与表结构不一致时启动失败。

### 结构核对（实际约束实时核验）
启动时 `SchemaGuard.verify()` 做两层核对，任何一项不过都抛
`SchemaMismatchException` 并使启动失败，绝不静默：
1. `flyway.validateWithResult()`：校验和不符、迁移缺失/错位；
2. 直接读 `INFORMATION_SCHEMA` 逐项核对实际数据库，包括：
   - 表是否齐全（`account` / `ledger_entry` / `ledger_request`）；
   - 关键列 NOT NULL（含 `request_id`、`balance_after` 等）；
   - CHECK 约束既要求约束名存在，也比对规范化后的表达式语义
     （把约束删掉、或同名改成更宽松的规则如 `amount >= 0` 都会被抓住）；
   - UNIQUE（`uk_ledger_account_request` 列集合正确，旧 `uk_ledger_request_id`
     必须已删除）、主键（每张表恰好是 id 列）、外键
     （`ledger_entry.account_id -> account.id`、
     `ledger_entry.request_id -> ledger_request.id`）、分页索引
     `idx_ledger_account_id`。

该核对可在运行期随时调用（测试中即通过人为删/改约束后调用 `verify()` 验证）。

## 异常归类

底层持久化异常一律不透出接口层，统一映射为 `LedgerException` 子类，
通过 `category()` 区分：

| 类别 | 异常 | 含义 | 调用方建议 |
|---|---|---|---|
| `BUSINESS_REJECTION` | `InsufficientFundsException` | 余额不足等业务拒绝 | 展示给用户，勿重试 |
| `CONCURRENCY_CONFLICT` | `ConcurrencyConflictException` | 乐观锁版本冲突 | 可安全重试 |
| `INVALID_ARGUMENT` | `InvalidPaginationException` | 非法游标/页大小等 | 修正参数 |
| `DUPLICATE_REQUEST` | `DuplicateRequestException` | 请求号已被另一种操作占用（或同号不同参） | 按重复提交处理，勿换语义重试 |
| `PERSISTENCE_FAILURE` | `PersistenceFailureException` / `SchemaMismatchException` | 底层持久化故障（已脱敏包装，cause 可查） | 告警排查 |

## 验证方法

```bash
mvn test
```

| 测试类 | 验证点 |
|---|---|
| `AccountServiceTest` | 余额变动一致性；余额不足整体回滚；顺序/并发重复提交收敛为单条流水 |
| `ConcurrencyConflictTest` | 过期版本更新被乐观锁拒绝；并发扣减无丢失更新且失败原因可区分 |
| `LedgerPaginationTest` | 游标分页完整覆盖；分页期间写入不漏不重；非法参数拒绝 |
| `TransactionRollbackTest` | 嵌套事务失败整体回滚无部分提交；失败后连接归还连接池（active=0） |
| `TransferServiceTest` | 划转两边余额/流水一致（方向、金额、变动后余额、流水标识）；余额不足两边与登记无痕；顺序/并发重复返回同一结果；收款方缺失/非正金额/自转明确失败；单边与划转请求号不串用 |
| `ConcurrentTransferTest` | 20 笔双向并发划转资金守恒、每请求恰好两条流水；屏障同步的同时互转不死锁、冲突可安全重试；冲突失败无半截流水 |
| `SchemaGuardTest` | 干净结构通过；人为删除/弱化唯一、CHECK、外键、非空、索引后 `verify()` 必失败且信息指明缺失项，恢复后通过 |

测试日志会打印关键输入与判定依据（如余额、流水数、连接池 active 数、异常类别），
便于核对一致性结论。
