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

本机 `mvn` 不在 PATH，IDE 终端同样不可用。可直接调用 IDEA 内置 Maven：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" test
```

该内置 Maven 为 3.9.16，运行时使用 `JAVA_HOME` 指向的 `D:\jdks\openjdk-22.0.2`。

## 本地测试环境启动

三个组件分属两套机制，重启机器后需分别启动：

| 组件 | 运行方式 | 启动命令 |
|------|---------|---------|
| MySQL 8.0 | **Windows 原生服务** `MySQL80` | `Start-Service MySQL80` |
| Redis 7 | **WSL Docker 容器** `redis`（`redis:7-alpine`） | `wsl -d Ubuntu -- docker start redis` |
| RabbitMQ 3 | **WSL Docker 容器** `rabbitmq`（`rabbitmq:3-management-alpine`） | `wsl -d Ubuntu -- docker start rabbitmq` |

要点：

- **MySQL 需要管理员权限**。非提权会话执行 `Start-Service MySQL80` 会报 `Cannot open 'MySQL80' service on computer '.'`，需用提权终端或 UAC 触发。
- **两个容器的重启策略都是 `no`**，不会随 WSL 自动拉起，每次都要手动 `docker start`。
- **WSL 会在最后一条 `wsl.exe` 命令结束约 60 秒后关闭整个 VM**，容器随之收到 SIGTERM。测试期间必须保持一个 WSL 会话存活（后台窗口或长驻进程），否则环境会中途掉。已复现多次；现象是容器 `Exited (0)`、日志出现 `Received SIGTERM`。
- 端口从 Windows 侧用 **`localhost`** 直连即可（WSL2 转发可用）：3306 / 6379 / 5672 / 15672。**不要依赖 WSL IP**，每次重启都会变（见过 `172.25.212.154` 和 `172.25.208.1`）。
- WSL 侧只有 Redis + RabbitMQ，实测占用约 190 MB；资源上限配置在 `C:\Users\<user>\.wslconfig`（`memory=4GB`、`swap=2GB`、`autoMemoryReclaim=gradual`）。注意 `autoMemoryReclaim` 必须放在 **`[experimental]`** 段，写在 `[wsl2]` 下会被拒绝并提示「未知键」。

应用启动：

```bash
java -jar mall-server/target/mall-server-1.0.0.jar --server.port=8081 --spring.profiles.active=loadtest
```

`loadtest` profile 仅覆盖 `mall.seckill.path-ttl-seconds=900`，其余继承主配置。启动约需 6 秒，日志出现 `Started MallApplication` 即为就绪。

### 验证用测试数据（2026-09-16 建立）

数据库重建后写入的最小数据集，供优惠券/支付集成验证使用：

| 对象 | ID | 说明 |
|------|----|------|
| 用户 A | `2100109502413639680` | 用户名 `coupontest_a` |
| 用户 B | `2100109503449632768` | 用户名 `coupontest_b` |
| 商品 SKU | `930000000000000001` / `930000000000000002` | 199.99 / 299.99 |
| 券 | `940000000000000001`~`...004` | 满减20 / 8折 / 并发券(total=2) / 过期券(expire_day=1) |

按本文件既有约定，**测试账号密码不写入项目文档**；本地口令请自行记录。

注意：应用接收 JSON 时中文必须是 **UTF-8**。从 Git Bash 直接用 `curl -d '{"receiver":"中文"}'` 会以 GBK 发出，服务端报 `Invalid UTF-8 start byte`；应改写为 UTF-8 文件后用 `--data-binary @file`。另注意 `AddressDTO.isDefault` 等字段为 `Integer`，传 `1` 而非 `true`。

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
            └── db/init.sql     # 全量建表脚本（19 张表）
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

使用 `KeyPrefix` 接口体系，前缀格式为 `mall:<domain>:<purpose>:`。秒杀热路径的 key 必须让同一 `itemId` 落在同一个 Redis Cluster hash slot：

- `mall:seckill:{itemId}:stock`：预热库存。
- `mall:seckill:{itemId}:snapshot`：预热写入的 SKU、秒杀价和限购快照。
- `mall:seckill:{itemId}:path:{userId}`：用户动态 path。
- `mall:seckill:{itemId}:request:{userId}`：path 阶段写入的请求快照（包含默认地址）。
- `mall:seckill:{itemId}:result:{userId}`：秒杀结果（`0`=排队，`1`=成功，`-1`=失败）。
- `mall:seckill:{itemId}:limit:{userId}`：本用户已预占数量。
- `mall:seckill:{itemId}:pending:{messageId}` 与 `pending:index`：消息待确认/处理中状态及索引。
- `mall:coupon:{couponId}:stock`：优惠券领取库存计数，使用 couponId hash tag。

已有 KeyPrefix 实现：`Userkey`、`GoodsKey`、`MiaoshaKey`、`MiaoShaUserKey`、`OrderKey`，位于 `mall-infra/redis/`；秒杀完整 key 由 `SeckillKey` 生成，旧前缀仅为源码兼容保留。

### RabbitMQ 秒杀消息流

- 交换机：`mall.seckill.direct`（Direct 类型）。
- 主队列：`mall.seckill.order`，routing key 为 `order.create`。
- 死信队列：`mall.seckill.order.dlq`，routing key 为 `order.create.dlx`。
- 生产者启用 correlated publisher confirm 和 mandatory returns；只有 broker confirm 成功才向客户端返回 `WAITING`。
- 消费端需手动确认：`acknowledge-mode: manual`，默认 `concurrency=8`、`prefetch=100`；失败消息进入死信队列并由补偿逻辑回滚预占。

### 统一响应格式

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1706000101000 }
```

