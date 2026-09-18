# 阶段九 9.1：商家端设计

## 范围

实现 `docs/implementation-plan.md` 第九阶段中的 **9.1 商家后台**，共 7 个接口：商家登录、商品上架/编辑/下架、本店订单列表、发货、送达。

本轮顺带偿还一笔技术债：阶段八实现了 `LogisticsService.shipOrder` 与 `markDelivered`，但注释写明「发货与送达暂不暴露为 HTTP 接口，待阶段九接入商家权限后开放」。商家端做出来即为其提供出口。

## 不在范围

- **9.2 管理后台**（管理员登录、用户管理、创建秒杀活动与券、统计）——下一轮单独做。本轮只把 `admin_user.merchant_id` 这个模型立住，`SUPER_ADMIN` 暂不可登录商家端。
- **退款审批**（`/api/merchant/refunds/*`）——`refund` 表当前无数据、阶段五的退款流程已验证，留到管理端那轮或单独一轮。
- **本店商品列表**（`GET /api/merchant/products`）——文档 9.1 只列了上架/编辑/下架，未含列表，本轮照此执行。上架接口的返回值带 `id`，够支撑编辑与下架的验证流程。**但真实商家控制台没有列表会很难用**：商家长时间后无法找回自己的商品 ID，也看不到自己在售的商品全貌。建议下一轮补上（一个分页查询，成本很低）。
- **拆单与多商家履约**——见文末「已知假设与限制」。

## 涉及表

| 表 | 变更 |
|----|------|
| `admin_user` | **新增列** `merchant_id BIGINT DEFAULT NULL` |
| `merchant` | 不变（已有 1 条测试数据 `900000000000000001`） |
| `product` / `product_sku` | 不变（`product.merchant_id` 已存在且为 `NOT NULL`） |
| `order` / `order_item` | 不变（**都没有 `merchant_id`**，本店归属靠关联推导） |
| `logistics` | 不变（沿用阶段八结论，见「已知假设与限制」） |

### 迁移语句

`init.sql` 中 `admin_user` 的建表语句同步加上该列；已存在的库执行：

```sql
ALTER TABLE admin_user
    ADD COLUMN merchant_id BIGINT DEFAULT NULL COMMENT 'FK → merchant.id，ADMIN 必填、SUPER_ADMIN 为空' AFTER role;
```

`admin_user` 当前是空表，无需数据迁移。

### 语义

| role | merchant_id | 含义 |
|------|-------------|------|
| `ADMIN` | **必填** | 商家账号，登录后可管理自己店铺的商品与订单 |
| `SUPER_ADMIN` | 为空 | 平台账号，本轮不可登录商家端（登录时拒绝） |

## 设计决策

### 1. 认证走完全独立的一套（路线 B）

`docs/implementation-plan.md` 9.3 写的是「商家和管理员用独立的安全过滤器链（不同于 C 端用户，可以不用 JWT，或者用不同 role 的 JWT）」，留白由本轮决定。

**采用独立密钥 + 独立过滤器 + 独立过滤器链**，`JwtUtil`、`JwtAuthFilter`、`UserContext`、`SecurityConfig` **一行不改**。

理由：阶段二~八的全部接口都跑在现有认证路径上且已通过 162 个测试与真实集成验证，改动它的回归面太大。独立密钥带来的是**签名层面的隔离**——C 端 token 不是「被判断拦住」，而是物理上无法通过商家端的签名校验。

代价是约 80 行结构相似的代码，以及需要维护两个密钥。接受。

### 2. 商品创建为 SPU + SKU 一次提交

价格与库存都在 `product_sku` 上，只创建 SPU 的接口没有实际用途。`POST /api/merchant/products` 接收内嵌的 SKU 列表，在同一事务内写入 `product` 与 `product_sku`。

### 3. 增加 `deliver` 接口

文档 9.1 只列了 `ship`，但阶段八的 `markDelivered` 已实现 `SHIPPED → DELIVERED` 却一直没有出口。本轮一并开放，使 `PAID → SHIPPED → DELIVERED → RECEIVED` 完整可达。

### 4. 商家订单查询放在 merchant 模块内

「本店订单」需要 `order → order_item → product_sku → product` 四表关联。该查询以 merchant 模块自己的 Mapper + XML 实现，**不改动 order 模块**，保持模块自包含。

### 5. 唯一需要触碰阶段八代码的地方

`LogisticsServiceImpl.verifyOwnership` 用 `UserContext.getUserId()` 与 `order.getUserId()` 比对。商家请求下 `UserContext` 为 null，因此 `shipOrder` / `markDelivered` **无法被商家端直接复用**。

