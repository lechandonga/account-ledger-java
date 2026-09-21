# account-ledger-java

账户余额与资金流水的关系型数据库数据访问层。目标：在并发转账、重复提交与异常回滚场景下，
账户状态始终一致且可解释。

技术栈：Spring Boot 3.4 / Spring Data JPA / Flyway / H2（可切换 PostgreSQL 兼容库）。

## 数据模型

```
account                 ledger_entry
--------                ------------
id          PK          id            PK 自增（游标分页排序键）
balance     >= 0        account_id    FK -> account.id
version     乐观锁       type          CREDIT | DEBIT
created_at              amount        > 0（分，恒正）
                        balance_after 变动后余额快照（审计）
                        request_id    UNIQUE（幂等键）
                        created_at
                        description   (V2 新增，可空)
```

- 金额用 `long`（分）存储，避免浮点误差。
- `account.balance >= 0`、`ledger_entry.amount > 0` 由数据库 CHECK 约束兜底。
- `uk_ledger_request_id` 唯一约束是幂等的最终防线。
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
1. 快路径：先按 `requestId` 查询，已存在则直接返回首次结果（`replayed=true`）；
2. 竞态路径：并发重复提交撞上 `uk_ledger_request_id` 唯一约束，本事务回滚后重新
   读取首次流水并返回。
两条路径都保证：重复请求返回与首次一致的结果，余额只扣一次，流水只有一条。

### 游标分页
`LedgerService.page(accountId, cursor, size)` 按自增主键升序取 `id > cursor` 的一段，
多取一条判断是否还有下一页。新写入的流水 id 更大，只会出现在后续页——分页过程中
有写入不会漏读或重复读取。非法参数（负游标、页大小越界）抛出
`InvalidPaginationException` 明确拒绝。

### 结构演进
- Flyway 版本化迁移（`V1__init`、`V2__ledger_entry_description`），变更只追加、
  对已有数据安全（可空列、无回填）。
- 启动时 `SchemaGuard` 执行 `flyway.validateWithResult()`：校验和不符、迁移缺失、
  实际结构与预期不一致时抛出 `SchemaMismatchException` 并使启动失败，不静默忽略。
- `spring.jpa.hibernate.ddl-auto=validate`：实体映射与表结构不一致时启动失败。

## 异常归类

底层持久化异常一律不透出接口层，统一映射为 `LedgerException` 子类，
通过 `category()` 区分：

| 类别 | 异常 | 含义 | 调用方建议 |
|---|---|---|---|
| `BUSINESS_REJECTION` | `InsufficientFundsException` | 余额不足等业务拒绝 | 展示给用户，勿重试 |
| `CONCURRENCY_CONFLICT` | `ConcurrencyConflictException` | 乐观锁版本冲突 | 可安全重试 |
| `INVALID_ARGUMENT` | `InvalidPaginationException` | 非法游标/页大小等 | 修正参数 |
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

测试日志会打印关键输入与判定依据（如余额、流水数、连接池 active 数、异常类别），
便于核对一致性结论。
