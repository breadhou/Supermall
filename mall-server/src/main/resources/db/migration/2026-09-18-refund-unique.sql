-- 一单一退：Agent 会重试，没有这个约束一次重试就是一笔重复退款
--
-- 本脚本自判断环境状态，可安全重复执行（幂等）。它同时负责两件事：
--   1. 给 refund.order_id 加唯一索引 uk_refund_order
--   2. 删除冗余的非唯一索引 idx_order_id（唯一索引已完全覆盖同一列的查询）
--
-- 本仓库没有 flyway/liquibase，init.sql 也不随应用启动执行，因此这个文件
-- 就是本迁移唯一的持久载体 —— 它必须自带 runbook。
--
-- ===========================================================================
-- 运行前检查（务必先做，不要跳过）
-- ===========================================================================
-- ADD UNIQUE KEY 遇到表中已有重复 order_id 时会以 ERROR 1062 失败。若 ADD 与
-- DROP 写在同一条 ALTER 里，整条语句原子回滚 —— DROP 也不会生效，表回到原样。
-- 这是刻意的：宁可原索引留着，也不要表处于「两个索引都没了」的状态。
--
-- 但更要紧的是本任务的前提：当前系统没有幂等，所以线上存在重复退款历史行
-- 是预期状态，不是异常。迁移前必须先查：
--
--     SELECT order_id, COUNT(*) c FROM refund GROUP BY order_id HAVING c > 1;
--
-- 若查出重复行，必须先由人工决定哪一条为准（比金额？比状态？比时间？），
-- 清理或合并之后才能执行本迁移。不要用自动规则猜哪条该留。
--
-- ===========================================================================
-- 按环境状态的执行矩阵
-- ===========================================================================
-- 「uk 可用」的定义是：存在一个名叫 uk_refund_order 的索引，且它**恰好**是
-- (order_id) 上的**单列唯一**索引。三者缺一不可，下面靠 @uk_ok 一次判定。
--
--   环境状态                              uk 可用?  本脚本行为
--   -----------------------------------   -------  --------------------------
--   索引不存在                            否       只 ADD
--   单列唯一 (order_id)                   是       无操作（@clauses 为空 → DO 0）
--   单列唯一但列不对 / 单列非唯一（撞名）  否       先 DROP 再 ADD 重建
--   复合唯一 (order_id, status)           否       先 DROP 再 ADD 重建
--   复合非唯一 (order_id, status)         否       先 DROP 再 ADD 重建
--
-- 为什么必须查得这么细 —— 这三个坑都是静默或误导的：
--   · 只按索引名判断：撞名的**非唯一**索引会被当成「已就位」，于是跳过 ADD 却
--     仍删掉 idx_order_id —— 退出码 0、无报错，而 order_id 上其实没有任何唯一
--     约束，幂等保证落空，还比迁移前更糟（原索引也没了）。
--   · 只判断 non_unique：**复合**唯一索引 (order_id, status) 同样让「非 0 即存在」
--     成立，于是什么都不做 —— 可 order_id 单独并不唯一，一单仍可多退。
--   · 用 `= 1` 比对一个按列展开的行数：information_schema.statistics **每个索引列
--     一行**，复合索引因此返回 2 行，等值比较全部落空，得到静默的 DO 0 或
--     令人困惑的 ERROR 1061。
-- 所以判据必须是「恰好一列 + 那一列是 order_id + non_unique = 0」。
--
-- 说明：DROP INDEX 在 MySQL 里不支持 IF EXISTS，所以下面用 information_schema
-- 读出当前实际状态，再动态拼出需要执行的子句。
--
-- ===========================================================================
-- 执行方式
-- ===========================================================================
--     mysql -uroot -p --default-character-set=utf8mb4 -D mall < 2026-09-18-refund-unique.sql
--
-- **必须**用 -D 指定目标库，脚本内刻意不写 USE：
--   · 受版本控制的文件跨环境保持逐字一致，不需要每个环境改一次库名 —— 这种
--     「每环境改一处」的漂移正是本迁移最初那版缺陷的成因。
--   · 目标库出现在 shell 历史、CI 日志和 runbook 里，事后可查。
--   · 临时库测试只需把 -D 换成 -D mall_scratch，无需改动文件。
--   · 忘加 -D 得到的是无害的 ERROR 1046 No database selected；若脚本内写死
--     USE mall，忘改库名时会把迁移静默打到生产库上。
-- 下面的 information_schema 查询用 DATABASE()，它返回的正是 -D 指定的库。
--
-- ===========================================================================
-- 运行后核对（成功时本脚本不打印任何东西，必须显式核对）
-- ===========================================================================
-- 迁移成功是**静默**的（DO 0 或一条不输出结果的 ALTER），所以「跑了但没做事」
-- 和「根本没跑」在终端上看着一样。执行后务必核对：
--
--     SELECT index_name, non_unique FROM information_schema.statistics
--      WHERE table_schema = DATABASE() AND table_name = 'refund';
--
-- 期望恰好三行、且值如下：
--     PRIMARY          0
--     uk_refund_order  0     ← 必须存在且 non_unique = 0
--     idx_user_id      1
-- 且 **idx_order_id 不应出现**（它已被本迁移删除）。
-- ===========================================================================

-- uk_refund_order 名下有几行 statistics。每个索引列一行，所以 0 表示索引不存在，
-- 1 表示单列，≥2 表示复合索引。
SET @uk_rows := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name   = 'refund'
                   AND index_name   = 'uk_refund_order');

-- 它是否以 order_id 作为唯一索引的**第一列**（non_unique = 0 才排除了非唯一索引）
SET @uk_head := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name   = 'refund'
                   AND index_name   = 'uk_refund_order'
                   AND non_unique   = 0
                   AND seq_in_index = 1
                   AND column_name  = 'order_id');

-- 「恰好一列」+「那一列是 order_id 且唯一」= 可用。复合索引 @uk_rows ≥ 2，被排除。
SET @uk_ok := (@uk_rows = 1 AND @uk_head = 1);

-- idx_order_id 是否还在（任何形态都算，它整体是冗余的）
SET @has_old := (SELECT COUNT(*) > 0 FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name   = 'refund'
                   AND index_name   = 'idx_order_id');

-- CONCAT_WS 会跳过 NULL，所以「不需要的子句」传 NULL 即可。
-- 顺序要紧：同名索引必须先 DROP 再 ADD，否则 MySQL 报 1061 duplicate key name。
SET @clauses := CONCAT_WS(', ',
    IF(@uk_rows > 0 AND @uk_ok = 0, 'DROP KEY uk_refund_order',               NULL),
    IF(@uk_ok  = 0,                 'ADD UNIQUE KEY uk_refund_order (order_id)', NULL),
    IF(@has_old,                    'DROP KEY idx_order_id',                  NULL));

-- uk 已可用且 idx 已不在时 @clauses 为空串；DO 0 是合法的空操作语句。
SET @stmt := IF(@clauses = '', 'DO 0', CONCAT('ALTER TABLE refund ', @clauses));

PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
