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

脚本使用 `910000000000000006` 作为压测秒杀商品 ID，库存为 1000，活动窗口从当前时间开始持续 2 小时。它不会修改之前集成测试使用的 `900000000000000006`。

脚本可以重复执行，但同一个压测商品已经产生订单后，建议更换脚本中的整组 `91000000000000000x` ID，避免旧用户的“一人一单”记录影响下一轮。

## 2. 创建用户和默认地址

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\prepare-users.ps1 -UserCount 100
```

脚本会调用当前运行中的应用注册用户、创建默认地址，并生成 `jmeter/users.csv` 和单独的 `jmeter/setup-user.csv`。默认密码为 `LoadTest@123456`，如需修改：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\prepare-users.ps1 `
  -UserCount 100 -Password '你的压测密码'
```

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

若要排除登录和动态路径生成对结果的影响，可先为某个活动准备 JWT 和动态路径：

```powershell
powershell -ExecutionPolicy Bypass -File .\jmeter\prepare-token-paths.ps1 `
  -ItemId 940000000000000000 -Count 500
```

然后执行 `seckill-execute-qps.jmx`。该计划只测合法的执行请求，会继续经过 Lua、RabbitMQ 和订单消费者；每次测试必须使用尚未参与该活动的用户和足够库存。
