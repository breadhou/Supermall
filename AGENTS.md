# AGENTS.md

本文件是本项目面向 Codex、Claude Code 及其他代码代理的协作指南。除非用户另有明确要求，修改代码时应遵守本文档中的架构、命名、测试和验证约定。

## 适用范围

- 根目录的本文件适用于整个项目。
- 开始修改前，先阅读相关模块的现有实现、测试和配置。
- 只修改完成当前任务所需的文件，保留用户已有的未提交改动。
- 完成代码修改后，运行与改动范围匹配的测试或构建命令，并报告验证结果。

## 项目概述

这是一个从单体架构起步的电商平台，核心聚焦秒杀高并发场景（目标万级 QPS），后续演进路径为 Spring Cloud Alibaba 微服务。

技术栈：Java 17、Spring Boot 3.4.4、MyBatis-Plus 3.5.10、MySQL 8.0、Redis 7.x、RabbitMQ 3.12.x、Spring Security 6.x、JWT 0.13.0、Hutool 5.8.47、Knife4j 4.5.0。

## 构建与运行

```bash
# 编译整个项目
mvn clean compile -DskipTests

# 运行所有测试
mvn test

# 运行单个测试类
mvn test -pl mall-security -Dtest=JwtUtilTest

# 启动应用（mall-server 是可执行 jar）
mvn spring-boot:run -pl mall-server

# 打包
mvn clean package -DskipTests
```

应用启动后访问 `http://localhost:8080/doc.html` 查看 Knife4j API 文档。

## Maven 模块结构

```text
supermall/
├── pom.xml                  # 父 POM：Spring Boot 3.4.4 parent + 版本/模块管理
├── mall-common/             # 公共模块：Result<T>、BusinessException、ResultStatus、雪花 ID
├── mall-security/           # 安全模块：JWT 生成/校验、Spring Security 无状态配置、UserContext
├── mall-infra/              # 基础设施：Redis、分布式锁、KeyPrefix 体系、RabbitMQ 队列声明
└── mall-server/             # 主服务：启动类 + 所有业务模块（按 module/<domain> 分包）
    └── src/main/
        ├── java/com/mall/
        │   ├── MallApplication.java
        │   ├── common/handler/GlobalExceptionHandler.java
        │   └── module/user/   # 用户模块（controller → service → mapper → entity）
        └── resources/
            ├── application.yml
            └── db/init.sql     # 全量建表脚本（15 张表）
```

依赖关系：`mall-server → mall-security → mall-common`，`mall-server → mall-infra → mall-common`。`mall-security` 和 `mall-infra` 互相独立。

## 架构与编码约定

### 分层约束

| 层 | 所在模块 | 规则 |
|----|---------|------|
| Controller | mall-server | 只做参数校验和路由，不写业务逻辑 |
| Service | mall-server | 业务编排、事务管理，不直接操作 Redis/MQ |
| Mapper | mall-server | 使用 MyBatis-Plus BaseMapper，复杂 SQL 写 XML |
| Infrastructure | mall-infra | 封装 Redis/MQ 操作，不包含业务判断 |
| Security | mall-security | JWT 认证过滤，不包含业务规则 |
| Common | mall-common | 响应体、异常、工具类，不依赖其他业务模块 |

### 对象转换约定

- **PO**（Persistent Object）：对应数据库表，Mapper 层使用。
- **VO**（View Object）：返回前端，Controller 层使用。
- **DTO**（Data Transfer Object）：接收前端参数，在 Service 间传输。

### JWT 认证流程

1. `JwtAuthFilter`（`OncePerRequestFilter`）从 `Authorization: Bearer <token>` 提取 Token。
2. `JwtUtil.validate()` 校验签名和过期时间。
3. 校验通过后，将 `userId` 写入 `SecurityContextHolder` 和 `UserContext`（ThreadLocal）。
4. 业务代码通过 `UserContext.getUserId()` 获取当前用户。
5. 请求结束后调用 `UserContext.clear()` 清除 ThreadLocal。

