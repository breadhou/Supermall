# 实现顺序与功能清单

## 实现原则

- **自底向上**：先基础设施，再业务模块
- **先普通后特殊**：先普通下单，再秒杀（秒杀是普通下单的变体）
- **每个阶段可独立验证**：完成一个阶段就能跑起来看效果

---

## 第一阶段：基础设施（没有业务也可启动）

### 1.1 mall-common 公共模块

| 类 | 位置 | 说明 |
|----|------|------|
| `Result<T>` | mall-common/result/ | 统一响应体，静态工厂方法 ok() / fail() |
| `BusinessException` | mall-common/exception/ | 业务异常，带 code 和 message |
| `GlobalExceptionHandler` | mall-server/common/handler/ | @RestControllerAdvice 全局异常兜底 |
| `SnowflakeIdUtil` | mall-common/utils/ | 雪花算法 ID 生成器（基于 Hutool 的 IdUtil） |

### 1.2 mall-security 安全模块

| 类 | 位置 | 说明 |
|----|------|------|
| `JwtUtil` | mall-security/utils/ | Token 生成、解析、校验、刷新 |
| `JwtAuthFilter` | mall-security/filter/ | OncePerRequestFilter，从 Header 取 Token，写入 SecurityContext |
| `SecurityConfig` | mall-security/config/ | 放行 /api/auth/**、/doc.html，其余需认证 |
| `UserContext` | mall-security/utils/ | ThreadLocal 持有当前登录用户 ID，方便业务代码获取 |

### 1.3 mall-infra 基础设施模块

| 类 | 位置 | 说明 |
|----|------|------|
| `RedisConfig` | mall-infra/redis/ | RedisTemplate 序列化配置 |
| `RedisLock` | mall-infra/redis/ | 分布式锁（SET NX EX + Lua 解锁） |
| `RabbitMQConfig` | mall-infra/mq/ | 队列/交换机/绑定声明 + Jackson 消息序列化 |

### 1.4 配置文件

`application.yml` 中补全数据源、Redis、RabbitMQ、MyBatis-Plus、Knife4j 配置。

**验证：** 启动 MallApplication，访问 `/doc.html` 能看到 Knife4j 页面，接口文档可用。

---

## 第二阶段：用户模块（基石）

### 2.1 功能

| 接口 | 说明 |
|------|------|
| `POST /api/auth/register` | 注册（username + password + phone），BCrypt 加密 |
| `POST /api/auth/login` | 登录，返回 JWT access_token + refresh_token |
| `GET /api/auth/refresh` | 刷新 Token |
| `GET /api/user/address` | 查询自己的收货地址列表 |
| `POST /api/user/address` | 新增地址 |
| `PUT /api/user/address/{id}` | 修改地址 |
| `DELETE /api/user/address/{id}` | 删除地址 |

### 2.2 类清单

```
mall-server/src/main/java/com/mall/module/user/
├── controller/
│   ├── AuthController.java        # 注册、登录、刷新
│   └── AddressController.java     # 地址 CRUD
├── service/
│   ├── UserService.java           # 接口
│   ├── impl/UserServiceImpl.java  # 注册、登录逻辑
│   ├── AddressService.java
│   └── impl/AddressServiceImpl.java
├── mapper/
│   ├── UserMapper.java            # MyBatis-Plus BaseMapper
│   └── AddressMapper.java
├── entity/
│   ├── User.java                  # PO
│   ├── Address.java               # PO
│   ├── LoginVO.java               # VO（返回 token + 用户信息）
│   └── RegisterDTO.java           # DTO（注册参数）
```

### 2.3 关键逻辑

- **注册**：校验 username/phone 唯一性 → BCrypt 加密 → 雪花ID → 入库 → 返回 JWT
- **登录**：查 username → BCrypt 校验 → 生成 JWT（userId 写入 claims）→ 返回
- **JWT 内容**：`{ sub: userId, iat, exp }`，access_token 有效期 2 小时，refresh_token 有效期 7 天
- **地址**：纯 CRUD，查当前用户 = `UserContext.getUserId()`

**验证：** 注册 → 登录拿到 Token → 带 Token 调地址接口 → 增删改查正常。

---

## 第三阶段：商品模块

### 3.1 功能

| 接口 | 说明 |
|------|------|
| `GET /api/categories` | 分类树（一次查出所有，内存中拼树） |
| `GET /api/products` | 商品列表（分页 + 按分类筛选 + 关键词搜索） |
| `GET /api/products/{id}` | 商品详情（含 SKU 列表） |
| `GET /api/products/{id}/skus` | 某商品的全部 SKU |
| `GET /api/products/{id}/reviews` | 商品评价列表 |

### 3.2 类清单

```
mall-server/src/main/java/com/mall/module/product/
├── controller/
│   ├── CategoryController.java
│   └── ProductController.java
├── service/
│   ├── CategoryService.java
│   ├── impl/CategoryServiceImpl.java   # 查全表 → 内存递归拼树
│   ├── ProductService.java
│   └── impl/ProductServiceImpl.java    # 分页 + 条件查询
├── mapper/
│   ├── CategoryMapper.java
│   ├── ProductMapper.java
│   └── ProductSkuMapper.java
├── entity/
│   ├── Category.java                   # PO
│   ├── Product.java                    # PO
│   ├── ProductSku.java                 # PO
│   ├── ProductVO.java                  # VO（含 SKU 列表）
│   ├── CategoryVO.java                 # VO（含子分类列表）
│   └── ProductPageDTO.java             # DTO（分页查询条件）
```

### 3.3 关键逻辑

- **分类树**：`SELECT * FROM category ORDER BY sort` → 内存中按 parent_id 递归组装（只有几十条，不需要多次查库）
- **商品分页**：MyBatis-Plus 分页插件 + 分类筛选 + 名称模糊搜索 + 只查上架商品
- **商品详情**：查 product + 关联的 sku 列表一起返回

**验证：** 数据库手动插入几条分类和商品 → 调接口看分类树和商品列表正确。

---

## 第四阶段：购物车模块

### 4.1 功能

| 接口 | 说明 |
|------|------|
| `GET /api/cart` | 我的购物车列表（带 SKU 详情 + 商家名） |
| `POST /api/cart/items` | 添加商品 `{ skuId, quantity }`，已存在则累加数量 |
| `PUT /api/cart/items/{id}` | 修改数量 |
| `DELETE /api/cart/items/{id}` | 删除一项 |
| `DELETE /api/cart` | 清空购物车（下单后调用） |

### 4.2 类清单

```
mall-server/src/main/java/com/mall/module/cart/
├── controller/CartController.java
├── service/CartService.java
├── service/impl/CartServiceImpl.java
├── mapper/CartItemMapper.java
├── entity/CartItem.java                # PO
├── entity/CartItemVO.java             # VO（含 SKU 名、价格、图片）
└── entity/AddCartDTO.java             # DTO
```

### 4.3 关键逻辑

- 加购时查是否已存在（user_id + sku_id 唯一），存在则 `quantity += 新数量`
- 取购物车时 JOIN product_sku 表，带出价格、规格、图片，前端不用再查

**验证：** 添加商品 → 列表正确 → 加同 SKU 数量累加 → 改数量 → 删除。

---

## 第五阶段：普通订单 + 模拟支付

### 5.1 功能

| 接口 | 说明 |
|------|------|
| `POST /api/orders` | 下单 `{ addressId, cartItemIds[], couponId? }` |
| `GET /api/orders` | 我的订单列表（分页，按时间倒序） |
| `GET /api/orders/{orderNo}` | 订单详情（含 orderItem 列表） |
| `POST /api/orders/{orderNo}/cancel` | 取消订单（仅 PENDING 状态可取消） |
| `POST /api/payment/{orderNo}/pay` | 模拟支付（直接标记 PAID） |

### 5.2 类清单

```
mall-server/src/main/java/com/mall/module/order/
├── controller/
│   ├── OrderController.java
│   └── PaymentController.java
├── service/
│   ├── OrderService.java
│   ├── impl/OrderServiceImpl.java     # 下单、查单、取消
│   ├── PaymentService.java
│   └── impl/PaymentServiceImpl.java   # 模拟支付
├── mapper/
│   ├── OrderMapper.java
│   ├── OrderItemMapper.java
│   ├── PaymentRecordMapper.java
│   └── CartItemMapper.java            # 复用，下单后清购物车
├── entity/
│   ├── Order.java
│   ├── OrderItem.java
│   ├── PaymentRecord.java
│   ├── OrderVO.java                   # 含 orderItems + 支付信息
│   ├── OrderItemVO.java
│   └── CreateOrderDTO.java
```

### 5.3 关键逻辑

下单流程（事务）：

```
① 校验地址存在 && 属于当前用户
② 从购物车取选中商品，校验库存（SELECT ... FOR UPDATE 行锁）
③ 计算总金额
④ 校验优惠券（如果传了 couponId）→ 满减/折扣后金额
⑤ 扣减 product_sku.stock
⑥ 生成雪花 order_no → 写 order 表
⑦ 写 order_item 表（每个 SKU 一条，价格快照）
⑧ 清空已下单的购物车项
⑨ 返回 order_no
```

**验证：** 购物车加商品 → 下单 → 库存扣减正确 → 订单列表看到 → 模拟支付 → 状态变为 PAID。

---

## 第六阶段：优惠券模块

### 6.1 功能

| 接口 | 说明 |
|------|------|
| `GET /api/coupons` | 可领取的优惠券列表（总量 > 0 && 未过期） |
| `POST /api/coupons/{id}/claim` | 领取优惠券（扣减总量，生成 user_coupon） |
| `GET /api/coupons/my` | 我的优惠券（UNUSED/USED/EXPIRED） |
| `POST /api/coupons/my/{id}/use` | 使用优惠券（下单时调用，内部接口） |

### 6.2 类清单

```
mall-server/src/main/java/com/mall/module/promotion/
├── controller/CouponController.java
├── service/CouponService.java
├── service/impl/CouponServiceImpl.java
├── mapper/
│   ├── CouponMapper.java
│   └── UserCouponMapper.java
├── entity/
│   ├── Coupon.java
│   ├── UserCoupon.java
│   ├── CouponVO.java
│   └── ClaimCouponDTO.java
```

### 6.3 关键逻辑

- **领券**：校验总量 > 0 → `UPDATE coupon SET total = total - 1 WHERE id = ? AND total > 0` → 生成 user_coupon
- **下单用券**：在第五阶段下单流程第④步，校验 user_coupon 属于该用户、状态 UNUSED、满足 min_amount → 标记 USED
- **过期处理**：查询时脚本算 `created_at + expire_day < now()` → 标记 EXPIRED（或用定时任务，不必现在做）

**验证：** 后台手动插一条优惠券 → 用户领取 → "我的优惠券"列表看到 → 下单时选券 → 金额正确扣减。

---

## 第七阶段：秒杀模块（核心亮点）

### 7.1 功能

| 接口 | 说明 |
|------|------|
| `GET /api/seckill/activities` | 秒杀活动列表 |
| `GET /api/seckill/items/{activityId}` | 某活动的秒杀商品列表 |
| `GET /api/seckill/{itemId}/countdown` | 获取秒杀商品状态（未开始/进行中/已结束 + 剩余库存） |
| `POST /api/seckill/{itemId}/order` | **秒杀下单**（核心接口） |
| `GET /api/seckill/result/{itemId}` | 轮询秒杀结果（排队中/成功/失败） |

### 7.2 类清单

```
mall-server/src/main/java/com/mall/module/promotion/
├── controller/SeckillController.java
├── service/
│   ├── SeckillService.java
│   ├── impl/SeckillServiceImpl.java   # 活动管理 + 下单
│   └── impl/SeckillOrderConsumer.java # MQ 消费者
├── mapper/
│   ├── SeckillActivityMapper.java
│   ├── SeckillItemMapper.java
│   └── SeckillOrderMapper.java
├── entity/
│   ├── SeckillActivity.java
│   ├── SeckillItem.java
│   ├── SeckillOrder.java
│   ├── SeckillActivityVO.java
│   ├── SeckillItemVO.java
│   └── SeckillResultVO.java           # 秒杀结果
```

### 7.3 Redis Key 设计

| Key | 类型 | 说明 |
|-----|------|------|
| `mall:seckill:stock:{itemId}` | String(number) | 秒杀商品预热库存 |
| `mall:seckill:result:{itemId}:{userId}` | String | 秒杀结果 0=排队 1=成功 -1=失败 |
| `mall:seckill:limit:{itemId}:{userId}` | String(number) | 已购数量（防超限购） |
| `mall:seckill:lock:{itemId}:{userId}` | String | 分布式锁（防重复提交） |

### 7.4 MQ 设计

| 组件 | 名称 |
|------|------|
| 交换机 | `mall.seckill.exchange`（direct） |
| 队列 | `mall.seckill.order.queue` |
| 死信队列 | `mall.seckill.order.dlx.queue` |
| 绑定 key | `mall.seckill.order` |

消息体：`{ userId, seckillItemId, messageId }` — messageId 用于消费端幂等。

### 7.5 关键逻辑

**活动开始前（预热）：**
管理员创建秒杀活动 → 将 `seckill_item.stock` 写入 Redis `mall:seckill:stock:{itemId}`。

**秒杀下单（核心流程）：**

```
① 校验活动状态（NOT_STARTED → 拒绝，ENDED → 拒绝）
② Redis 判断用户是否已购买（limit bitmap / incr）
③ Redis Lua 脚本原子执行：
     stock = GET mall:seckill:stock:{itemId}
     if stock <= 0 → 返回库存不足
     DECR stock
     SET mall:seckill:result:{itemId}:{userId} = 0（排队中）
④ Lua 返回库存充足 → 发送 MQ 消息
⑤ 立即返回 "排队中" 给前端
```

**MQ 消费者（异步下单）：**

```
① 幂等检查：messageId 是否已消费（Redis SET NX）
② 校验 MySQL seckill_item.stock > 0
③ UPDATE seckill_item SET stock = stock - 1 WHERE stock > 0（乐观锁）
④ 扣减 product_sku.stock（回写日常库存）
⑤ 生成 order + order_item + seckill_order（一人一单唯一约束）
⑥ 更新 Redis mall:seckill:result:{itemId}:{userId} = 1（成功）
⑦ 异常 → 死信队列 → 补偿回滚 Redis 库存 + 标记结果为 -1
```

**轮询结果：**
`GET /api/seckill/result/{itemId}` → 查 Redis `mall:seckill:result:{itemId}:{userId}` → 0=排队 / 1=成功 / -1=失败。如果 Redis 没查到，回源查 seckill_order 表。

**验证：** 管理后台创建秒杀 → 预热库存 → 多个用户同时抢 → 不超卖 → 分布式锁防重 → MQ 消息可靠消费。

---

## 第八阶段：商家后台 + 管理后台

### 8.1 商家后台

| 接口 | 说明 |
|------|------|
| `POST /api/merchant/login` | 商家登录 |
| `POST /api/merchant/products` | 上架商品 |
| `PUT /api/merchant/products/{id}` | 编辑商品 |
| `PUT /api/merchant/products/{id}/off-shelf` | 下架商品 |
| `GET /api/merchant/orders` | 本店订单列表 |
| `POST /api/merchant/orders/{orderNo}/ship` | 发货（写 logistics 表） |

### 8.2 管理后台

| 接口 | 说明 |
|------|------|
| `POST /api/admin/login` | 管理员登录 |
| `GET /api/admin/users` | 用户列表 |
| `PUT /api/admin/users/{id}/status` | 禁用/启用用户 |
| `POST /api/admin/seckill/activities` | 创建秒杀活动 |
| `POST /api/admin/coupons` | 创建优惠券模板 |
| `GET /api/admin/statistics` | 简单统计（用户数、订单数、GMV） |

### 8.3 关键点

- 商家和管理员用独立的安全过滤器链（不同于 C 端用户，可以不用 JWT，或者用不同 role 的 JWT）
- 商家后台只看自己店铺的订单和商品
- 管理后台的统计查询用聚合 SQL

**验证：** 商家登录 → 上架商品 → C 端看到 → 管理员登录 → 用户列表 → 创建秒杀活动。

---

## 第九阶段：退款 + 评价 + 物流

### 9.1 功能

| 接口 | 说明 |
|------|------|
| `POST /api/orders/{orderNo}/refund` | 申请退款（PAID/SHIPPED 状态） |
| `PUT /api/merchant/refunds/{id}/approve` | 商家同意退款 → 回滚库存 |
| `PUT /api/merchant/refunds/{id}/reject` | 商家拒绝退款 |
| `POST /api/reviews` | 发表评价（仅已收货订单） |
| `GET /api/orders/{orderNo}/logistics` | 查物流轨迹 |

这些是收尾功能，逻辑简单，不多展开。

---

## 总览：实现顺序图

```
阶段一 (基础设施)
    ↓
阶段二 (用户)        ← 必须先有用户
    ↓
阶段三 (商品)        ← 必须先有商品
    ↓
阶段四 (购物车)      ← 依赖用户 + 商品
    ↓
阶段五 (普通下单)    ← 依赖购物车 + 用户 (核心业务流程)
    ↓
    ├── 阶段六 (优惠券) ← 下单时可叠加
    │
    └── 阶段七 (秒杀)   ← 基于下单改造 (简历最大亮点)
           ↓
阶段八 (商家/管理后台) ← 管理前面所有数据
    ↓
阶段九 (退款/评价/物流) ← 收尾
```

每个阶段完成后 commit 一次，方便回退和查看进度。
