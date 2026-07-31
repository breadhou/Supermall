# 阶段六：秒杀模块设计

> **实施状态（2026-07-31）**：本文件是初版设计记录，当前实现已在提交 `ba495db` 中完成秒杀入口热路径优化。以下规则以本说明中的“当前实现补充”和仓库 `AGENTS.md` 为准：执行接口不再查询 MySQL 商品数据；Redis 使用 `{itemId}` hash tag；publisher confirm 成功后才返回 `WAITING`；pending、死信和定时补偿负责可靠回滚。测试环境当前已关闭，持续压测和故障注入待恢复后执行。

## 涉及表

| 表 | 关键字段 |
|----|---------|
| `seckill_activity` | id, name, start_time, end_time, status(NOT_STARTED/IN_PROGRESS/ENDED) |
| `seckill_item` | id, activity_id, sku_id, seckill_price, stock(秒杀库存), limit_per_user |
| `seckill_order` | id, user_id, seckill_item_id, order_id, uk(user_id+seckill_item_id)防重 |
| `order` | 复用阶段五的表，秒杀下单时创建 |
| `order_item` | 复用阶段五的表 |

## 阶段边界

| 阶段六（当前） | 阶段七（运营后台） |
|---|---|
| 库存预热 | 秒杀活动 CRUD |
| 获取秒杀路径 | 秒杀商品管理 |
| 执行秒杀 | 活动列表/商品查询 |
| 结果轮询 | 优惠券 |
| MQ 消费落库 | 商家/管理员 |

## 已建基础设施（直接使用）

- `RedisService` — get/set/incr/decr/exists/deleteByPrefix
- `RedisLock` — 通用分布式锁仍保留，但不再用于秒杀入口热路径
- `SeckillKey` — 生成 `mall:seckill:{itemId}:...` hash-tag key（stock/snapshot/path/request/result/limit/pending）
- `MQConfig` — 交换机 `mall.seckill.direct` + 主队列 `mall.seckill.order` + 死信 `mall.seckill.order.dlq`
- `SnowflakeIdUtil` — 雪花 ID
- `UserContext` — 获取当前登录 userId

## API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/seckill/{itemId}/preheat` | 预热库存到 Redis |
| POST | `/api/seckill/{itemId}/path` | 获取秒杀路径（返回随机 UUID） |
| GET | `/api/seckill/{itemId}/countdown` | 返回剩余库存和活动状态 |
| POST | `/api/seckill/{itemId}/{path}` | 执行秒杀（无 requestBody） |
| GET | `/api/seckill/result/{itemId}` | 轮询秒杀结果 |

## 文件清单

```
mall-server/src/main/java/com/mall/module/seckill/
├── entity/
│   ├── po/SeckillActivity.java
│   ├── po/SeckillItem.java
│   ├── po/SeckillOrder.java
│   └── vo/SeckillResultVO.java
├── mapper/
│   ├── SeckillActivityMapper.java
│   ├── SeckillItemMapper.java
│   └── SeckillOrderMapper.java
├── service/
│   ├── SeckillService.java
│   └── impl/SeckillServiceImpl.java
├── controller/
│   └── SeckillController.java
└── mq/
    ├── SeckillMessage.java
    └── SeckillConsumer.java
```

## 核心业务规则

### 1. 库存预热 `POST /{itemId}/preheat`

- 查 `seckill_item`，取 `stock`、`sku_id`、`seckill_price` 和 `limit_per_user`
- 写入 `mall:seckill:{itemId}:stock` 及 `...:snapshot`，并登记 item 供补偿任务扫描
- 后续执行接口不查 MySQL 商品数据，全靠 Redis 快照和 Lua 预占

### 2. 获取秒杀路径 `POST /{itemId}/path`

- 校验 seckill_item 存在
- 校验活动是否在有效时间窗口内（start_time ≤ now ≤ end_time）
- 生成随机 UUID 作为 path 值
- 缓存：`mall:seckill:{itemId}:path:{userId}` 和 `...:request:{userId}`，生产 TTL 60 秒，`loadtest` profile TTL 900 秒
- request snapshot 同时保存 SKU、秒杀价格、限购数量和默认地址 ID
- 返回 path 字符串给前端

### 3. 活动倒计时 `GET /{itemId}/countdown`

- 查 seckill_item 和关联的 seckill_activity
- 返回：
  ```json
  {
    "status": "IN_PROGRESS",
    "startTime": "2026-07-28 10:00:00",
    "endTime": "2026-07-28 12:00:00",
    "seckillPrice": 99.00,
    "remainingStock": 150,
    "limitPerUser": 1
  }
  ```
- `remainingStock` 从 Redis `mall:seckill:{itemId}:stock` 读取
- 未到开始时间返回 `NOT_STARTED`，已过期返回 `ENDED`

### 4. 执行秒杀 `POST /{itemId}/{path}`（核心）

**当前步骤：**

1. 读取 Redis `request:{userId}` 快照；执行接口不再查询 MySQL 商品、SKU 或地址。
2. `reserve` Lua 一次校验 path、重复请求、限购和库存，并写入 result=0 与 pending 状态。
3. 预占成功后发送带快照的 `SeckillMessage`，等待 RabbitMQ correlated publisher confirm。
4. confirm 成功才返回 `WAITING`；NACK、异常、超时通过 rollback Lua 恢复库存/限购并写 result=-1。
5. pending 状态由定时补偿任务扫描，处理 publisher 超时、进程重启和 Redis 结果更新失败。