无需认证的白名单路径：`/api/auth/**`、`/doc.html`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`。

### Redis Key 命名规范

使用 `KeyPrefix` 接口体系，前缀格式为 `mall:<domain>:<purpose>:`。例如：

- `mall:seckill:stock:{itemId}`：秒杀库存预热。
- `mall:seckill:result:{itemId}:{userId}`：秒杀结果（`0`=排队，`1`=成功，`-1`=失败）。
- `mall:seckill:limit:{itemId}:{userId}`：已购数量。

已有 KeyPrefix 实现：`Userkey`、`GoodsKey`、`MiaoshaKey`、`MiaoShaUserKey`、`OrderKey`，位于 `mall-infra/redis/`。

### RabbitMQ 秒杀消息流

- 交换机：`mall.seckill.direct`（Direct 类型）。
- 主队列：`mall.seckill.order`，routing key 为 `order.create`。
- 死信队列：`mall.seckill.order.dlq`，routing key 为 `order.create.dlx`。
- 消费端需手动确认：`acknowledge-mode: manual`，`prefetch=1`。

### 统一响应格式

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1706000101000 }
```

`Result<T>` 提供 `Result.build()` 系列静态工厂方法，失败使用 `Result.fail(ResultStatus)`。`BusinessException` 由 `GlobalExceptionHandler` 全局兜底捕获。

## 实现阶段规划

基于 `db/init.sql` 的 15 张表，按业务域拆分为 9 个阶段：

| 阶段 | 业务域 | 涉及表 | 状态 |
|------|--------|--------|------|
| 阶段一 | 基础设施 | common/security/infra 模块 | 已完成 |
| 阶段二 | 用户模块 | user、address | 已完成 |
| 阶段三 | 商品模块 | category、product、product_sku、review | 已完成 |
| 阶段四 | 购物车 | cart_item | 已完成 |
| 阶段五 | 订单模块 | order、order_item、refund | 已完成 |
| 阶段六 | 秒杀模块 | seckill_activity、seckill_item、seckill_order | 进行中（核心接口、服务层和 MQ 已接入） |
| 阶段七 | 优惠券 | coupon、user_coupon | 待实现 |
| 阶段八 | 支付物流 | payment_record、logistics | 待实现 |
| 阶段九 | 商家后台 | merchant、admin_user | 待实现 |

## 当前进度

### 阶段一：基础设施（已完成）

- `mall-common`：`Result<T>`、`BusinessException`、`ResultStatus` 枚举、雪花 ID。
- `mall-security`：`JwtUtil`、`JwtAuthFilter`、`UserContext`、Spring Security 无状态配置。
- `mall-infra`：`RedisService`、`RedisLock`、KeyPrefix 体系、RabbitMQ 秒杀队列声明。

### 阶段二：用户模块（已完成）

- User：注册、登录、刷新 Token，BCrypt 密码加密，JWT 双 Token。
- Address：CRUD、默认地址管理。
- 25 个单元测试（`UserServiceImpl`、`AddressServiceImpl`）。
- 已完成统一分层规范重构（接口注入、URL 路径、修饰符）。

### 阶段三：商品模块（已完成）

- **Category**：分类树（一次查库，内存递归组装），`GET /api/categories`。
- **Product SPU**：分页查询（关键词、分类、状态筛选 + MyBatis-Plus 分页插件），`GET /api/products`；商品详情包含 SKU 聚合信息（最低价、总库存、首图），`GET /api/products/{id}`。
- **Product SKU**：单 SKU 查询，`GET /api/skus/{id}`。
- **Review**：按商品查询评价并按时间倒序，`GET /api/products/{id}/reviews`。
- **配置**：`MybatisPlusConfig`（`PaginationInnerInterceptor`）。
- 24 个单元测试（`CategoryServiceImpl`、`ProductServiceImpl`、`SkuServiceImpl`、`ReviewServiceImpl`、`CategoryController`），全部通过。

