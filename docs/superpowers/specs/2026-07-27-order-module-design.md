# 阶段五：订单模块设计

## 涉及表

| 表 | 关键字段 |
|----|---------|
| `order` | id, order_no(雪花), user_id, address_id, total_amount, coupon_id, status(6状态), created_at |
| `order_item` | id, order_id, sku_id, price(快照), quantity |
| `refund` | id, order_id, user_id, reason, amount, status(4状态), created_at |

## 文件清单

```
mall-server/src/main/java/com/mall/module/order/
├── entity/
│   ├── po/Order.java
│   ├── po/OrderItem.java
│   ├── po/Refund.java
│   ├── dto/CreateOrderDTO.java
│   ├── dto/OrderPageDTO.java
│   ├── dto/RefundDTO.java
│   ├── vo/OrderVO.java
│   ├── vo/OrderItemVO.java
│   └── vo/OrderListVO.java
├── mapper/
│   ├── OrderMapper.java
│   ├── OrderItemMapper.java
│   └── RefundMapper.java
├── service/
│   ├── OrderService.java
│   └── impl/OrderServiceImpl.java
└── controller/
    └── OrderController.java
```

## 状态机

```
PENDING ──支付──▶ PAID ──发货──▶ SHIPPED ──收货──▶ RECEIVED
   │                │                                  │
   ▼                ▼                                  ▼
CANCELLED      REFUNDED ◀──────────── 退款申请 ─────────┘
 (终态)          (终态)
```

**允许的转换：**
- `PENDING` → `PAID`（支付回调触发，本阶段先占位）
- `PENDING` → `CANCELLED`（用户取消）
- `PAID` → `SHIPPED`（商家发货，阶段九实现）
- `SHIPPED` → `RECEIVED`（用户确认收货）
- `PAID` / `RECEIVED` → `REFUNDED`（退款完成时更新）

**禁止的转换：** 已取消的不能支付，已退款的不能再次退款。

## API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders` | 创建订单 |
| GET | `/api/orders` | 订单列表（分页，可按状态筛选） |
| GET | `/api/orders/{id}` | 订单详情（含 order_items） |
| PUT | `/api/orders/{id}/cancel` | 取消订单 |
| POST | `/api/orders/{id}/refund` | 申请退款 |
| PUT | `/api/orders/{id}/receive` | 确认收货 |

## DTO/VO 设计

### CreateOrderDTO

```java
@NotNull Long addressId
@NotEmpty List<OrderItemDTO> items  // 内部类: skuId + quantity
Long couponId  // 可空，阶段七才用
```

### OrderPageDTO

```java
Integer pageNum = 1
Integer pageSize = 20
String status  // 可空，筛选条件
```

### RefundDTO

```java
@NotBlank String reason
```

### OrderVO（详情用）

```java
Long id, String orderNo, Long userId, Long addressId
BigDecimal totalAmount, String status, LocalDateTime createdAt
List<OrderItemVO> items
```

### OrderItemVO

```java
Long id, Long skuId, BigDecimal price  // 下单时快照价
Integer quantity, String specs, String image, String productName
```

### OrderListVO（列表用，不含 items）

```java
Long id, String orderNo, BigDecimal totalAmount
String status, LocalDateTime createdAt, Integer itemCount
```

## 核心业务规则

### 1. 创建订单（最复杂的方法）

- 从 `UserContext` 取 userId
- 校验 addressId 是否属于当前用户（查 address 表）
- 遍历 items，查每个 SKU 的当前价格作为快照
- 计算 totalAmount = Σ(sku.price × quantity)
- 生成 orderNo（`SnowflakeIdUtil.nextId()`）
- **同一个事务里**写入 order + 批量写入 order_item
- 返回 OrderVO

### 2. 订单列表

- 只查当前用户的订单
- 支持 status 可选筛选
- 分页查询，按创建时间倒序
- 返回 OrderListVO（不包含 items，减少数据量）

### 3. 订单详情

- 查 order + order_items，校验归属
- 每个 order_item 额外查 SKU 和 Product 信息做展示
- 金额用 order_item.price（快照价），不要再去查 SKU 当前价

### 4. 取消订单

- 校验归属 + 状态必须是 PENDING
- 更新 status → CANCELLED

### 5. 确认收货

- 校验归属 + 状态必须是 SHIPPED
- 更新 status → RECEIVED

### 6. 申请退款

- 校验归属 + 状态必须是 PAID 或 RECEIVED
- 创建 refund 记录（amount 取自 order.totalAmount，status=PENDING）
- 暂不修改 order 状态，等退款审批完成后才改（阶段八/九）

## 实现建议

1. 先写 PO → Mapper → DTO/VO，纯数据结构，不动脑
2. OrderService 接口定义 6 个方法签名
3. OrderServiceImpl 按顺序实现：创建订单 → 列表 → 详情 → 取消 → 收货 → 退款
4. 创建订单最复杂，建议先画好伪代码再写
5. 状态机用常量或枚举定义，不要散落字符串
6. 写完 Service 立即写单元测试，覆盖：正常创建/地址不归属/SKU不存在/取消非PENDING/收货非SHIPPED/退款非PAID等

## 关键注意事项

- **价格快照**：`order_item.price` 存的是下单时的 SKU 价格，以后 SKU 涨价不影响历史订单显示。这是最容易出错的点
- **事务边界**：创建订单时 order + order_item 必须在一个 `@Transactional` 里
- **归属校验**：每个操作都要验证 `order.user_id == UserContext.getUserId()`
- **状态校验**：cancel/receive/refund 都要先判断当前状态是否允许转换