**三层超卖防护：**
1. Redis Lua 原子 DECR（第一道防线）
2. MySQL 乐观锁 `UPDATE seckill_item SET stock = stock - ? WHERE stock >= ?`（第二道防线）
3. `seckill_order` 唯一索引 `uk_user_seckill`（第三道防线，防重）

### 5. MQ 消费者 SeckillConsumer

- 监听队列 `mall.seckill.order`
- 手动确认模式：`acknowledge-mode: manual`，默认 `concurrency=8`、`prefetch=100`
- 消费流程：
  1. 解析带 SKU、秒杀价格和地址快照的 `SeckillMessage`
  2. `claim` Lua 将 pending 从 `PENDING` 转为 `PROCESSING`，重复投递安全退出
  3. MySQL 事务执行乐观锁扣减 `seckill_item.stock`，并生成 order + order_item + seckill_order
  4. 事务提交后 `finalize` Lua 写 result=1、删除 pending，再 `channel.basicAck`
  5. 事务异常 → `channel.basicNack` → 自动进入死信队列
  6. 死信消费者或定时补偿任务执行 rollback Lua，恢复 Redis 库存/限购并写 result=-1
  7. 如果订单已落库但 Redis 暂时不可用，保留 ACK；补偿任务通过唯一的 `seckill_order` 记录恢复 result=1

### 6. 轮询结果 `GET /result/{itemId}`

- 读 `mall:seckill:{itemId}:result:{userId}`：`redisService.getValue(SeckillKey.resultKey(itemId, userId), Integer.class)`
- 返回：
  - 0 → `{ "status": "WAITING" }`
  - 1 → `{ "status": "SUCCESS", "orderId": xxx }`
  - -1 → `{ "status": "FAILED", "reason": "库存不足" }`
  - null → 未参与秒杀

## SeckillResultVO

```java
@Data
public class SeckillResultVO {
    /** WAITING / SUCCESS / FAILED */
    private String status;
    /** 成功时返回订单 ID */
    private Long orderId;
    /** 失败时返回原因 */
    private String reason;
}
```

## SeckillMessage

```java
@Data
public class SeckillMessage {
    private Long seckillItemId;
    private Long userId;
    private Integer quantity;
    private String messageId;
    private Long skuId;
    private BigDecimal seckillPrice;
    private Long addressId;
}
```

## PO 关键字段

### SeckillActivity
```java
@TableName("seckill_activity")
public class SeckillActivity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;  // NOT_STARTED / IN_PROGRESS / ENDED
    private LocalDateTime createdAt;
}
```

### SeckillItem
```java
@TableName("seckill_item")
public class SeckillItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long activityId;
    private Long skuId;
    private BigDecimal seckillPrice;
    private Integer stock;         // 秒杀专用库存
    private Integer limitPerUser;  // 每人限购
    private LocalDateTime createdAt;
}
```

### SeckillOrder
```java
@TableName("seckill_order")
public class SeckillOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long seckillItemId;
    private Long orderId;     // 关联 order 表
    private LocalDateTime createdAt;
}
```

## 实现建议

1. 先写 PO → Mapper → SeckillResultVO → SeckillMessage，纯数据
2. SeckillService 接口定义 5 个方法（preheatStock / getPath / countdown / execute / pollResult）
3. countdown 最简单，先写
4. execute 是核心，实现时依赖 RedisLock + RedisService 的 incr/decr
5. Lua 脚本写在 `mall-server/src/main/resources/Lua/`，由 `SeckillRedisStateService` 统一调用
6. SeckillConsumer 是 `@RabbitListener` + `@Component`，手动 ACK、默认并发 8、prefetch 100
7. 单元测试覆盖 reserve、confirm、rollback 和 Controller；Redis/MQ 故障注入与集成测试在实例环境恢复后执行

## 关键注意事项

- **库存预热时机**：preheat 接口在活动开始前手动调用，后续运营后台完成后可自动触发
- **path 过期时间**：生产 60 秒；本地 `loadtest` profile 为 900 秒，便于稳定压测
- **result 缓存 TTL**：3600 秒，活动结束后自然过期
- **死信队列**：消费失败的消息不丢，存到 DLQ；死信消费者和 pending 定时任务回滚 Redis 库存
- **并发安全**：Lua 原子校验/预占，MySQL 乐观锁兜底，uk 约束最后防线；入口不依赖单用户 Redis 锁

## 当前实现补充

| 项目 | 当前实现 |
|------|----------|
| 热路径商品数据 | 预热写入 SKU、价格、限购快照，执行接口只读 Redis request snapshot |
| Redis Cluster | 所有 item 级 key 使用 `mall:seckill:{itemId}:...` hash tag |
| 可靠投递 | publisher confirm 成功后才返回 `WAITING`；失败通过 Lua rollback |
| 结果一致性 | 消费事务提交后 finalize；落库成功但 Redis 更新失败由 pending 扫描修复 |
| 监控 | `/actuator/metrics` 记录入口、Lua、confirm、消费者和补偿指标 |
| 验证 | MCP IntelliJ JUnit 全部 14 个测试类通过；真实集成/持续压测待环境恢复 |