### 阶段四：购物车模块（已完成）

- **CartItem**：加入购物车（同 SKU 累加）、列表查询（含 SKU/商品名称）、修改数量、删除单项、清空购物车。
- Controller 路径：`POST/GET/PUT/DELETE /api/cart`；DTO 含 `@Min/@Max` 校验。
- `CartItemVO` 包含 SKU 规格、价格、库存、图片和商品名称。
- SKU 不存在时抛出 `BusinessException`。
- 10 个单元测试（`CartServiceImplTest`），全部通过。

### 阶段五：订单模块（已完成）

- **Order**：创建订单（事务写入 order + order_item，保存价格快照）、列表查询（分页 + 状态筛选 + itemCount）、详情查询（含 SKU 展示信息）。
- **状态机**：`PENDING → CANCELLED`（取消）、`SHIPPED → RECEIVED`（确认收货）；`PAID/RECEIVED` 可申请退款。
- **Refund**：创建退款申请记录，拥有独立生命周期（`PENDING/APPROVED/REJECTED/COMPLETED`）。
- **ResultStatus 重构**：清理 11 个未使用码值、修正拼写错误、按模块分段（1xxxx 通用、2xxxx 用户、5xxxx 订单、6xxxx 秒杀）。
- Controller 路径：`POST/GET/PUT /api/orders`；DTO 使用 `@Valid` 校验。
- 18 个单元测试（`OrderServiceImplTest`），全部通过。

### 阶段六：秒杀模块（进行中）

当前工作区已完成或开始实现以下基础部分：

- 秒杀活动、秒杀商品、秒杀订单的 PO 和 Mapper。
- 秒杀倒计时、秒杀结果 VO。
- `SeckillService` 及其实现类的基础结构。
- 秒杀 Redis Key、库存预热、动态路径和 Lua 原子扣库存接入。
- 重复请求校验、排队结果写入和 RabbitMQ 秒杀消息发送。
- `SeckillConsumer` 已实现主队列事务落库、手动 ACK、失败转死信和死信库存回滚。
- 秒杀消息使用 JSON 转换器，消费者包含数据库乐观锁扣库存和重复消息幂等处理。
- `SeckillController` 已提供预热、路径、倒计时、执行秒杀和结果轮询 5 个接口。
- 已补充 `SeckillServiceImplTest` 14 个服务层单元测试和 `SeckillControllerTest` 5 个 Controller 单元测试，均通过。
- 已修复 `mall-common/pom.xml` 中重复且版本不一致的 `mybatis-plus-annotation` 依赖，统一到 `${mybatis-plus.version}`（3.5.10），解决 `FieldStrategy.IGNORED` 启动异常。
- `MallApplication` 已在本地成功启动，HTTP 服务监听 8080 端口；临时凭据不写入项目文档。
- 已修复 `RedisService.get()` 对 Redis 字符串、数字等标量值的反序列化问题，并保留 JSON 对象反序列化；新增 `RedisServiceTest` 覆盖字符串、整数和对象读取。
- 已修复订单实体对 MySQL 保留字表名的映射，将 `Order` 的表名改为反引号转义的 `` `order` ``；同时完善全局异常日志堆栈输出。
- 已完成真实本地集成测试：文档、注册、登录、JWT 鉴权、地址、商品查询、Redis 预热、倒计时、动态路径、Lua 预扣、RabbitMQ 消费、订单落库和结果轮询均通过；重复下单返回 `60001 / SECKILL_REPEAT`。
- 集成验证结果：Redis/数据库库存由 2 正确变为 1，生成 1 条秒杀订单、1 条普通订单和 1 条订单明细，主队列与死信队列均无积压。

尚待补齐或验证：

- 持续流量压测（瞬时突发压测已完成，仍需单独验证稳定到达率）。
- 订单落库成功但 Redis 结果更新失败时的补偿任务。
- 当前秒杀 Controller、服务和 MQ 消费端已通过 IDEA 项目构建验证。

