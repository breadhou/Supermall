# 秒杀 JMeter 压测

这组文件针对当前接口链路：

```text
登录 → 获取动态路径 → 对齐并发 → Lua 预扣库存 → RabbitMQ 异步下单 → 轮询结果
```

## 1. 先准备独立压测数据

使用 IDEA 的数据库控制台或 MySQL 客户端执行：

```text
jmeter/prepare-seckill-data.sql
```

脚本使用 `910000000000000006` 作为压测秒杀商品 ID，库存为 1,000,000，活动窗口从当前时间开始持续 2 小时。它不会修改之前集成测试使用的 `900000000000000006`。

脚本可以重复执行，但同一个压测商品已经产生订单后，建议更换脚本中的整组 `91000000000000000x` ID，避免旧用户的“一人一单”记录影响下一轮。

## 2. 创建用户和默认地址

在项目根目录执行：

```powershell
$env:LOADTEST_PASSWORD = '<你的压测密码>'
powershell -ExecutionPolicy Bypass -File .\jmeter\prepare-users.ps1 `
  -UserCount 100 -Password $env:LOADTEST_PASSWORD
```

脚本会调用当前运行中的应用注册用户、创建默认地址，并生成 `jmeter/users.csv` 和单独的 `jmeter/setup-user.csv`。

`-Password` 是**必填参数，没有默认值**——刻意如此。默认口令会被遗忘，最终在某次部署中变成人尽皆知的凭据。请用环境变量传入，不要把口令写进命令行记录或本文件。

生成的 `jmeter/*.csv` 含明文口令，已在 `.gitignore` 中整体排除；不要用 `git add -f` 强制加入。

每个线程需要一行不同的用户；秒杀消费者没有地址时会落入死信队列，因此默认地址是必需的。

## 3. 先做小流量验证

```powershell
$jmeter = 'D:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
& $jmeter -n `
  -t .\jmeter\seckill-load-test.jmx `
  -JitemId=910000000000000006 `
  -Jthreads=10 -JrampUp=10 -Jduration=20 `
  -JusersFile="$((Resolve-Path '.\jmeter\users.csv').Path)" `
  -l .\jmeter\results\smoke.jtl `
  -e -o .\jmeter\report\smoke
```

确认应用日志、MySQL 订单、Redis DB1 库存和 RabbitMQ 主/死信队列均正常后，再提高并发。

## 4. 推荐的第一轮正式压测

```powershell
New-Item -ItemType Directory -Force .\jmeter\results, .\jmeter\report | Out-Null
$jmeter = 'D:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
$users = (Resolve-Path '.\jmeter\users.csv').Path
& $jmeter -n `
  -t .\jmeter\seckill-load-test.jmx `
  -JitemId=910000000000000006 `
  -Jthreads=100 -JrampUp=30 -Jduration=60 `
  -JusersFile="$users" `
  -l .\jmeter\results\seckill-100t.jtl `
  -e -o .\jmeter\report\seckill-100t
```

测试计划的预热线程组只执行一次，主线程组再读取同一份用户 CSV。不要把 `preheat` 接口放进主线程循环，否则会不断重置 Redis 库存，测试结果无效。

## 5. 查看结果

- `jmeter\report\seckill-100t\index.html`：HTML 汇总报告。
- `jmeter\results\seckill-100t.jtl`：原始采样结果。
- 重点关注秒杀执行接口的吞吐量、P95/P99、错误率，以及最终 `SUCCESS/FAILED` 数量。

压测期间不要删除 Redis DB1 的其他业务数据；测试计划只访问秒杀接口，压测数据使用独立 ID。

## 执行接口 QPS 阶梯测试

本地持续压测可使用 `loadtest` profile 将动态 path 有效期放宽到 15 分钟：

```text
java -jar mall-server.jar --spring.profiles.active=loadtest
```

生产环境不启用该 profile，默认 path 有效期保持 60 秒。

若要排除登录和动态路径生成对结果的影响，可先为某个活动准备 JWT 和动态路径：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\prepare-token-paths.ps1 `
  -ItemId 940000000000000000 -Count 500
```

然后执行 `seckill-execute-qps.jmx`。该计划只测合法的执行请求，会继续经过 Lua、RabbitMQ 和订单消费者；每次测试必须使用尚未参与该活动的用户和足够库存。

## 当前进度（2026-07-31）

- 第一轮 100 线程全链路压测已完成：30 秒升压、60 秒窗口，402 个采样全部 HTTP 成功，100 个新用户生成 100 条秒杀订单，MySQL/Redis 库存由 978 降至 878，主队列和死信队列均清空。
- 纯执行突发压测中，450 并发最后一轮 450/450 业务成功，平均 219.8ms、P95 345ms、P99 388ms，约 2,103 瞬时 QPS；455/460/475 并发出现部分 `HttpHostConnectException`。该结果是本机突发边界，不代表稳定持续 QPS。
- 秒杀入口优化已提交为 `ba495db`：执行阶段使用 Redis 快照和 Lua 原子预占，publisher confirm 成功后才返回 `WAITING`，并加入 pending/死信补偿和指标。
- 本地 `loadtest` profile 将动态 path 有效期设为 15 分钟，生产默认仍为 60 秒；压测仍需使用全新用户和足够库存。
- 截至 2026-07-31，MySQL、Redis、RabbitMQ 测试环境曾关闭，因此当时未执行稳定到达率压测或多实例压测；2026-08-11 已恢复环境并完成新的突发压测，结果见下节。

## 2026-08-11 真实突发压测记录

- 应用使用 `loadtest` profile 运行在 `http://localhost:8081`；MySQL、Redis、RabbitMQ 均已启动。
- 本轮使用独立 `itemId=994000000000000006`，每轮预热库存为 1,000,000。

| 档位 | 执行请求 | 连接失败 | 成功请求平均耗时 | 成功请求最大耗时 |
|------|----------|----------|------------------|------------------|
| 100 线程，30 秒升压 | 100/100 | 0 | 60.2 ms | 96 ms |
| 200 线程，30 秒升压 | 200/200 | 0 | 102.4 ms | 169 ms |
| 400 线程，30 秒升压 | 380/400 | 20，全部为 `HttpHostConnectException` | 152.0 ms | 220 ms |

三轮均在各自预热后执行，成功请求/订单分别为 100、200、380；最终检查时数据库库存和 Redis 库存均为 999,320；`seckill_order` 累计 680 条且 `distinct user` 为 680；RabbitMQ 主队列、死信队列和 Redis pending 均为 0。

这仍是线程对齐的一次性突发压测，不能作为持续 QPS 结论。400 线程的 20 个失败均为连接建立失败，不是业务层错误。下一步使用新的恒定吞吐压测计划，再验证稳定到达率，并分别统计 HTTP 接收 QPS 与订单落库 QPS。

## 2026-08-14 恒定吞吐单机基线

- `seckill-sustained-qps.jmx` 已改用 JMeter 内置 `PreciseThroughputTimer`。`ConstantThroughputTimer` 的共享模式在本计划中未生效，不能继续使用；JMeter 5.6.3 在 JDK 22 下也不再依赖 JSR223/Groovy 脚本。
- 计时器校准使用 `itemId=997000000000000006`，100 个合法请求首尾跨度约 4.8 秒且无 HTTP 错误；该商品只用于校准，不能作为性能结果。
- 正式测试使用全新 `itemId=998000000000000006`、1,000 个新用户、1,000,000 库存，1,000 个执行请求在 59.671 秒内完成，实际到达率 16.76 req/s（1,000 samples/min），HTTP 错误 0，平均 10.89 ms，P95 18 ms，P99 23 ms，最大 36 ms。
- 测试结束后 `seckill_item` 和 Redis DB1 库存均为 999,000；新增秒杀订单 1,000 条且用户去重数为 1,000；RabbitMQ 主队列/死信队列、Redis pending 均为 0，publisher confirm/ack/nack 无异常。
- 随后使用全新 `itemId=999000000000000006` 做短时高率探针：1,000 个合法请求在 1.050 秒内完成，实际约 952.38 req/s，HTTP 错误 0，平均 56.15 ms，P95 210 ms，P99 244 ms，最大 255 ms；DB/Redis 库存均为 999,000，订单 1,000 条且用户去重，MQ/pending 均清空。
- Windows 主机本轮最低可用内存约 3.02 GB，JVM 工作集约 0.38 GB，未观察到内存耗尽。该结果是单机低速恒定到达基线，不是 1,000 req/s，更不是万级 QPS；后续高流量测试需要逐级提高 `PreciseThroughputTimer` 目标并使用更多独立用户/负载机。

高率探针只持续约 1 秒，不能替代 60 秒持续验收；正式计划文件在探针结束后已恢复为 1,000 samples/min（约 16.7 req/s）。

## 2026-08-14 单机高率探针结论

| 目标 | 实际到达率 | HTTP 成功 | 连接失败 | 成功请求耗时（平均/P95/P99） |
|---|---:|---:|---:|---:|
| 1,000 req/s | 952.38 req/s | 1,000/1,000 | 0 | 56.15/210/244 ms |
| 2,000 req/s | 1,036.27 req/s | 995/1,000 | 5 | 220.66/445/528 ms |
| 5,000 req/s | 1,262.63 req/s | 776/1,000 | 224 | 158.14/364/381 ms |

2k 和 5k 探针中的失败全部是 `HttpHostConnectException`，不是业务错误；每轮成功请求对应的 MySQL/Redis 库存、秒杀订单、RabbitMQ 队列和 Redis pending 均一致。单机阶段结论是：当前主机可以完成低速 60 秒稳定基线和约 1k req/s 短时合法请求，继续提高目标会先触发连接接入瓶颈，不能据此宣称万级持续 QPS。计划文件已恢复为 1,000 samples/min，后续云平台、多 API 实例和多负载机验证等项目功能完成后再进行。
