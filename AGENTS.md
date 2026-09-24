# AGENTS.md

本文件是本项目面向 Codex、Claude Code 及其他代码代理的协作指南。除非用户另有明确要求，修改代码时应遵守本文档中的架构、命名、测试和验证约定。

## 适用范围

- 根目录的本文件适用于整个项目。
- 开始修改前，先阅读相关模块的现有实现、测试和配置。
- 只修改完成当前任务所需的文件，保留用户已有的未提交改动。
- 完成代码修改后，运行与改动范围匹配的测试或构建命令，并报告验证结果。


> **历史部分已拆出**（2026-09-22）：各阶段进度叙事、压测流水、已修复缺陷编年移至
> [`docs/progress-and-loadtest-log.md`](docs/progress-and-loadtest-log.md)。
> 拆出原因：Codex 对项目指令文件有 **32 KiB 上限且静默截断**，本文件此前 51,908 字节已超出，
> 被丢弃的部分包括本文件的「待办」与「相关项目」两节。
>
> ⚠️ **改本文件时盯着字节数**——超过 32,768 字节的部分不会报错，只会被静默丢掉。
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
# 三个环境变量都是必需的：缺失任何一个都启动失败
export MERCHANT_JWT_SECRET='<至少 32 字节的随机串>'
export MALL_WORKER_ID=1          # 0~31，每个实例必须互不相同
export MALL_DATACENTER_ID=1      # 0~31
java -jar mall-server/target/mall-server-1.0.0.jar --server.port=8081 --spring.profiles.active=loadtest
```

三者都**刻意不设默认值**，缺失时启动阶段即抛 `Could not resolve placeholder '...'` 并退出：

- `MERCHANT_JWT_SECRET`（阶段九 9.1 起）——商家端与 C 端密钥不同，隔离才在签名层面成立。
- `MALL_WORKER_ID` / `MALL_DATACENTER_ID`（2026-09-18 起）——雪花 ID 的实例身份。此前由 Hutool 按 MAC + PID 推导，**单实例下没问题，只有多实例高 QPS 时才会静默发出重复 ID**，那种故障极难回溯，所以宁可起不来。多实例部署时每个实例给一对不同的值。

`loadtest` profile 仅覆盖 `mall.seckill.path-ttl-seconds=900`，其余继承主配置。启动约需 6 秒，日志出现 `Started MallApplication` 即为就绪。

### 测试数据清理快照（2026-09-18）

这是 2026-09-18 为准备阶段九做的清理快照，**不是对当前数据库状态的声明**。当日 `init.sql` 只建表、不含任何 INSERT，因此库里业务数据原本都是测试期间手工造的，不存在需要保护的真实数据。

清理方式：先用 `mysqldump` 把待删表整体备份到仓库外的系统临时目录（`mall-business-backup-<时间戳>.sql`，25 KB），再执行删除。

| 保留 | 内容 |
|------|------|
| `category` | 1 条 |
| `product` | 1 条（`920000000000000001` 测试商品，有 `merchant_id`，阶段九会用到） |
| `product_sku` | 3 条（`930000000000000001` 199.99 / `...002` 299.99 / `...003` 60.00 低价夹具） |
| `merchant` | 1 条（`900000000000000001` 测试商家） |
| `admin_user` | 空表 |

| 已清空 | 说明 |
|--------|------|
| `user` / `address` | 33 个用户（`coupontest_*`、`itest_*`、`race_*`、`concu_*`、`verifyonly_*`）及其地址 |
| `coupon` / `user_coupon` | 含 2026-09-16 的 4 张模板券和 2026-09-18 的 1 张扩容券 |
| `order` / `order_item` / `payment_record` / `logistics` | 阶段七/八集成验证产生的订单链路 |
| `seckill_activity` / `seckill_item` / `seckill_order` | 含 2026-09-18 故障注入用的活动与商品 |
| Redis DB1 | `flushdb` 清掉 7,619 个 key（几乎全是历次压测的 `mall:seckill:*:limit:*`）；DB0 未动 |

> **重跑阶段七/八验证需要重新造数**：文档里引用过的 `coupontest_a/b`、券 `940...001`~`005`、秒杀活动 `9600...001` 与商品 `9700...001` **均已不存在**。如需复现，按各自章节描述的步骤重建即可。

> `getPath` 要求用户**必须有收货地址**，否则抛 `SECKILL_FAIL(60002)`；给测试用户建号后记得补地址。

按本文件既有约定，**测试账号密码不写入项目文档**；本地口令请自行记录。

注意：应用接收 JSON 时中文必须是 **UTF-8**。从 Git Bash 直接用 `curl -d '{"receiver":"中文"}'` 会以 GBK 发出，服务端报 `Invalid UTF-8 start byte`；应改写为 UTF-8 文件后用 `--data-binary @file`。另注意 `AddressDTO.isDefault` 等字段为 `Integer`，传 `1` 而非 `true`。

## Maven 模块结构

```text
supermall/
├── pom.xml                  # 父 POM：Spring Boot 3.4.4 parent + 版本/模块管理
├── mall-common/             # 公共模块：Result<T>、BusinessException、ResultStatus、雪花 ID
├── mall-security/           # JWT、三条 Spring Security 无状态过滤器链、UserContext
│                            #   C 端默认链；商家 @Order(1)；管理端 @Order(2)
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