改法：把两者的状态流转逻辑提取为私有方法，新增两个 merchant 侧入口：

```java
LogisticsVO shipOrderForMerchant(Long orderId, ShipOrderDTO dto);
LogisticsVO markDeliveredForMerchant(Long orderId);
```

`shipOrder(orderId, dto)` 保留原有「先 `verifyOwnership` 再流转」的行为，由现有 `LogisticsServiceImplTest` 的 6 个用例继续兜住。**纯新增 + 提取，不改变既有行为。**

## 认证架构

### 新增组件

| 组件 | 模块 | 职责 |
|------|------|------|
| `MerchantJwtUtil` | mall-security | `@Component`，密钥读配置 `mall.merchant.jwt-secret`。载荷：`sub`=adminUserId、`merchantId`、`role` |
| `MerchantContext` | mall-security | ThreadLocal，存 `adminUserId` / `merchantId`；请求结束清除 |
| `MerchantAuthFilter` | mall-security | 解析 `Authorization: Bearer`，校验签名，写入 `MerchantContext` 与 SecurityContext（authority `ROLE_MERCHANT`） |
| `MerchantSecurityConfig` | mall-security | `@Order(1)` + `securityMatcher("/api/merchant/**")` 的独立过滤器链 |

### 过滤器链为何零改动即可生效

`SecurityConfig` 现有的链**没有 `@Order`**，因此取默认值 `LOWEST_PRECEDENCE`；`MerchantSecurityConfig` 用 `@Order(1)` 且 `securityMatcher` 限定 `/api/merchant/**`，会**先被匹配**。Spring Security 只执行**第一个匹配到的链**，所以：

- `/api/merchant/**` 完全不经过 C 端的 `JwtAuthFilter`
- 其余路径完全不经过商家链

`SecurityConfig` 无需任何修改。

### 过滤器链配置要点

```
securityMatcher("/api/merchant/**")
csrf          disabled
session        STATELESS
authorizeHttpRequests:
    POST /api/merchant/login  → permitAll
    其余                       → hasRole("MERCHANT")
addFilterBefore(MerchantAuthFilter, UsernamePasswordAuthenticationFilter)
```

### 配置项

```yaml
mall:
  merchant:
    jwt-secret: ${MERCHANT_JWT_SECRET:...}
    access-expire-ms: 7200000   # 2 小时，与 C 端一致
```

密钥必须从环境变量注入，**不在仓库中出现明文默认值**。

## 文件清单

```
mall-security/src/main/java/com/mall/security/
├── utils/MerchantJwtUtil.java
├── utils/MerchantContext.java
├── filter/MerchantAuthFilter.java
└── config/MerchantSecurityConfig.java

mall-server/src/main/java/com/mall/module/merchant/
├── entity/
│   ├── po/Merchant.java
│   ├── po/AdminUser.java
│   ├── dto/MerchantLoginDTO.java
│   ├── dto/MerchantProductDTO.java      # SPU + 内嵌 SKU 列表
│   ├── dto/MerchantOrderPageDTO.java
│   ├── vo/MerchantLoginVO.java
│   ├── vo/MerchantProductVO.java        # 含 SKU 列表
│   └── vo/MerchantOrderVO.java
├── mapper/
│   ├── MerchantMapper.java
│   ├── AdminUserMapper.java
│   └── MerchantOrderMapper.java         # 四表关联查询
├── service/
│   ├── MerchantAuthService.java
│   ├── MerchantProductService.java
│   ├── MerchantOrderService.java
│   └── impl/  (三个实现类)
└── controller/
    ├── MerchantAuthController.java
    ├── MerchantProductController.java
    └── MerchantOrderController.java

mall-server/src/main/resources/mapper/MerchantOrderMapper.xml
```

## API 设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/merchant/login` | 商家登录 | 放行 |
| POST | `/api/merchant/products` | 上架商品（SPU + SKU） | ROLE_MERCHANT |
| PUT | `/api/merchant/products/{id}` | 编辑商品 | ROLE_MERCHANT |
| PUT | `/api/merchant/products/{id}/off-shelf` | 下架商品 | ROLE_MERCHANT |
| GET | `/api/merchant/orders` | 本店订单列表（分页） | ROLE_MERCHANT |
| POST | `/api/merchant/orders/{orderNo}/ship` | 发货 | ROLE_MERCHANT |
| POST | `/api/merchant/orders/{orderNo}/deliver` | 送达 | ROLE_MERCHANT |

## DTO/VO 设计

### MerchantLoginDTO

```java
@NotBlank String username
@NotBlank String password
```

### MerchantLoginVO

