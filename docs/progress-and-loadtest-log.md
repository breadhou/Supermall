# supermall 进度与压测记录

> 本文件由 `AGENTS.md` 于 2026-09-22 拆出。原因：Codex 对项目指令文件有 32 KiB 上限且**静默截断**
> （见 after-sales-agent 仓库 `docs/known-issues.md` 的 K-32），而 `AGENTS.md` 已达 51,908 字节。
>
> 内容为**历史记录**——各阶段进度叙事、压测流水、已修复缺陷编年。按原顺序、**原措辞**保留，未作改写。
> 仍然生效的约定与当前状态留在 `AGENTS.md`。

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

- 订单落库成功但 Redis 结果更新失败的故障注入验证；对应的定时补偿任务已实现。**发布失败分支已于 2026-09-18 通过停 RabbitMQ 完成真实验证**（见「已修复的缺陷（2026-09-18）」末尾），「订单已落库但 finalize 失败」这一支仍待验证。
- 持续流量压测（已完成 16.76 req/s 恒定到达基线，目标高流量和多实例持续压测仍待验证）。
- 多 API 实例、Redis/RabbitMQ 集群和独立订单消费者的生产式压测。
- 2026-08-11 曾恢复测试环境并完成真实压测；应用使用 `loadtest` profile 监听 `8081`，独立压测商品为 `994000000000000006`。该轮结束后测试进程已停止，当前不能假定 Redis、RabbitMQ AMQP 或应用仍在运行。


## 缺陷修复与实例配置（历史）

### 多实例部署的 ID 冲突（已修复，2026-09-18）

`SnowflakeIdUtil` 原先委托 Hutool `IdUtil.getSnowflake()` 自动推导：`datacenterId` 取 **MAC 地址最后两个字节**，`workerId` 取 `hash(datacenterId + PID)` 的低 16 位，两侧各只有 **5 位 = 32 个槽位**。

同主机多实例时 MAC 相同 → `datacenterId` 恒定，`workerId` 退化成「PID 哈希进 32 个桶」，是标准生日问题：

| 同主机实例数 | workerId 撞车概率 |
|--------------|------------------|
| 4 | 17.7% |
| 6 | 39.2% |
| 8 | 61.4% |
| 16 | 99.0% |

**撞了为什么会产出完全相同的 ID**：Hutool `Snowflake` 的 `randomSequenceLimit` 默认是 `0`（逐层追构造器确认），因此每到新的一毫秒序列号从 **0** 开始。两个共用 `(datacenterId, workerId)` 的实例在同一毫秒各自返回 `(timestamp << 22) | (dc << 17) | (worker << 12) | 0`——四个分量全同，**逐位相同**。序列号只是每实例内存里的一个 long，实例之间没有协调。

**危险之处在于它是静默的**：低负载时两者难在同一毫秒发号，几乎不触发；**恰恰是压到高 QPS 才暴露**，表现为零星主键冲突，极难回溯。

**修法**：`SnowflakeIdUtil.configure(workerId, datacenterId)` 由 `SnowflakeIdConfig` 启动时注入，取值来自环境变量 `MALL_WORKER_ID` / `MALL_DATACENTER_ID`，**无默认值**。

- 缺失时启动即失败（`Could not resolve placeholder 'MALL_WORKER_ID'`），与 `MERCHANT_JWT_SECRET` 同样的 fail-closed。
- 未配置时 `nextId()` 抛 `IllegalStateException`；**以不同值重复配置也抛错**——那会新建生成器、序列号从头开始，可能重复发号。
- 多实例部署时每个实例必须拿到互不相同的一对值（各 0~31）；同主机多实例时 datacenterId 相同、workerId 不同即可。

**验证**：启动日志打印 `Snowflake identity configured: workerId=1, datacenterId=1`；生成的 ID 反解 `(id>>12)&31` 与 `(id>>17)&31` 均等于配置值，读路径（注册用户）与写路径（下单落库）都核对过。