`Result<T>` 提供 `Result.build()` 系列静态工厂方法，失败使用 `Result.fail(ResultStatus)`。`BusinessException` 由 `GlobalExceptionHandler` 全局兜底捕获。

## 实现阶段规划

基于 `db/init.sql` 的 19 张表，按业务域拆分为 9 个阶段：

| 阶段 | 业务域 | 涉及表 | 状态 |
|------|--------|--------|------|
| 阶段一 | 基础设施 | common/security/infra 模块 | 已完成 |
| 阶段二 | 用户模块 | user、address | 已完成 |
| 阶段三 | 商品模块 | category、product、product_sku、review | 已完成 |
| 阶段四 | 购物车 | cart_item | 已完成 |
| 阶段五 | 订单模块 | order、order_item、refund | 已完成 |
| 阶段六 | 秒杀模块 | seckill_activity、seckill_item、seckill_order | 核心实现和集成测试已完成，持续压测待验证 |
| 阶段七 | 优惠券 | coupon、user_coupon | 核心实现和单元测试已完成，真实集成验证待执行 |
| 阶段八 | 支付物流 | payment_record、logistics | 核心实现和单元测试已完成，真实集成验证待执行 |
| 阶段九 | 商家后台 | merchant、admin_user | 待实现 |

## 当前进度

### 阶段一：基础设施（已完成）

- `mall-common`：`Result<T>`、`BusinessException`、`ResultStatus` 枚举、雪花 ID。
- `mall-security`：`JwtUtil`、`JwtAuthFilter`、`UserContext`、Spring Security 无状态配置。
- `mall-infra`：`RedisService`、KeyPrefix 体系、RabbitMQ 秒杀队列声明。`RedisLock` 为有意保留的通用分布式锁，当前**无任何生产或测试代码引用**（秒杀路径改用 Lua 保证原子性后不再需要），Spring 会实例化该 bean 但无人注入。

### 阶段二：用户模块（已完成）

- User：注册、登录、刷新 Token，BCrypt 密码加密，JWT 双 Token。
- Address：CRUD、默认地址管理。
- 8 个单元测试（`UserServiceImplTest`）。此前文档声称的「25 个测试含 `AddressServiceImpl`」属于误记 —— `AddressServiceImpl` 当时**没有任何测试**，直至 2026-09-16 补齐 `AddressServiceImplTest`（6 个用例）。
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
- 19 个单元测试（`OrderServiceImplTest`），全部通过。