```java
String accessToken
Long adminUserId, Long merchantId, String merchantName, String role
```

### MerchantProductDTO（创建与编辑共用）

```java
@NotBlank @Size(max=256) String name
String description
@NotNull Long categoryId
String status                  // 可空；创建时默认 ON_SHELF，允许 DRAFT
@NotEmpty @Valid List<SkuDTO> skus

static class SkuDTO {
    Long id                    // 编辑时携带表示已有 SKU；为空表示新增
    @Size(max=512) String specs
    @NotNull @DecimalMin("0.01") BigDecimal price
    @NotNull @Min(0) Integer stock
    @Size(max=512) String image
}
```

### MerchantProductVO

```java
Long id, Long merchantId, String name, String description
Long categoryId, String status, LocalDateTime createdAt
List<SkuVO> skus               // skuId, specs, price, stock, image
```

### MerchantOrderPageDTO

```java
Integer pageNum = 1
Integer pageSize = 20
String status                  // 可空，按订单状态筛选
```

### MerchantOrderVO

```java
Long orderId, String orderNo, String status
BigDecimal storeAmount         // 本店商品金额小计，非整单金额
LocalDateTime createdAt
String receiver, String phone, String address   // 收货信息（发货必需）
List<ItemVO> items             // 仅本店明细
```

## 核心业务规则

### 1. 商家登录

1. 按 `username` 查 `admin_user`（走 `uk_username` 唯一索引）
2. 账号不存在 与 密码不匹配 → **返回同一个错误码**，不泄漏用户名是否存在
3. `merchant_id` 为空（`SUPER_ADMIN`）→ 拒绝，提示未绑定商家
4. 校验 `merchant.status = 1`（正常），被禁用的商家不能登录
5. 签发 token，载荷含 `adminUserId` / `merchantId` / `role`

### 2. 商品归属隔离（安全核心）

- **创建**：`merchant_id` 强制取自 `MerchantContext.getMerchantId()`，**不接受请求参数传入**
- **编辑 / 下架**：先按 id 查出商品，校验 `product.merchant_id` 等于当前商家；不等则抛 `MERCHANT_PRODUCT_FORBIDDEN`，**不区分「不存在」与「不属于你」**
- 编辑时 SKU 的处理：请求中带 `id` 的做更新，不带的做新增；**库中已有但请求未提及的 SKU 做删除**（全量覆盖语义，需在接口文档中写明）

### 3. 本店订单

- 查询条件：该订单**至少有一条** `order_item` 的 SKU 属于当前商家的商品
- 返回的 `items` **只包含本店明细**，看不到其他商家的商品
- `storeAmount` 是本店明细的小计，**不是** `order.total_amount`（后者含其他商家商品与整单优惠）

### 4. 发货

1. 按 `orderNo` 查订单（不存在 → `ORDER_NOT_EXIST`）
2. **归属校验**：订单需含本店商品，否则 `MERCHANT_ORDER_FORBIDDEN`
3. 委托 `LogisticsService.shipOrderForMerchant(orderId, dto)`，由其完成：订单必须为 `PAID`、尚无物流记录、写入 `logistics` 并把订单置为 `SHIPPED`

### 5. 送达

同上路径，委托 `markDeliveredForMerchant`：订单必须为 `SHIPPED`、物流记录必须为 `SHIPPED`，置为 `DELIVERED`。C 端原有的 `PUT /api/orders/{id}/receive` 保持可用，完成 `DELIVERED → RECEIVED`。

## 错误码

`ResultStatus` 新增 `9xxxx` 段（现有最大为 80003，无冲突）：

| 码值 | 枚举 | 消息 |
|------|------|------|
| 90000 | `MERCHANT_PRODUCT_FORBIDDEN` | 无权操作该商品 |
| 90001 | `MERCHANT_ORDER_FORBIDDEN` | 无权操作该订单 |
| 90002 | `MERCHANT_LOGIN_FAILED` | 商家账号或密码错误 |
| 90003 | `MERCHANT_NOT_BOUND` | 该账号未绑定商家，不能登录商家端 |
| 90004 | `MERCHANT_DISABLED` | 商家已被禁用 |

## 测试

### 单元测试