### 已修复的缺陷（2026-09-16）

`AddressServiceImpl` 此前零覆盖，修复两个缺陷并补齐 `AddressServiceImplTest`（6 个用例）：

1. **`isDefault` 永远无法设置**：DTO/VO 为 `Integer`，PO 为 `Boolean`，`BeanUtils.copyProperties` 因类型不匹配静默跳过该字段。已将 PO 改为 `Integer`（同时对齐数据库 `TINYINT`），并在 PO 字段上注明原因。
2. **越权/不存在时静默成功**：`updateAddress` 原本返回 `null`，Controller 直接 `result.success(null)`，导致修改他人地址返回 `code 0` 成功。现改为抛 `BusinessException(ADDRESS_NOT_EXIST)`（新增码值 `20004`）。

`SeckillConsumer`（289 行）已由 `SeckillConsumerTest` 覆盖，17 个用例聚焦 ACK/NACK 语义、幂等分支与快照字段兼容回退。

### 已修复的缺陷（2026-09-18）

1. **`useCoupon` 中 `markExpired` 的写入被自身事务回滚**：`CouponServiceImpl.useCoupon` 带 `@Transactional`，过期分支先 `markExpired()` 再抛 `BusinessException(COUPON_EXPIRED)`，抛异常触发回滚把这次 UPDATE 一起撤销，券在库里仍是 `UNUSED`。属永不生效的死写入。已删除该调用（过期状态由 `listUserCoupons` 与 `@Scheduled expireCoupons` 落库，实测定时任务能在 60s 内翻转）。
   - **根因教训**：原有单元测试 `useCoupon_shouldMarkExpiredAndReject` 断言 `verify(userCouponMapper).update(...)`，把这个 bug 当成了正确行为 —— Mock 测试看不见事务回滚。已改写为 `useCoupon_shouldRejectExpiredCouponWithoutUpdate`。**写涉及事务的副作用测试时，要断言「提交后的最终状态」，而不是「方法内调用过某次写入」。**
2. **两处硬编码 `3600` 绕过配置**：`SeckillCompensationTask` 的 `finalizeSuccess` 与 `SeckillRedisStateService.rollback` 的 Lua ARGV[5] 都写死 3600，无视 `mall.seckill.result-ttl-seconds`，与读配置的消费端静默分叉。已给任务类加 `@Value` 注入，并给 `rollback` 增加 `resultTtlSeconds` 参数（与 `finalizeSuccess`/`reserve` 对齐），三处调用点全部改传配置值。
3. **补偿任务静默吞掉 `rollback == -1`**：Lua 返回 `-1` 表示库存 key 不可用、预占数量**无法归还**，是真实超卖风险信号，原本无日志无 metric。已补 `log.warn` + `recordCompensation("rollback_stock_missing")`。注意 `SeckillConsumer` 的死信路径原本就正确处理了 `-1`，只有补偿任务这个「最后一道防线」在静默。
4. **405 被兜底成通用错误**：用错 HTTP 方法原返回 `code -1 / 系统异常`，把路由错误伪装成服务端崩溃。已新增处理器返回 HTTP 405 + `METHOD_NOT_ALLOWED(10002)`，并降为 `log.warn`。

**秒杀回滚路径的真实环境验证（2026-09-18）**：因改动涉及秒杀热路径的 `rollback` 签名，用故障注入做了真实触发——建夹具（活动 + 秒杀商品 + 预热）后先跑通正常链路（`WAITING → SUCCESS`、订单落库、Redis 与 DB 库存一致），再**停掉 RabbitMQ 容器**后执行秒杀，强制走发布失败分支：入口正确返回 `60002`（confirm 未成功就不返回 WAITING）、结果 `FAILED`、**Redis 库存回滚到位**、限购 key 释放、无订单落库；重启容器后再执行恢复正常（`WAITING → SUCCESS`，库存 98/98 一致，主队列与死信队列均空）。


