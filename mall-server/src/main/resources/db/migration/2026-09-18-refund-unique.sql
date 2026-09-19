-- 一单一退：Agent 会重试，没有这个约束一次重试就是一笔重复退款
ALTER TABLE refund ADD UNIQUE KEY uk_refund_order (order_id);
