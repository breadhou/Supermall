# 阶段六：秒杀模块设计

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
- `RedisLock` — Lua 解锁分布式锁（lock/unlock）
- `SeckillKey` — 6 个 KeyPrefix（stock/result/userLimit/userLock/path/verifyCode）
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

- 查 `seckill_item`，取 `stock` 字段
- 写入 Redis：`redisService.set(SeckillKey.stock, itemId.toString(), stock)`
- 后续秒杀不再查 MySQL 库存，全靠 Redis 扣减

### 2. 获取秒杀路径 `POST /{itemId}/path`

- 校验 seckill_item 存在
- 校验活动是否在有效时间窗口内（start_time ≤ now ≤ end_time）
- 生成随机 UUID 作为 path 值
- 缓存：`redisService.set(SeckillKey.path, itemId + ":" + userId, uuid, 60s)`
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
- `remainingStock` 从 Redis `SeckillKey.stock` 读取
- 未到开始时间返回 `NOT_STARTED`，已过期返回 `ENDED`

### 4. 执行秒杀 `POST /{itemId}/{path}`（核心）

**步骤：**

1. **校验 path** — 查 `SeckillKey.path`，不存在或值不匹配 → 拒绝
2. **RedisLock 防重** — `SeckillKey.userLock` 加锁（10s），防止同一用户并发重复请求
3. **校验限购** — 查 `SeckillKey.userLimit`，已购数量 + 本次数量 > `limit_per_user` → 拒绝
4. **Lua 脚本原子扣库存** — `DECR SeckillKey.stock`，返回值 < 0 → 拒绝（库存不足，写 result=-1）
5. **库存扣减成功** → 发 MQ 消息 `SeckillMessage(itemId, userId, quantity)` 到 `mall.seckill.direct` + routingKey `order.create`
6. **写结果缓存** — `redisService.set(SeckillKey.result, itemId+":"+userId, 0)` → 0=排队中
7. **释放 Lock**
8. 返回 `{ "message": "排队中，请轮询结果" }`

**三层超卖防护：**
1. Redis Lua 原子 DECR（第一道防线）
2. MySQL 乐观锁 `UPDATE seckill_item SET stock = stock - ? WHERE stock >= ?`（第二道防线）
3. `seckill_order` 唯一索引 `uk_user_seckill`（第三道防线，防重）

### 5. MQ 消费者 SeckillConsumer

- 监听队列 `mall.seckill.order`
- 手动确认模式：`acknowledge-mode: manual`，prefetch=1
- 消费流程：
  1. 解析 `SeckillMessage`
  2. MySQL 乐观锁扣库存：`UPDATE seckill_item SET stock = stock - ? WHERE id = ? AND stock >= ?`
  3. 查 SKU 当前价获取快照
  4. 生成 order + order_item + seckill_order（`@Transactional`）
  5. 更新结果缓存：`redisService.set(SeckillKey.result, itemId+":"+userId, 1)` → 1=成功
  6. Redis INCR `SeckillKey.userLimit` +1
  7. channel.basicAck
  8. 任一步失败 → channel.basicNack → 自动进入死信队列
  9. 死信处理：更新 result=-1，回滚 Redis 库存 INCR

### 6. 轮询结果 `GET /result/{itemId}`

- 读 `SeckillKey.result`：`redisService.get(SeckillKey.result, itemId+":"+userId, Integer.class)`
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
    private Long itemId;
    private Long userId;
    private Integer quantity;
    /** 消息唯一 ID（防重） */
    private String messageId;
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
5. Lua 脚本写在 `mall-infra` 或 `resources/lua/` 下
6. SeckillConsumer 是 `@RabbitListener` + `@Component`，prefetch=1
7. 这个阶段必须写集成测试（`@SpringBootTest`），因为 Redis + MQ 的并发行为 Mock 看不到

## 关键注意事项

- **库存预热时机**：preheat 接口在活动开始前手动调用（阶段七做活动管理后可以自动触发）
- **path 过期时间**：60 秒，和 SeckillKey.path 的过期时间一致
- **result 缓存 TTL**：3600 秒，活动结束后自然过期
- **死信队列**：消费失败的消息不丢，存到 DLQ，后续补定时任务回滚 Redis 库存
- **并发安全**：eutoLock 防单用户重入，Lua 原子扣库存，MySQL 乐观锁兜底，uk 约束最后防线