## 压测记录与阶段七~九（历史）

### 已处理：移除压测脚本的默认口令（2026-09-18 完成）

`jmeter/prepare-users.ps1` 曾把压测口令作为 `$Password` 的**参数默认值**，`jmeter/README.md` 也明文写了同一个口令。两者随 `f318b3e` 推送到了公开仓库。

**为什么值得改**：这是「默认凭据」模式。将来若本项目部署到公网、且有人运行该脚本时忘记覆盖参数，就会启用一个人尽皆知的密码。公网扫描器会专门 grep GitHub 找 `[string]$Password = '...'` 这类模式。

**为什么优先级不高**：这些是本机测试夹具 —— MySQL 与应用都只监听 `localhost`，没有对外暴露的服务可以用这组凭据登录，因此当时不可直接利用。真正的风险是**未来部署时的疏忽**，不是当下的泄漏。

**改法（已完成）**：`$Password` 改为 `[Parameter(Mandatory = $true)]`，不设默认值；`jmeter/README.md` 的示例改为经 `$env:LOADTEST_PASSWORD` 传入，并说明口令刻意无默认值的理由。本文件中原先复述的口令字面量也已抹去 —— 在被跟踪的文档里重复它，正是当初该被 grep 到的模式。

**已验证的行为**：
- 语法解析通过。
- 不带 `-Password` 运行 → 拒绝：`无法处理命令，因为缺少一个或多个必需参数: Password`。
- 按 README 推荐写法但环境变量未设置 → 拒绝：`无法将自变量绑定到参数 'Password'，因为它是空字符串`（fail-closed，不会静默使用空口令）。
- 提供口令后正常运行：注册用户并生成 `users.csv` / `setup-user.csv`。

**注意 `-BaseUrl` 默认是 `http://localhost:8080`**：若应用按 `loadtest` profile 跑在 8081，需显式传 `-BaseUrl http://localhost:8081`，否则连接被拒。README 的示例流程用的是默认 8080，与此一致。

**明确不做的事**：不重写 git 历史。旧口令仍留在 `f318b3e` 中，已接受 —— 该值不含真实资产，force-push 的代价（打断所有基于旧历史的克隆）高于收益。若日后决定重写，需另行确认。
**仍未处理**：本机 `jmeter/*.csv`（已被 `.gitignore` 排除）里的历史压测账号仍用旧口令，对应数据库中的真实用户。这些是 localhost 专用的夹具，如需彻底消除，重建库或改密即可。
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

### 阶段七：优惠券模块（已完成，真实集成验证 2026-09-18 通过）

