# Plan A Task 8 真实环境验证记录

验证日期：2026-09-22  
分支：`feat/after-sales-capability`（Task 7 提交 `b1ff494`）

本记录只保留脱敏后的验证结果，不包含 JWT、密码、密钥或凭据文件路径。

## 构建与单元测试

使用仓库约定的 IDEA 内置 Maven：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" `
  -pl mall-server -am clean package "-DskipTests"
```

结果：exit 0，五个 Reactor 模块全部 `BUILD SUCCESS`。

完整测试命令：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" `
  -pl mall-server -am "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：exit 0，Reactor 汇总为 **222 tests, 0 failures, 0 errors, 0 skipped**：

- `mall-common`: 10
- `mall-security`: 16
- `mall-infra`: 6
- `mall-server`: 190

## 应用启动

应用以 `loadtest` profile、端口 8081 启动。日志确认：

```text
Snowflake identity configured: workerId=1, datacenterId=1
Started MallApplication
```

验证期间 MySQL、Redis、RabbitMQ 可用；应用在记录完成时仍保持运行，供并发验证使用。

## 策略端点

使用一次性客户身份进行认证后，连续请求 `GET /api/after-sales/policies`：

- 两次响应均 `code=0`。
- 条款数量为 3。
- 两次 fingerprint 相同：
  `1ffff37eca39e03efb7a6bcc751a94c4df8f761ceaa005aec9d5806bcaa4c55c`
- 实际条款 code 为：
  `SEVEN_DAY_NO_REASON`、`SHIPPED_NOT_RECEIVED`、`QUALITY_ISSUE`。
- `SEVEN_DAY_NO_REASON` 与资格接口返回的 `policyCode` 对齐，且三项与 `AfterSalesPolicy` 枚举名称完全匹配。

## 售后链路

测试使用 SKU `930000000000000001`，订单实付金额为 `199.99`。每个场景均使用新订单，避免幂等路径掩盖校验结果。

| 场景 | 订单 | 预期 | 实际 |
|---|---:|---|---|
| 资格查询 | `2102371738003312640` | 可退，命中 `SEVEN_DAY_NO_REASON` | `code=0`，`eligible=true`，金额 `199.99` |
| 执行退款 | 同上 | 成功并进入 `REFUNDED` | `code=0`；数据库状态 `REFUNDED` |
| 顺序重试 | 同上 | 成功幂等，只有一条退款 | `code=0`；退款行数 1 |
| 旧端点首次申请 | `2102371739412598784` | 落一条 `PENDING` | `code=0`；数据库状态 `PAID`，退款状态 `PENDING` |
| 旧端点重复申请 | 同上 | `50003` 业务错误 | `code=50003`，退款行数 1 |
| 越权资格查询 | 订单 1，由另一客户请求 | `50000` | `code=50000` |
| 金额不可指定 | `2102371742147284992` | 忽略请求中的 `amount=99999` | `code=0`；订单总额与退款金额均为 `199.99`，订单状态 `REFUNDED` |
| 空白 reason | `2102371742944202752` | 参数错误且不落退款行 | `code=10000`；订单仍 `RECEIVED`，退款行数 0 |
| 513 字 reason | 同上 | 参数错误且不落退款行 | `code=10000`；退款行数仍为 0 |

数据库只读核对还确认 `refund.uk_refund_order` 是 `order_id` 上的唯一索引（`non_unique=0`）。

## 并发幂等证明

独立并发验证使用新订单 `2102372454289772544`，初始状态 `RECEIVED`、退款行数 0。先在独立 MySQL 会话锁住该订单行，再同时发出两个执行请求。

在提交锁之前观察到两条不同的等待线程：

| thread id | schema | table | index | lock data |
|---:|---|---|---|---:|
| 1123 | `mall` | `order` | `PRIMARY` | `2102372454289772544` |
| 1124 | `mall` | `order` | `PRIMARY` | `2102372454289772544` |

阻塞连接 ID 为 861。释放持锁事务后，两个响应均为 `code=0`，一个是首次执行、一个是幂等结果；最终退款行数为 1，订单状态为 `REFUNDED`。

这证明了两个请求在订单行锁上等待并走完并发幂等路径。它不能直接观测事务内部 read view 的建立时刻；read view 先于锁等待的部分来自当前 `execute()` / `check()` 调用顺序及 MySQL `REPEATABLE-READ` 配置。

## 有意保留的简化

本轮验证与计划中的实现约束一致：

1. 签收时间用 `order.created_at` 近似，订单表尚无独立签收时间字段。
2. 只支持整单退款，退款金额由服务端取订单实付金额，不支持部分退款。
3. 退款即时完成，没有真实支付网关；新执行端点将订单推进到 `REFUNDED`。
4. 旧的 `POST /api/orders/{id}/refund` 申请路径继续保留，语义是落 `PENDING`；Agent 使用幂等的 `/refund/execute` 路径。

