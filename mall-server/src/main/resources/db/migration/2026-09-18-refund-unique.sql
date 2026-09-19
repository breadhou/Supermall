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
--   环境状态                        期望行为        本脚本行为
--   ----------------------------    ------------    --------------------------
--   全新（无 uk、有 idx）           加 uk，删 idx   一条 ALTER 同时完成
--   prod 形态（有 uk、有 idx）      只删 idx        只生成 DROP KEY 子句
--   已迁移（有 uk、无 idx）         无操作          @clauses 为空，执行 DO 0
--   异常（无 uk、无 idx）           加 uk           只生成 ADD 子句
--   同名但非唯一（uk 撞名、类型错） 重建为唯一      先 DROP 再 ADD 同名索引
--
-- 最后一行值得说明：只按索引名判断是不够的。若有人手工建了一个**非唯一**索引
-- 却叫 uk_refund_order，只查名字会误判成「已经有了」，于是跳过 ADD 却仍删掉
-- idx_order_id —— 脚本报成功，而 order_id 上其实一个唯一约束都没有，幂等保证
-- 落空。所以这里连 non_unique 一起判断，撞名且类型不对时删掉重建。
--
-- 说明：DROP INDEX 在 MySQL 里不支持 IF EXISTS，所以下面用 information_schema
-- 读出当前实际状态，再动态拼出需要执行的子句。
--
-- ===========================================================================
-- 执行方式
-- ===========================================================================
--     mysql -uroot -p --default-character-set=utf8mb4 < 2026-09-18-refund-unique.sql
--
-- USE mall; 让脚本在不带 -D 时也能解析表名；下面的 information_schema 查询用
-- DATABASE() 取当前库，因此要迁移别的库（如 staging）时，只需改这一行的库名。
-- ===========================================================================

USE mall;

-- 唯一的 uk_refund_order（non_unique = 0 才是唯一索引）
SET @has_uk := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name   = 'refund'
                  AND index_name   = 'uk_refund_order'
                  AND non_unique   = 0);

-- 撞名但类型不对的非唯一索引（见上方矩阵最后一行）
SET @uk_wrong_type := (SELECT COUNT(*) FROM information_schema.statistics
                       WHERE table_schema = DATABASE()
                         AND table_name   = 'refund'
                         AND index_name   = 'uk_refund_order'
                         AND non_unique   = 1);

SET @has_old := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name   = 'refund'
                   AND index_name   = 'idx_order_id');

-- CONCAT_WS 会跳过 NULL，所以「不需要的子句」传 NULL 即可。
-- 顺序要紧：同名索引必须先 DROP 再 ADD，否则 MySQL 报 1061 duplicate key name。
SET @clauses := CONCAT_WS(', ',
    IF(@uk_wrong_type = 1, 'DROP KEY uk_refund_order',                   NULL),
    IF(@has_uk        = 0, 'ADD UNIQUE KEY uk_refund_order (order_id)',  NULL),
    IF(@has_old       = 1, 'DROP KEY idx_order_id',                      NULL));

-- 两个索引都已就位时 @clauses 为空串；DO 0 是合法的空操作语句。
SET @stmt := IF(@clauses = '', 'DO 0', CONCAT('ALTER TABLE refund ', @clauses));

PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