- 新增 `Coupon`、`UserCoupon` PO 和对应 MyBatis-Plus Mapper。
- 新增优惠券模板查询 `GET /api/coupons`、领取 `POST /api/coupons/{couponId}/receive`，以及用户优惠券列表 `GET /api/user/coupons?status=UNUSED|USED|EXPIRED`。
- 领取路径使用 `mall:coupon:{couponId}:stock` Redis 计数器和 `SETNX + DECR` 原子预占；数据库插入失败会回滚 Redis 库存，`user_coupon` 增加 `(user_id, coupon_id)` 唯一索引，重复领取返回已存在记录保持幂等。
- 支持 `FULL_REDUCTION` 满减和 `DISCOUNT` 折扣（折扣比例为 `0~1`），校验最低消费金额并返回订单优惠前金额、优惠金额和实付金额。
- `POST /api/orders` 传入已有 `couponId` 时会在订单事务内校验并将用户券条件更新为 `USED`，订单保存优惠后的 `total_amount` 和 `coupon_id`；取消 `PENDING` 订单会恢复未过期优惠券，过期券在查询、使用和定时任务中转为 `EXPIRED`。
- 新增 `CouponServiceImplTest` 10 个、`CouponControllerTest` 3 个和 `CouponStockRedisServiceTest` 2 个测试用例；截至本轮，当时的 16 个测试类均由 IntelliJ MCP 运行并以 `exitCode=0` 通过，IDE 项目构建成功。（测试类总数现为 21，见「测试覆盖基线」。）
- 2026-09-18 已完成真实集成验证（MySQL + Redis + RabbitMQ + 应用全部真实运行，环境启动方式见「本地测试环境启动」）：
  - 领取后 Redis 计数与 DB 领取数一致（100 → 99，DB 1 行）；同用户重复领取幂等，两次返回同一 `user_coupon.id`。
  - **并发无超发**：6 路抢 2 张券恰好 2 成功 / 4 拒绝；20 路抢 5 张券恰好 5 成功 / 15 拒绝 / 0 异常，Redis 均为 0、DB 行数与去重用户数均等于券总量。
  - **同用户 10 路并发领同一券**：10 个请求返回同一 `id`，Redis 仅减 1（其余 9 个失败路径的 `reserve` 扣减被正确回滚），DB 1 行 —— 唯一索引冲突幂等分支与 Redis 库存回滚同时得到验证。
  - 满减下单 199.99 − 20.00 = 179.99、折扣下单 60.00 × 0.8 = 48.00，`order_item` 保留原价快照；取消订单后券恢复 `UNUSED` 且 `used_at` 清空。
  - 低于门槛 / 已使用 / 越权 / 不存在 / 已过期分别返回 `70006` / `70005` / `70004` / `70000` / `70001`。
- 该轮集成验证发现并修复一个缺陷：`useCoupon` 过期分支的 `markExpired` 写入被自身的 `@Transactional` 回滚，是永不生效的死写入。已删除该调用（过期状态由 `listUserCoupons` 与 `@Scheduled expireCoupons` 落库，实测定时任务能在 60s 内翻转）。
  - **注意**：原有单元测试 `useCoupon_shouldMarkExpiredAndReject` 断言的是 `verify(userCouponMapper).update(...)`，即把这个 bug 当成了正确行为 —— Mock 测试看不见事务回滚。已改写为 `useCoupon_shouldRejectExpiredCouponWithoutUpdate`。**写涉及事务的副作用测试时，要确认断言的是「提交后的最终状态」，而不是「方法内调用过某次写入」。**

### 阶段八：支付物流模块（已完成，真实集成验证 2026-09-18 通过）

- 新增 `PaymentRecord` PO、Mapper、Service 和 VO；模拟支付接口为 `POST /api/orders/{orderId}/pay`，查询接口为 `GET /api/orders/{orderId}/payment`。
- 支付只允许当前用户操作自己的 `PENDING` 订单；事务先锁定订单行，再创建或更新 `payment_record`，成功后将订单推进到 `PAID`。
- 重复支付对已有 `SUCCESS` 记录幂等返回，并在订单仍为 `PENDING` 时修复订单状态；订单归属和非法状态分别返回订单/支付状态错误。
- 新增 `Logistics` PO、Mapper、Service、DTO 和 VO；用户查询接口为 `GET /api/orders/{orderId}/logistics`。
- 物流服务层已实现 `PAID → SHIPPED → DELIVERED` 的发货和送达流转、订单归属校验及重复发货/送达保护；由于商家后台尚未完成，发货和送达方法暂不暴露为用户 HTTP 接口，阶段九接入商家权限后再开放。
- 为支付记录和物流记录增加 `order_id` 唯一索引，避免一单多条支付/物流记录；订单状态新增兼容性的 `DELIVERED`，原有 `SHIPPED → RECEIVED` 确认收货接口仍保留，同时支持 `DELIVERED → RECEIVED`。
- `ResultStatus` 新增 80000 段支付/物流错误码；新增 `PaymentServiceImplTest` 6 个、`PaymentControllerTest` 2 个、`LogisticsServiceImplTest` 6 个和 `LogisticsControllerTest` 1 个测试用例，均已由 IntelliJ MCP 运行并以 `exitCode=0` 通过，IDE 增量构建成功。
- 2026-09-18 已完成真实集成验证：支付 `PENDING` 订单生成 `payment_record` 并使订单转 `PAID`；重复支付返回同一记录且 `payment_record` 仍为 1 行；他人代付、他人查询支付与物流均返回 `50000 ORDER_NOT_EXIST`（越权与不存在同响应，不泄漏订单存在性）；支付已取消订单返回 `80001`；无物流记录返回 `80002`；`SHIPPED → RECEIVED` 成功且重复确认返回 `50001`。
- **未覆盖**：物流的 `PAID → SHIPPED → DELIVERED` 两个方法在阶段八没有 HTTP 出口，当时只能用 SQL 夹具构造 `SHIPPED` 状态验证查询与收货流转。**阶段九 9.1 已开放商家端接口并完成真实链路验证**，见下节。