### 阶段六：秒杀模块（核心实现和集成验证已完成，持续压测待验证）

当前工作区已完成或开始实现以下基础部分：

- 秒杀活动、秒杀商品、秒杀订单的 PO 和 Mapper。
- 秒杀倒计时、秒杀结果 VO。
- `SeckillService` 及其实现类的基础结构。
- 秒杀 Redis Key、库存预热、动态路径和 Lua 原子扣库存接入。
- 重复请求校验、排队结果写入和 RabbitMQ 秒杀消息发送。
- `SeckillConsumer` 已实现主队列事务落库、手动 ACK、失败转死信和死信库存回滚。
- 秒杀消息使用 JSON 转换器，消费者包含数据库乐观锁扣库存和重复消息幂等处理。
- `SeckillController` 已提供预热、路径、倒计时、执行秒杀和结果轮询 5 个接口。
- 已补充 `SeckillServiceImplTest` 12 个服务层单元测试、`SeckillControllerTest` 5 个 Controller 单元测试和 `SeckillMessagePublisherTest` 2 个 publisher confirm 单元测试，均通过。
- 已修复 `mall-common/pom.xml` 中重复且版本不一致的 `mybatis-plus-annotation` 依赖，统一到 `${mybatis-plus.version}`（3.5.10），解决 `FieldStrategy.IGNORED` 启动异常。
- `MallApplication` 已在本地成功启动，HTTP 服务监听 8080 端口；临时凭据不写入项目文档。
- 已修复 `RedisService.get()` 对 Redis 字符串、数字等标量值的反序列化问题，并保留 JSON 对象反序列化；新增 `RedisServiceTest` 覆盖字符串、整数和对象读取。
- 已修复订单实体对 MySQL 保留字表名的映射，将 `Order` 的表名改为反引号转义的 `` `order` ``；同时完善全局异常日志堆栈输出。
- 已完成真实本地集成测试：文档、注册、登录、JWT 鉴权、地址、商品查询、Redis 预热、倒计时、动态路径、Lua 预扣、RabbitMQ 消费、订单落库和结果轮询均通过；重复下单返回 `60001 / SECKILL_REPEAT`。
- 集成验证结果：Redis/数据库库存由 2 正确变为 1，生成 1 条秒杀订单、1 条普通订单和 1 条订单明细，主队列与死信队列均无积压。

尚待补齐或验证：

- 订单落库成功但 Redis 结果更新失败的故障注入验证；对应的定时补偿任务已实现。
- 持续流量压测（已完成 16.76 req/s 恒定到达基线，目标高流量和多实例持续压测仍待验证）。
- 多 API 实例、Redis/RabbitMQ 集群和独立订单消费者的生产式压测。
- 2026-08-11 曾恢复测试环境并完成真实压测；应用使用 `loadtest` profile 监听 `8081`，独立压测商品为 `994000000000000006`。该轮结束后测试进程已停止，当前不能假定 Redis、RabbitMQ AMQP 或应用仍在运行。

## 测试覆盖基线（2026-09-16 核实）

**权威数字**：`mvn test` 共 **154 个测试，0 失败 / 0 错误 / 0 跳过**，24 个测试类。

| 模块 | 测试数 | 测试类 |
|------|--------|--------|
| mall-common | 5 | `SnowflakeIdUtilTest`(5) |
| mall-security | 12 | `JwtAuthFilterTest`(4)、`JwtUtilTest`(8) |
| mall-infra | 6 | `CouponStockRedisServiceTest`(2)、`RedisServiceTest`(4) |
| mall-server | 131 | 19 个测试类 |