### 三条安全过滤器链

- C 端 `SecurityConfig` 没有 `@Order`，使用默认的最低优先级，处理未被更具体链匹配的路径。
- 商家端 `MerchantSecurityConfig` 是 `@Order(1)`，以 `securityMatcher("/api/merchant/**")` 先匹配；管理端 `AdminSecurityConfig` 是 `@Order(2)`，匹配 `/api/admin/**`。Spring Security 只使用第一条匹配链，因此这两类路径不经过 C 端过滤器。
- 商家端和管理端共用 `MerchantJwtUtil` 的签名密钥；C 端 `JwtUtil` 使用另一把密钥。共享签名只覆盖两个内部后台，权限仍由各自角色控制。

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

已有 KeyPrefix 实现：`Userkey`、`GoodsKey`、`MiaoshaKey`、`MiaoShaUserKey`、`OrderKey`、`CouponKey`，位于 `mall-infra/redis/`；`CouponStockRedisService` 通过 `CouponKey.stockKey(couponId)` 初始化、扣减和回滚优惠券库存。秒杀完整 key 由 `SeckillKey` 生成，旧前缀仅为源码兼容保留。

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
| 阶段七 | 优惠券 | coupon、user_coupon | 已完成，真实集成验证 2026-09-18 通过 |
| 阶段八 | 支付物流 | payment_record、logistics | 已完成，真实集成验证 2026-09-18 通过（发货/送达无 HTTP 出口，待阶段九） |
| 阶段九 | 商家后台 | merchant、admin_user | 已完成（9.1 商家端 + 9.2 管理后台，均通过真实环境验证） |

## 测试报告基线（2026-09-23）

现有 Surefire 报告汇总为 **228 个测试，38 个测试类，0 失败 / 0 错误 / 0 跳过**。这是 2026-09-23 的报告快照，**不是本次会话重新运行 `mvn test` 的结果**。

| 模块 | 测试数 | 测试类 |
|------|--------|--------|
| mall-common | 10 | 1 |
| mall-security | 16 | 3 |
| mall-infra | 6 | 2 |
| mall-server | 196 | 32 |

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

零覆盖的高风险类：仅剩 `UserContext`。

`SeckillCompensationTask`（110 行）与 `SeckillRedisStateService` 已于 2026-09-18 补齐测试并修掉三个问题：

- `SeckillCompensationTaskTest`（5 个用例）锁住 `repairOne` 的顺序不变量：**`seckill_order` 必须先于回滚决策被查询**。最关键的用例把 pending 置为 `PROCESSING` 且 `processingAt` 已远超 `processing-timeout-ms`——即「看起来可以回滚」——同时存在已提交订单，断言此时必须 `finalizeSuccess` 而非 `rollback`。这正是防止「消费者卡顿超时被回滚、随后又提交订单」导致超卖的关键（此前只是读过代码认为正确，现在是锁住的）。
- 同文件另锁住：无订单时才回滚、`PROCESSING` 未超时不动作、`finalizeSuccess` 用的是注入的 TTL 而非字面量、`rollback == -1` 必须记 `rollback_stock_missing` 指标。
- `SeckillRedisStateServiceTest`（2 个用例）用打桩捕获 Lua 的 ARGV，锁住 TTL 确实被转发进脚本。

无测试的 Controller：`CartController`、`OrderController`、`AddressController`、`AuthController`、`ProductController`、`UserCouponController`、`ReviewController`、`SkuController`。

### 文档维护约定

按阶段记录测试数量会持续漂移，历史上已产生「25 个单元测试（含 `AddressServiceImpl`）」「全部 14 个测试类」等失实条目。**新增或修改测试后只更新本节的权威数字**，不要在各阶段条目里散落具体数量。

## 数据库