### 阶段九 9.1：商家端（已完成，2026-09-18）

设计文档：`docs/superpowers/specs/2026-09-18-merchant-module-design.md`

- **账号模型**：`admin_user` 新增 `merchant_id`（`init.sql` 已同步）。`SUPER_ADMIN` 为空、不可登录商家端；`ADMIN` 必填。种子账号 `shoptest_a`（商家 A）、`shoptest_b`（商家 B，隔离验证用）、`superadmin`，口令哈希由文档中的 jshell 命令生成，**仓库内不含明文**。
- **认证独立一套**：`MerchantJwtUtil`（密钥读 `MERCHANT_JWT_SECRET`）、`MerchantContext`、`MerchantAuthFilter`、`MerchantSecurityConfig`。**`JwtUtil` / `JwtAuthFilter` / `UserContext` / `SecurityConfig` 一行未改**——`SecurityConfig` 无 `@Order` 取 `LOWEST_PRECEDENCE`，商家链 `@Order(1)` + `securityMatcher` 先匹配，Spring Security 只跑第一个匹配的链，两条认证路径互不经过。
- **`MerchantAuthFilter` 刻意不是 `@Component`**：Spring Boot 会把容器中任何 Filter bean 自动注册到全局 Servlet 链上，那样它对所有路径生效。由 `MerchantSecurityConfig` 直接 `new` 出来只挂商家链。
- **接口**：`POST /api/merchant/login`（放行）、`POST|PUT /api/merchant/products`、`PUT .../off-shelf`、`GET /api/merchant/orders`、`POST .../{orderNo}/ship`、`POST .../{orderNo}/deliver`。
- **归属隔离**：商品 `merchant_id` 强制取自上下文；改/下架/发货前校验归属，不存在与不属于本店同码值。订单归属经 `order_item → product_sku → product.merchant_id` 推导，只返回本店明细；`MerchantOrderMapper.xml` 放在商家模块内，**不改动 order 模块**。
- **SKU 全量覆盖语义**：编辑时带 `id` 的更新、不带的添加、未提及的删除；更新前必须校验该 SKU 确属本商品，否则传入别家 SKU 的 id 就能改到别人的数据。
- **唯一触碰阶段八之处**：`LogisticsService` 新增 `shipOrderForMerchant` / `markDeliveredForMerchant`，因为原方法用 `UserContext` 校验归属而商家请求下该上下文为空。把流转提取为私有方法共用，原方法行为不变。
- **错误码**：`9xxxx` 段，见 `ResultStatus`。
- 测试：`MerchantJwtUtilTest`(4)、`MerchantAuthServiceImplTest`(4)、`MerchantProductServiceImplTest`(4)、`MerchantOrderServiceImplTest`(4)。
- **端到端验证**：登录 → 上架（SPU + 2 SKU）→ C 端可见 → C 端下单 → 商家查本店订单 → 发货 → 送达 → C 端收货，全链路通过；跨商家隔离（改商品 `90000`、发货 `90001`、订单列表为空）与 C 端 token 打商家接口 403 均已验证。