执行方式（本机 `mvn` 不在 PATH）：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" test
```

### 已知覆盖缺口

下表是**类级覆盖**（是否存在对应测试类），不是行覆盖率。真实行覆盖率需 JaCoCo，当前 pom 未配置。

| 层 | 有测试 / 总数 | 覆盖率 |
|----|--------------|--------|
| ServiceImpl | 12 / 12 | 100% |
| Controller | 5 / 13 | 38% |
| Util | 2 / 2 | 100% |

零覆盖的高风险类：

- `SeckillCompensationTask`（110 行）：pending 状态补偿与库存回滚。已读代码，逻辑正确（PROCESSING 的 `processingAt` 由 `seckill_claim.lua` 保证非空，此前怀疑的「永久跳过」不成立）。待办三项：
  1. **补测试**，重点锁住 `repairOne` 中「**先查 `seckill_order` 再决定回滚**」的顺序 —— 这是防止「消费者卡顿超时被回滚、随后又提交订单」产生数据不一致的关键。
  2. **修硬编码 TTL**：`SeckillCompensationTask:85` 与 `SeckillRedisStateService:115` 都写死 `3600`，绕过了 `mall.seckill.result-ttl-seconds`。消费端 `SeckillConsumer:110` 读的是配置，两条路径会静默分叉。
  3. **`rollback` 返回 `-1`（库存 key 缺失）被静默吞掉**，无日志无 metric，该故障模式在监控上不可见。
- `SeckillRedisStateService`、`UserContext`。

### 多实例部署的 ID 冲突风险（待处理）

`SnowflakeIdUtil` 委托 Hutool `IdUtil.getSnowflake()`，其 workerId / datacenterId 由**网络地址与 PID 推导**（`IdUtil.getDataCenterId` 取 MAC，`IdUtil.getWorkerId` 取 `hash(datacenterId + PID)`），两侧各只有 **5 位 = 32 个槽位**。

同主机多实例时 PID 不同，通常能分到不同 workerId，但 32 个槽位的哈希撞车概率随实例数快速上升：

| 同主机实例数 | workerId 撞车概率 |
|--------------|------------------|
| 4 | 17.7% |
| 6 | 39.2% |
| 8 | 61.4% |
| 16 | 99.0% |

一旦两个实例共用 `(datacenterId, workerId)`，在**同一毫秒内生成 ID** 就会产生重复 —— 而本项目的万级 QPS 目标下，同毫秒生成是必然的。雪花 ID 的 sequence 每毫秒从 0 重新计数，因此两个实例在同一毫秒的首个 ID 会完全相同。

多实例压测前需改为**显式配置 workerId / datacenterId**（例如从环境变量或配置项读取），而不是依赖推导。当前单实例运行不受影响。

### 已修复的缺陷（2026-09-16）

`AddressServiceImpl` 此前零覆盖，修复两个缺陷并补齐 `AddressServiceImplTest`（6 个用例）：

1. **`isDefault` 永远无法设置**：DTO/VO 为 `Integer`，PO 为 `Boolean`，`BeanUtils.copyProperties` 因类型不匹配静默跳过该字段。已将 PO 改为 `Integer`（同时对齐数据库 `TINYINT`），并在 PO 字段上注明原因。
2. **越权/不存在时静默成功**：`updateAddress` 原本返回 `null`，Controller 直接 `result.success(null)`，导致修改他人地址返回 `code 0` 成功。现改为抛 `BusinessException(ADDRESS_NOT_EXIST)`（新增码值 `20004`）。

`SeckillConsumer`（289 行）已由 `SeckillConsumerTest` 覆盖，17 个用例聚焦 ACK/NACK 语义、幂等分支与快照字段兼容回退。

无测试的 Controller：`CartController`、`OrderController`、`AddressController`、`AuthController`、`ProductController`、`UserCouponController`、`ReviewController`、`SkuController`。

### 文档维护约定

按阶段记录测试数量会持续漂移，历史上已产生「25 个单元测试（含 `AddressServiceImpl`）」「全部 14 个测试类」等失实条目。**新增或修改测试后只更新本节的权威数字**，不要在各阶段条目里散落具体数量。

## 数据库

执行 `mall-server/src/main/resources/db/init.sql` 初始化全部 **19 张表**。数据库名为 `mall`，默认连接 `localhost:3306`，账号为 `root`，密码为 `123456`。

> 若数据库早于 `init.sql` 的索引变更建立，`user_coupon`、`payment_record`、`logistics` 上的唯一索引会缺失，导致 `CouponServiceImpl.receiveCoupon` 中依赖 `DataIntegrityViolationException` 的并发幂等分支失效。核对方式见「测试覆盖基线」同级的索引检查说明；修复时重建库或手工 `ALTER TABLE` 补齐。

MyBatis-Plus 配置了逻辑删除字段 `deleted`（`0`=未删除，`1`=已删除），并启用了下划线到驼峰的自动转换。

## JMeter 压测约定

- JMeter 安装目录：`D:\tools\apache-jmeter-5.6.3`。
- 压测只使用独立的秒杀活动、商品和用户，不复用集成测试数据；Redis 固定使用 DB1。
- 压测前先准备高库存活动和带默认收货地址的测试用户，再执行库存预热；不要在每个线程中重复预热库存。
- 非 GUI 模式执行 JMeter，结果文件放在 `jmeter/runs/` 下的本地输出目录，不提交用户密码、JTL 或 HTML 报告。
- **`jmeter/runs/` 内含真实 JWT 和明文密码**（`token-paths.csv`、`*-users.csv`），已在 `.gitignore` 中整体排除；`jmeter/report/`、`jmeter/results/`、顶层 `jmeter/*.jtl` 同样忽略。禁止用 `git add -f` 强制加入。
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
- 提交 `ba495db` 已落地本阶段代码和测试；MCP IntelliJ JUnit 已运行当时的全部测试类，均以 `exitCode=0` 通过。（当时为 14 个测试类，现已增至 22 个，见「测试覆盖基线」。）
- 本轮 IDE 终端没有可用的 `mvn` 命令，因此没有重复执行 Maven 全量命令；未启动 MySQL、Redis 或 RabbitMQ。
- 万级持续到达率、多实例压测及故障注入验证仍待在已恢复的测试环境基础上继续执行；多实例阶段还需准备对应的部署资源。

### 2026-08-11 真实突发压测记录

- 测试环境：MySQL、Redis、RabbitMQ 已启动；应用使用 `loadtest` profile 运行在 `http://localhost:8081`。
- 测试数据：独立 `itemId=994000000000000006`，每轮预热库存为 1,000,000，未复用集成测试商品和用户。

| 档位 | 执行请求 | 连接失败 | 成功请求平均耗时 | 成功请求最大耗时 |
|------|----------|----------|------------------|------------------|
| 100 线程，30 秒升压 | 100/100 | 0 | 60.2 ms | 96 ms |
| 200 线程，30 秒升压 | 200/200 | 0 | 102.4 ms | 169 ms |
| 400 线程，30 秒升压 | 380/400 | 20，全部为 `HttpHostConnectException` | 152.0 ms | 220 ms |

- 三轮均在各自预热后执行，成功请求/订单分别为 100、200、380；最终检查时数据库库存和 Redis 库存均为 999,320，`seckill_order` 累计 680 条且 `distinct user` 为 680；RabbitMQ 主队列、死信队列和 Redis pending 均为 0。
- 本轮是线程对齐的一次性突发压测，不能据此认定持续 QPS；400 线程的 20 个失败属于连接建立失败，不是秒杀业务错误。下一步使用新的恒定吞吐压测计划验证稳定到达率。

### 2026-08-14 当前工作状态：恒定吞吐单机基线已完成

- 已提交代码、前一轮进度文档和恒定吞吐验证计划：`ba495db feat: optimize seckill entry throughput`、`03ec8b7 docs: update seckill progress and load test notes`、`a7777c5 test: add sustained seckill throughput validation`。
- 新增并验证 `jmeter/seckill-sustained-qps.jmx`，该计划只压测合法的执行请求，并保留一次性库存预热线程组。
- JMeter 5.6.3 在当前 JDK 22 环境下执行 JSR223/Groovy 时出现 `Unsupported class file major version 66`，因此计划已移除对该脚本路径的依赖。
- `ConstantThroughputTimer` 在该计划中未形成共享限速（`calcMode=4`、`3` 的 A/B 运行均在约 1 秒内完成）；现已改用 JMeter 内置 `PreciseThroughputTimer`，并通过 `threads=0` 非 GUI 解析验证。
- 之前对 `item995`、`item996` 的运行约 1 秒内完成约 1,000 个请求，计时器未生效，不能记录为持续 QPS；这两个商品已经产生订单，后续不得复用。`item997000000000000006` 仅用于计时器校准，也不作为正式性能结果。
- 正式单机基线使用全新商品 `itemId=998000000000000006`、1,000 个全新用户和 1,000,000 库存；1,000 个执行请求在 59,671 ms 的首尾窗口内完成，实际到达率 16.76 req/s（目标 1,000 samples/min），HTTP 错误 0，状态码全部为 200，平均耗时 10.89 ms，P95 18 ms，P99 23 ms，最大 36 ms。
- 正式基线完成后的数据一致性：`seckill_item.stock=999000`，Redis DB1 的 `mall:seckill:{998000000000000006}:stock=999000`，`seckill_order` 新增 1,000 条且 `distinct user_id=1000`；RabbitMQ 主队列、死信队列、Redis pending 均为 0，publisher failed/nack 指标为 0。
- 随后使用全新 `itemId=999000000000000006` 做短时高率探针：1,000 个合法请求在 1,050 ms 内完成，实际约 952.38 req/s，HTTP 错误 0，平均耗时 56.15 ms，P95 210 ms，P99 244 ms，最大 255 ms；DB/Redis 库存均为 999000，订单 1,000 条且用户去重，主/死信队列和 pending 均为 0。
- 单机 2k 目标探针使用 `itemId=100000000000000006`：1,000 个请求实际在 965 ms 内到达，约 1,036.27 req/s；995 个 HTTP 200，5 个 `HttpHostConnectException`，成功请求平均 220.66 ms，P95 445 ms，P99 528 ms，DB/Redis 均扣减 995，MQ/pending 均清空。
- 单机 5k 目标探针使用 `itemId=100200000000000006`：1,000 个请求实际在 792 ms 内到达，约 1,262.63 req/s；776 个 HTTP 200，224 个 `HttpHostConnectException`，成功请求平均 158.14 ms，P95 364 ms，P99 381 ms，DB/Redis 均扣减 776，MQ/pending 均清空。
- 本轮资源采样中 Windows 总内存约 15.21 GB，最低可用约 3.02 GB（约 80.1% 已用），应用 JVM 工作集约 0.38 GB，WSL 可用内存约 6.6 GB；本轮未观察到内存耗尽，但更高流量和多实例测试仍需保留约 3 GB 以上主机余量。
- 本轮测试通过 WSL 地址 `172.25.212.154` 连接 Redis `6379` 和 RabbitMQ AMQP `5672`，RabbitMQ 管理端 `15672` 与应用 `8081` 可用；WSL Ubuntu 正在运行，但 Windows `docker` 命令不可用。若下次环境已停止，恢复前需重新确认服务端口，并以 `--server.port=8081 --spring.profiles.active=loadtest` 启动应用，同时临时覆盖 Redis/RabbitMQ host。
- 单机阶段结论：16.7 req/s 可稳定持续 60 秒；约 952 req/s 的短时探针全部成功；将目标提高到 2k/5k 后，JMeter 实际到达率受单机连接接入能力限制在约 1,036/1,263 req/s，并出现连接拒绝。业务成功请求始终保持库存、订单、Redis 和 MQ 一致，未发现超卖、重复订单或消息丢失。单机验证到此结束，10k req/s 的 60 秒云平台/多实例验证留到项目功能完成后。

### 阶段七：优惠券模块（核心实现和单元测试已完成，真实集成验证待执行）

- 新增 `Coupon`、`UserCoupon` PO 和对应 MyBatis-Plus Mapper。
- 新增优惠券模板查询 `GET /api/coupons`、领取 `POST /api/coupons/{couponId}/receive`，以及用户优惠券列表 `GET /api/user/coupons?status=UNUSED|USED|EXPIRED`。
- 领取路径使用 `mall:coupon:{couponId}:stock` Redis 计数器和 `SETNX + DECR` 原子预占；数据库插入失败会回滚 Redis 库存，`user_coupon` 增加 `(user_id, coupon_id)` 唯一索引，重复领取返回已存在记录保持幂等。
- 支持 `FULL_REDUCTION` 满减和 `DISCOUNT` 折扣（折扣比例为 `0~1`），校验最低消费金额并返回订单优惠前金额、优惠金额和实付金额。
- `POST /api/orders` 传入已有 `couponId` 时会在订单事务内校验并将用户券条件更新为 `USED`，订单保存优惠后的 `total_amount` 和 `coupon_id`；取消 `PENDING` 订单会恢复未过期优惠券，过期券在查询、使用和定时任务中转为 `EXPIRED`。
- 新增 `CouponServiceImplTest` 10 个、`CouponControllerTest` 3 个和 `CouponStockRedisServiceTest` 2 个测试用例；截至本轮，当时的 16 个测试类均由 IntelliJ MCP 运行并以 `exitCode=0` 通过，IDE 项目构建成功。（测试类总数现为 21，见「测试覆盖基线」。）
- 本阶段尚未启动 MySQL、Redis、RabbitMQ 做真实优惠券领取/下单集成验证；需要环境恢复后验证 Redis 库存、唯一索引、订单金额和取消回滚的一致性。

### 阶段八：支付物流模块（核心实现和单元测试已完成，真实集成验证待执行）

- 新增 `PaymentRecord` PO、Mapper、Service 和 VO；模拟支付接口为 `POST /api/orders/{orderId}/pay`，查询接口为 `GET /api/orders/{orderId}/payment`。
- 支付只允许当前用户操作自己的 `PENDING` 订单；事务先锁定订单行，再创建或更新 `payment_record`，成功后将订单推进到 `PAID`。
- 重复支付对已有 `SUCCESS` 记录幂等返回，并在订单仍为 `PENDING` 时修复订单状态；订单归属和非法状态分别返回订单/支付状态错误。
- 新增 `Logistics` PO、Mapper、Service、DTO 和 VO；用户查询接口为 `GET /api/orders/{orderId}/logistics`。
- 物流服务层已实现 `PAID → SHIPPED → DELIVERED` 的发货和送达流转、订单归属校验及重复发货/送达保护；由于商家后台尚未完成，发货和送达方法暂不暴露为用户 HTTP 接口，阶段九接入商家权限后再开放。
- 为支付记录和物流记录增加 `order_id` 唯一索引，避免一单多条支付/物流记录；订单状态新增兼容性的 `DELIVERED`，原有 `SHIPPED → RECEIVED` 确认收货接口仍保留，同时支持 `DELIVERED → RECEIVED`。
- `ResultStatus` 新增 80000 段支付/物流错误码；新增 `PaymentServiceImplTest` 6 个、`PaymentControllerTest` 2 个、`LogisticsServiceImplTest` 6 个和 `LogisticsControllerTest` 1 个测试用例，均已由 IntelliJ MCP 运行并以 `exitCode=0` 通过，IDE 增量构建成功。
- 尚未使用恢复的 MySQL 做真实支付、发货、物流查询和订单状态联动验证；环境恢复后需验证唯一索引、事务回滚、重复请求、越权访问和订单/支付/物流最终一致性。