| 测试类 | 重点 |
|--------|------|
| `MerchantJwtUtilTest` | 签名校验、过期、载荷解析；**用 C 端密钥签的 token 必须被拒绝** |
| `MerchantAuthFilterTest` | 有效 token 写入上下文、无 token 不认证、请求结束清除 ThreadLocal |
| `MerchantAuthServiceImplTest` | 密码不匹配与账号不存在返回同一码值；`merchant_id` 为空被拒；商家被禁用被拒 |
| `MerchantProductServiceImplTest` | **归属隔离**：持 A 商家上下文改 B 商家商品必须抛 `MERCHANT_PRODUCT_FORBIDDEN`；创建时 `merchant_id` 忽略请求值；SKU 全量覆盖语义 |
| `MerchantOrderServiceImplTest` | 本店订单只返回本店明细；`storeAmount` 不等于整单金额；无本店商品的订单不可发货 |
| `MerchantOrderControllerTest` / `MerchantProductControllerTest` | 路由与参数校验 |

### 集成验证（真实环境）

登录 → 上架商品（含 2 个 SKU）→ C 端 `GET /api/products` 能看到 → C 端下单 → 商家查本店订单 → 发货 → 送达 → C 端确认收货，逐环节核对数据库状态。

**跨商家隔离的验证**：造第二个商家及其商品，确认 A 商家看不到、也改不了 B 商家的商品与订单。

## 种子账号

`admin_user` 无预置数据，且**仓库中不得出现明文口令**（沿用 2026-09-18 移除默认口令的结论）。创建步骤：

```powershell
# 1. 设置口令（不写入仓库）
$env:MERCHANT_ADMIN_PASSWORD = '<自定口令>'

# 2. 写一个一次性的 jshell 脚本
@'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
System.out.println(new BCryptPasswordEncoder().encode(System.getenv("MERCHANT_ADMIN_PASSWORD")));
/exit
'@ | Out-File -Encoding ascii "$env:TEMP\genhash.jsh"

# 3. 生成 BCrypt 哈希
$m2 = "$env:USERPROFILE\.m2\repository"
$crypto = "$m2\org\springframework\security\spring-security-crypto\6.4.4\spring-security-crypto-6.4.4.jar"
$jcl   = "$m2\commons-logging\commons-logging\1.2\commons-logging-1.2.jar"
jshell --class-path "$crypto;$jcl" -q "$env:TEMP\genhash.jsh"

# 4. 用完后删除
Remove-Item "$env:TEMP\genhash.jsh"
```

两个注意点，都是实测踩出来的：

- **classpath 必须带 `commons-logging`**，否则 `BCryptPasswordEncoder` 初始化抛 `NoClassDefFoundError`。
- **路径必须是 Windows 形式**（`C:\...`，分隔符 `;`）；在 Git Bash 里用 `/c/...` 会被 Windows 版 `jshell` 当成不存在的文件。
- PowerShell here-string 的结束符 `'@` **必须顶格**，否则会被解析错（实测会报 `Remove-Item on system path '/exit' is blocked`）。

该命令已于 2026-09-18 实测：生成的哈希能够正确匹配、错误口令返回 false。

```sql
-- 3. 插入账号（哈希替换为上一步输出）
INSERT INTO admin_user (id, username, password, role, merchant_id)
VALUES (<雪花ID>, '<用户名>', '<BCrypt哈希>', 'ADMIN', 900000000000000001);
```

## 已知假设与限制

**本系统假设：一张订单只含一个商家的商品，且只发一次货。** 由 `logistics.order_id` 的唯一索引 `uk_logistics_order_id` 在数据库层面保证。

该假设在真实电商中不成立——多商家购物车、同商家拆包、逆向物流都会让「订单 : 物流」变成 1:N。真实平台的做法是引入两级订单（主订单按商家拆成子订单，子订单再拆发货单，发货单下挂物流轨迹），本项目**有意不做**：

- 本项目的主要展示目标是秒杀高并发（阶段六），订单物流保持简化
- 完整拆单需要改动阶段五已验证的下单事务与订单表结构，投入产出不划算

**因此本轮 9.1 的规则是**：商家能看到所有包含本店商品的订单（明细只展示本店行），订单里有本店商品即可发货，一单只能发一次。当订单确实跨商家时，另一商家的商品会被标记为已发货——这是上述简化假设的直接后果，不是缺陷。

`logistics` 表本身只是运单头（公司 + 单号 + 状态），没有物流轨迹表，「查看物流」只能显示状态而不能显示时间线。同样属有意简化。

## 实现顺序建议

1. `admin_user` 加列 + `init.sql` 同步 + 错误码
2. `MerchantJwtUtil` / `MerchantContext` / `MerchantAuthFilter` / `MerchantSecurityConfig` + 单元测试
3. 登录接口 + 种子账号 + 集成验证登录
4. 商品三接口 + 归属隔离测试
5. `LogisticsService` 两个 merchant 侧方法（提取私有方法，跑通原有 6 个测试）
6. 订单列表 + 发货 + 送达
7. 端到端集成验证（含第二个商家的隔离验证）
