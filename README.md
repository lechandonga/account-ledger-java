# Account Ledger（账户余额与资金流水数据访问层）

基于 Spring Boot 3.4 / Java 21 / Spring JDBC / H2 / Flyway 的关系型数据访问层，
支撑账户余额与资金流水场景，目标是：**并发转账、重复提交、异常回滚下账户状态始终一致且可解释**。

---

## 1. 数据模型

金额一律以 `BIGINT` 存储**分**，对外用 `BigDecimal`（元，2 位小数）换算，杜绝浮点误差。

### accounts（账户）
| 列 | 说明 |
| --- | --- |
| id | 自增主键 |
| account_no | 业务账户号，唯一 |
| account_name | 账户名 |
| balance_cents | 余额（分），`CHECK (balance_cents >= 0)` 数据库层兜底非负 |
| currency | 币种（V2 演进加入，默认 CNY） |
| version | 乐观锁版本号，每次余额变动 +1 |
| created_at / updated_at | 时间戳 |

### ledger_entries（资金流水，只追加）
| 列 | 说明 |
| --- | --- |
| id | 自增主键，**游标分页的稳定游标依据** |
| request_id | 业务请求号（幂等键） |
| account_no / counterpart_no | 本方账户 / 对手方账户 |
| direction | `DEBIT` 扣减 / `CREDIT` 增加 |
| amount_cents | 发生额（分），`CHECK (amount_cents > 0)` |
| balance_after | 变动后余额（分），`CHECK >= 0` |
| entry_type | `DEPOSIT/WITHDRAW/TRANSFER_OUT/TRANSFER_IN` |
| entry_time | 记账时间 |

约束 `UNIQUE (request_id, account_no, direction)` 作为数据库层二次防线：
即使应用层幂等失效，同一请求也不可能对同一账户写入第二条同向流水。

### idempotent_requests（幂等请求）
| 列 | 说明 |
| --- | --- |
| request_id | 唯一键 |
| request_type / request_digest | 请求类型 / 规范化入参的 SHA-256 摘要（识别同键不同体） |
| status | `CLAIMED`（处理中占位）/ `SUCCESS` / `REJECTED`（后两者为终态，CHECK 约束限定取值） |
| response_body | 首发终态 JSON，重放时原样还原 |
| created_at / finished_at | 占位时间 / 终态时间 |

---

## 2. 核心约束与设计

### 2.1 余额变动由流水驱动、同事务原子落库
- 每次操作在**一个业务事务**内完成：条件更新账户余额 → 写流水 → 幂等行置终态。
- 任何一步失败，整事务回滚，不存在“余额变了但没流水”或反之的半更新状态。
- 转账产生 `TRANSFER_OUT` + `TRANSFER_IN` 两条流水，同事务提交。

### 2.2 乐观并发控制（丢失更新防护）
余额更新是带版本条件的单条 SQL：

```sql
UPDATE accounts
SET balance_cents = balance_cents + :delta, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE account_no = :no AND version = :expectedVersion AND balance_cents + :delta >= 0
```

- 更新 0 行时，在当前事务内重读账户，**可区分两种失败原因**：
  - 版本已变 → `OPTIMISTIC_LOCK_CONFLICT`（HTTP 409，可重试的瞬时冲突）；
  - 版本没变但扣减后为负 → `INSUFFICIENT_FUNDS`（HTTP 422，确定的业务拒绝）。
- 数据库 `CHECK (balance_cents >= 0)` 是最后一道防线。
- 服务层对乐观锁冲突 / 死锁牺牲 / 锁超时（统一翻译为 `LOCK_CONFLICT`）做**有界退避重试**，
  重试在新事务中重新读取最新版本；超过上限才向接口层返回冲突，不静默吞错。

### 2.3 重复提交（幂等）
采用“**先抢占、后办事、终态随业务事务提交**”的模型：

1. 请求到达先以独立短事务向 `idempotent_requests` 插入 `CLAIMED` 占位行（`request_id` 唯一）。
2. 抢占成功者执行业务事务，**终态（SUCCESS/REJECTED）在业务事务内更新占位行**，
   与余额、流水一起提交——终态与业务数据之间没有悬空窗口。
3. 抢占失败者（唯一键冲突）轮询占位行：
   - 看到终态 → 校验 `request_digest` 一致后，反序列化首发结果原样返回，`replay=true`，
     **返回与首次完全一致的结果，不产生第二条流水**；
   - 仍是 `CLAIMED` → 等待首发完成（有超时保护）。
4. 同一 `request_id` 绑定不同入参（摘要不一致）→ `IDEMPOTENT_REQUEST_CONFLICT`（HTTP 409）。
5. 业务拒绝（余额不足）也是终态：重复的超额取款/转账会重放到一致的 `REJECTED` 结果。
6. 非终态失败（冲突重试耗尽、持久化失败）会删除本请求的 `CLAIMED` 占位，允许后续重试。

### 2.4 游标分页（稳定、不重不漏）
- 以流水自增 `id` 为游标：查询 `id > afterId ORDER BY id ASC LIMIT n`，
  对外游标为 Base64URL 不透明串（`PageCursor`），无法伪造。