执行 `mall-server/src/main/resources/db/init.sql` 初始化全部 **19 张表**。`application.yml` 含本地 datasource、Redis 与 RabbitMQ 连接属性；不要在项目文档写入凭据。运行时用 Spring 环境变量覆盖本地值，例如 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_RABBITMQ_HOST`、`SPRING_RABBITMQ_PORT`、`SPRING_RABBITMQ_USERNAME`、`SPRING_RABBITMQ_PASSWORD`。这不替代本项目必须设置的 `MERCHANT_JWT_SECRET`、`MALL_WORKER_ID`、`MALL_DATACENTER_ID`。

> 若数据库早于 `init.sql` 的索引变更建立，`user_coupon`、`payment_record`、`logistics`、`refund` 上的唯一索引会缺失。`CouponServiceImpl.receiveCoupon` 中依赖 `DataIntegrityViolationException` 的并发幂等分支会失效；`refund` 的 `uk_refund_order` 缺失则**退款幂等静默失效**——Agent 的一次重试就是一笔重复退款，而所有测试都 mock 了 `RefundMapper`，CI 永远发现不了。核对：
> `SELECT table_name, index_name, non_unique FROM information_schema.statistics WHERE table_schema='mall' AND table_name IN ('user_coupon','payment_record','logistics','refund');`
> `non_unique` 必须为 0。`refund` 另需确认是 `(order_id)` 上的**单列**唯一索引——复合索引 `(order_id, status)` 挡不住一单多退。修复时重建库或手工 `ALTER TABLE` 补齐。

MyBatis-Plus 配置了逻辑删除字段 `deleted`（`0`=未删除，`1`=已删除），并启用了下划线到驼峰的自动转换。

## JMeter 压测约定

- JMeter 安装目录：`D:\tools\apache-jmeter-5.6.3`。
- 压测只使用独立的秒杀活动、商品和用户，不复用集成测试数据；Redis 固定使用 DB1。
- 压测前先准备高库存活动和带默认收货地址的测试用户，再执行库存预热；不要在每个线程中重复预热库存。
- 非 GUI 模式执行 JMeter，结果文件放在 `jmeter/runs/` 下的本地输出目录，不提交用户密码、JTL 或 HTML 报告。
- **`jmeter/runs/` 内含真实 JWT 和明文密码**（`token-paths.csv`、`*-users.csv`），已在 `.gitignore` 中整体排除；`jmeter/report/`、`jmeter/results/`、顶层 `jmeter/*.jtl` 同样忽略。禁止用 `git add -f` 强制加入。
- 当前脚本：测试计划 `jmeter/seckill-load-test.jmx`、`jmeter/seckill-execute-qps.jmx`、`jmeter/seckill-sustained-qps.jmx`；用户生成 `jmeter/prepare-users.ps1`；数据 `jmeter/prepare-seckill-data.sql`；动态 path 令牌 `jmeter/prepare-token-paths.ps1`；管理员令牌 `jmeter/prepare-admin-token.ps1`。
- **跑秒杀压测前必须先执行 `jmeter/prepare-admin-token.ps1`**（登录 `/api/admin/login` 并校验 `SUPER_ADMIN` 角色），否则预热步骤 403——9.2 已把预热移到管理员端点。
- **`-BaseUrl` 默认是 `http://localhost:8080`**：应用按 `loadtest` profile 跑在 8081 时需显式传 `-BaseUrl http://localhost:8081`，否则连接被拒。
- **动态 path 有效期 60 秒**：压测必须在 token/path 生成后立即执行；过期 path 返回的 `60002` 不计入并发能力判断。
- 历史压测结果与各阶段验证记录见 [`docs/progress-and-loadtest-log.md`](docs/progress-and-loadtest-log.md)。

---

## 待办（2026-09-18 记录）

### 1. 本店商品列表（`GET /api/merchant/products`）

阶段九 9.1 按 `docs/implementation-plan.md` 未做。商家目前只能靠上架时返回的 ID 记住自己的商品，**无法找回在售商品的全貌**。成本很低，建议下一轮补齐。

### 2. 把本地启动流程做成项目 skill

**为什么值得做**：supermall 的启动已经复杂到需要固化了。

- **三个必需环境变量**（`MERCHANT_JWT_SECRET`、`MALL_WORKER_ID`、`MALL_DATACENTER_ID`），缺任何一个都启动失败
- **两套服务机制**：MySQL 是 Windows 服务，Redis/RabbitMQ 是 WSL 容器
- **WSL 会在最后一条 `wsl.exe` 命令结束约 60 秒后关掉整个 VM**，测试期间必须保持一个 WSL 会话存活
- 中文字段必须走 UTF-8 文件，直接 `curl -d` 会被 Git Bash 按 GBK 发出

**现状**：`.claude/skills/` 不存在，每个新会话都要重读本节与「本地测试环境启动」再手工拼命令。

**做法**：运行 `/run-skill-generator`。这正是 `run` skill 推荐的场景——「had to set env vars, patch config… recommend `/run-skill-generator` so that work gets captured as a project skill」。

**紧迫性**：`D:\sourcecode\after-sales-agent` 的三份实现计划**都要求 supermall 处于运行状态**，启动摩擦会在每次执行计划时重复发生。

### 3. 阶段六遗留（自「当前进度」摘入，2026-09-22）

- 订单落库成功但 Redis 结果更新失败的故障注入验证；对应的定时补偿任务已实现。发布失败分支已于 2026-09-18 通过停 RabbitMQ 完成真实验证（记录见 `docs/progress-and-loadtest-log.md`），**「订单已落库但 finalize 失败」这一支仍待验证**。
- 持续流量压测：已完成 16.76 req/s 恒定到达基线，**高流量和多实例持续压测仍待验证**。
- 多 API 实例、Redis/RabbitMQ 集群和独立订单消费者的生产式压测。
- 2026-08-11 那轮压测结束后测试进程已停止——**当前不能假定 Redis、RabbitMQ AMQP 或应用仍在运行**，开工前按「本地测试环境启动」重新拉起。

---

## 相关项目

| 项目 | 位置 | 关系 |
|---|---|---|
| **after-sales-agent** | `D:\sourcecode\after-sales-agent` | 售后决策与执行 Agent，通过 **MCP** 调用本项目的能力，不直连数据库。设计与计划已完成，待执行 |