## 数据库

执行 `mall-server/src/main/resources/db/init.sql` 初始化全部 15 张表。数据库名为 `mall`，默认连接 `localhost:3306`，账号为 `root`，密码为 `123456`。

MyBatis-Plus 配置了逻辑删除字段 `deleted`（`0`=未删除，`1`=已删除），并启用了下划线到驼峰的自动转换。

## JMeter 压测约定

- JMeter 安装目录：`D:\tools\apache-jmeter-5.6.3`。
- 压测只使用独立的秒杀活动、商品和用户，不复用集成测试数据；Redis 固定使用 DB1。
- 压测前先准备高库存活动和带默认收货地址的测试用户，再执行库存预热；不要在每个线程中重复预热库存。
- 非 GUI 模式执行 JMeter，结果文件放在 `jmeter/` 下的本地输出目录，不提交用户密码、JTL 或 HTML 报告。
- 当前压测测试计划为 `jmeter/seckill-load-test.jmx`，用户生成脚本为 `jmeter/prepare-users.ps1`，数据脚本为 `jmeter/prepare-seckill-data.sql`。
- 已完成第一轮 100 线程压测：30 秒升压、60 秒测试窗口，402 个采样全部 HTTP 成功；100 个新用户实际生成 100 条秒杀订单，MySQL 与 Redis 库存均由 978 降至 878，RabbitMQ 主队列和死信队列均无积压。
- 本轮结果文件：`jmeter/results/formal-100t-20260731152048.jtl`，HTML 报告目录：`jmeter/report/formal-100t-20260731152048/`。
- 已新增纯秒杀执行压测计划 `jmeter/seckill-execute-qps.jmx`，提前准备 JWT/动态路径后，仅压测“鉴权 → Lua 扣库存 → RabbitMQ → 订单消费者”链路；`jmeter/prepare-token-paths.ps1` 支持 `Skip` 参数以便每轮使用全新用户。
- 纯执行突发压测结果：300、400 并发分别全部成功；450 并发多轮全部成功，最后一轮 450/450 业务成功，平均 219.8ms、P95 345ms、P99 388ms，释放窗口 214ms，约 2103 瞬时 QPS；RabbitMQ 主队列/死信队列均清空，数据库订单与库存扣减一致。
- 455、460、475 并发均出现部分 `HttpHostConnectException`（分别成功 403/455、420/460、450/475），未出现业务层 `60002`；当前本机一次性突发的稳定边界按 450 并发记录，约 450×请求释放窗口的瞬时 QPS，不等同于持续 QPS。
- 动态 path 有效期为 60 秒，压测必须在 token/path 生成后立即执行；过期 path 返回的 `60002` 不计入并发能力判断。

### 秒杀入口优化阶段（2026-07-31）

- 秒杀入口执行阶段不再查询 MySQL 商品数据；预热写入库存、SKU、价格和限购快照，用户 path 阶段写入默认地址快照。
- 秒杀 Redis key 使用 `mall:seckill:{itemId}:...` hash tag；Lua 原子完成 path、重复、限购、库存预占和 pending 状态写入。
- RabbitMQ 使用异步 publisher confirm；NACK、异常和超时通过 Lua 回滚库存/限购并标记失败，定时任务扫描长时间 pending 状态并修复订单结果或回滚。
- 消费者保持手动 ACK，默认并发 8、prefetch 100；消息携带 SKU、秒杀价格和地址快照，数据库连接池初始上限调整为 32。
- 新增 `/actuator/metrics` 指标、Lua/confirm/消费者耗时和补偿计数；本地 `loadtest` profile 将 path 有效期设为 15 分钟，生产默认 60 秒。
- `mvn test` 当前全量通过（76 个测试）；万级持续到达率和多实例压测仍需在 Redis/RabbitMQ/MySQL 实例环境中执行。
