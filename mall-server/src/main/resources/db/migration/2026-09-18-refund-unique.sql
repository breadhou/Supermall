-- 一单一退：Agent 会重试，没有这个约束一次重试就是一笔重复退款
-- 同时删除 idx_order_id：唯一索引已完全覆盖该列查询，原非唯一索引是冗余的
ALTER TABLE refund
    ADD UNIQUE KEY uk_refund_order (order_id),
    DROP KEY idx_order_id;