- 分页过程中新流水只追加在尾部，已发布游标之前的窗口不变 → 不漏读、不重复。
- 多取 1 条判断 `hasMore`；支持全量 / 单账户（`account_no`）两种范围。
- 非法参数明确拒绝（`INVALID_REQUEST`，HTTP 400）：`size<=0`、`size>100`、
  游标无法解码、空白 `accountNo`。

### 2.5 结构演进（Flyway）
- `db/migration/V1` 建表；`V2` 加币种列与索引（带默认值回填存量行、`IF NOT EXISTS` 可重复执行）；
  `V3` 幂等表增加 `CLAIMED` 状态与 `finished_at`。
- 迁移由 Flyway 校验和管理，可重复执行、对存量数据安全。
- `SchemaVerifier`（ApplicationRunner，可由 `ledger.schema.verify` 关闭）启动期核对
  必需表、关键列及可空性、UNIQUE / CHECK 约束；实际结构与预期不一致时抛
  `SCHEMA_MISMATCH` **拒绝启动，绝不静默忽略**。

### 2.6 异常统一归类
所有底层异常经 `PersistenceExceptionTranslator` 翻译为 `LedgerException` 体系，
底层异常仅作为 cause 保留在日志中，不直接透出接口层：

| errorCode | 含义 | HTTP |
| --- | --- | --- |
| INVALID_REQUEST | 入参/分页参数非法 | 400 |
| ACCOUNT_NOT_FOUND | 账户不存在 | 404 |
| ACCOUNT_ALREADY_EXISTS / IDEMPOTENT_REQUEST_CONFLICT / OPTIMISTIC_LOCK_CONFLICT | 资源冲突（码不同可区分） | 409 |
| INSUFFICIENT_FUNDS | 余额不足（业务拒绝终态） | 422 |
| LOCK_CONFLICT | 死锁/锁超时，事务已回滚（可重试） | 500（服务层通常内部重试消化） |
| PERSISTENCE_FAILURE / SCHEMA_MISMATCH / INTERNAL_ERROR | 内部失败，不回显底层细节 | 500 |

### 2.7 事务边界与传播
- 事务边界在 service 层；repository 本身不声明事务，只在调用方事务内工作。
- 抢占、业务、释放占位分别是 `REQUIRES_NEW` 独立事务（经独立 Bean `OperationTxExecutor`
  跨 Bean 调用，避免同类自调用导致 `@Transactional` 代理失效）。
- 转账按账户号字典序加锁，降低双向并发转账的死锁概率；残余的死锁牺牲按可重试冲突处理。
- HikariCP 开启泄漏检测（`leak-detection-threshold`），异常路径连接必须归还。

---

## 3. HTTP 接口

| 方法 路径 | 说明 |
| --- | --- |
| POST `/api/accounts` | 建户 `{accountNo, accountName, initialBalance, currency}` |
| GET  `/api/accounts/{accountNo}` | 查询账户 |
| POST `/api/deposits` | 存款 `{requestId, accountNo, amount}` |
| POST `/api/withdrawals` | 取款（余额不足返回 422 操作结果） |
| POST `/api/transfers` | 转账 `{requestId, fromAccountNo, toAccountNo, amount}` |
| GET  `/api/ledger-entries?accountNo=&cursor=&size=` | 游标分页流水 |

操作成功/重放返回 200，body 含 `status / replay / entries / nextCursor`；
业务拒绝返回 422 且 body 含 `status=REJECTED, rejectCode`。

---

## 4. 验证方法

```bash
mvn clean test
```

共 23 个测试（均以并发冲突、重复提交、异常回滚为重点，日志打印关键输入与判定依据）：

| 测试类 | 关注点 |
| --- | --- |
| `OptimisticLockConcurrencyTest` | 8 线程同版本并发更新：恰 1 成功、其余全部 `OPTIMISTIC_LOCK_CONFLICT`、余额/版本只推进一次 |
| `IdempotencyTest` | 串行重复提交、10 线程并发首发、同键不同体、业务拒绝的重放 |
| `TransferTest` | 双流水原子落库、余额不足整体无痕、双向并发转账不死锁且总额守恒 |
| `TransactionRollbackTest` | REQUIRED 内层失败整体回滚、REQUIRES_NEW 内层独立回滚不影响外层、异常后连接归还 |
| `CursorPaginationTest` | 翻页期间并发新写入不重不漏、全量/单账户、非法参数拒绝 |
| `SchemaVerificationTest` | 删除 CHECK / UNIQUE 约束后必须被识别为 `SCHEMA_MISMATCH` |
| `WebApiTest` | 422/400/404/200(replay) 状态码与错误码可区分 |
| `LedgerBasicFlowTest` | 建户、存/取款、流水驱动、拒绝后余额版本不变 |

手工验证（可选，文件库在 `./data/ledger`）：

```bash
mvn spring-boot:run
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"accountNo":"A","accountName":"甲","initialBalance":100.00,"currency":"CNY"}'
curl -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"requestId":"T1","fromAccountNo":"A","toAccountNo":"B","amount":30.00}'
# 重复执行同一 requestId：返回 replay=true 且流水不增加
curl 'localhost:8080/api/ledger-entries?size=10'
```