> **造数注意**：发货的 `company` 等字段含中文，必须用 UTF-8 文件 `--data-binary @file`；直接 `curl -d` 会被 Git Bash 按 GBK 发出，服务端报 `Invalid UTF-8 start byte`。本轮踩过一次。

### 阶段九 9.2：管理后台（已完成，2026-09-18）

- **认证**：`AdminAuthFilter` + `AdminSecurityConfig`（`@Order(2)` + `securityMatcher("/api/admin/**")`），与商家端**共用签发密钥**（都是内部后台，第三个密钥没有额外收益）。门槛在角色：`role = SUPER_ADMIN` 才拿到 `ROLE_SUPER_ADMIN`。平台管理员的 token **不带 `merchantId`**。
- **接口**：`POST /api/admin/login`（放行）、`GET /api/admin/users`、`PUT /api/admin/users/{id}/status`、`POST /api/admin/seckill/activities`、`POST /api/admin/coupons`、`GET /api/admin/statistics`。
- **登录**：密码错与账号不存在同码（`90005`）；密码正确但角色不符返回 `90006`（此时透露「你不是管理员」不泄漏账号是否存在）。
- **禁用用户是真实生效的**：`UserServiceImpl` 登录时检查 `user.status` 抛 `USER_BANNED`，9.2 只是提供操作入口；`user` 表原本就有 `status` 列。
- **统计口径**：`gmv` 只累加 `PENDING`/`CANCELLED` 之外的订单，不是所有订单金额之和。
- **创建秒杀活动**：必须带商品列表；`status` 仅用于展示（秒杀路径按 `start_time`/`end_time` 判断），创建时由时间推导。
- 测试：`AdminAuthServiceImplTest`(3)。
- **验证**：SUPER_ADMIN 登录通过；商家账号登录管理端 `90006`；商家 token / C 端 token / 无 token 打 `/api/admin/**` 均 403；禁用用户后其登录被拒、恢复后正常；新建活动经预热 + 倒计时验证**确实可跑**（Redis 500、快照正确）。

### 已修复缺陷（2026-09-18，做 9.2 时发现）

**`POST /api/seckill/{itemId}/preheat` 任何 C 端用户都能调用**（阶段六遗留）：

- 原接口挂在 C 端路径上，只要求已登录、不校验角色，作用却是把 Redis 库存**重置**为 `seckill_item.stock` 并重写快照。
- **实证**：把某秒杀商品的 Redis 库存人为改成 42，用一个刚注册的普通账号调用 preheat，库存被重置回 500。
- **风险**：秒杀进行中调用会让可预占数量凭空增加——**超卖**路径；至少也能把已售罄的商品重新「上架」。
- **修法**：移到 `POST /api/admin/seckill/items/{itemId}/preheat`（`ROLE_SUPER_ADMIN`），C 端端点删除；`AdminMarketingService` 委托 `SeckillService.preheatStock`。
- **验证**：普通用户打旧路径 `60002` 且库存不变；打新路径 403；管理员打新路径成功；无 token 403。
- **副作用需知情**：旧路径不是 404，而是落到 `POST /api/seckill/{itemId}/{path}` 执行接口上被当作非法秒杀请求拒掉——结果无害但不够干净。
- **压测脚本已同步**：三个 `.jmx` 改用管理员 token；新增 `jmeter/prepare-admin-token.ps1`（登录 `/api/admin/login` 并校验 `SUPER_ADMIN` 角色）产出 `admin-token.csv` 与 `admin-credentials.csv`。**跑秒杀压测前必须先执行它**，否则预热步骤会 403。`seckill-load-test.jmx` 中主线程的用户登录未动。

