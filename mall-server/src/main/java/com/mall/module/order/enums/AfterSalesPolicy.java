package com.mall.module.order.enums;

import java.util.Arrays;

/**
 * 售后政策。
 *
 * <p><b>判定参数与条款文本刻意放在同一处。</b>Agent 侧会用 RAG 检索条款来组织解释，
 * 如果文本另有来源，就可能出现「代码判定拒绝、解释却说可以」的矛盾。共址让规则
 * 与文本能在同一次修改中审阅；两者的语义一致性由测试和代码审查守护。构造签名
 * 要求每个枚举项同时提供标题与条款文本，其非空约束由 {@code AfterSalesPolicyTest}
 * 守护。</p>
 *
 * <p>{@link #resolve} 按声明顺序取第一个命中的政策，因此<b>顺序即优先级</b>。</p>
 *
 * <p><b>警告：{@link #QUALITY_ISSUE} 匹配所有 {@code RECEIVED} 订单，是兜底项，
 * 因此追加在它之后的 {@code RECEIVED} 政策永远不会被 {@link #resolve} 返回。</b>
 * 新增 {@code RECEIVED} 政策时必须插到 {@link #QUALITY_ISSUE} <b>之前</b>，
 * 或扩展 {@link #QUALITY_ISSUE} 自身的判定。</p>
 *
 * <p>被遮蔽的常量比死代码更危险：{@code values()} 会连同它的 {@code clauseText}
 * 一起进入 Agent 侧的 RAG 索引，于是代码判定永不命中、检索到的文本却说适用——
 * 正是本枚举开头要防的那种矛盾。
 * {@code AfterSalesPolicyTest#everyPolicyIsReachableThroughResolve} 守住这条底线。</p>
 */
public enum AfterSalesPolicy {

    /** 已签收且从订单创建时间起完整经过的 24 小时天数不超过 7。 */
    SEVEN_DAY_NO_REASON("已签收（完整天数不超过 7）整单退款",
            "订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；"
                    + "计数不超过 7 天可申请整单退款，退款执行后立即完成。",
            7) {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "RECEIVED".equals(orderStatus) && daysSinceReceipt <= windowDays;
        }
    },

    /** 已发货或已送达；当前状态模型尚未确认收货。 */
    SHIPPED_NOT_RECEIVED("已发货或已送达整单退款",
            "订单状态为已发货或已送达时，可申请整单退款；退款执行后立即完成。") {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "SHIPPED".equals(orderStatus) || "DELIVERED".equals(orderStatus);
        }
    },

    /**
     * 已签收且从订单创建时间起完整经过的 24 小时天数超过 7 的兜底政策。
     *
     * <p>枚举码为兼容既有调用方保留；当前判定没有退款原因或凭证输入，不能把本项解释为
     * 已核验质量问题。</p>
     */
    QUALITY_ISSUE("已签收（完整天数超过 7）整单退款",
            "订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；"
                    + "计数超过 7 天仍可申请整单退款，退款执行后立即完成。") {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "RECEIVED".equals(orderStatus);
        }
    };

    private final String title;
    private final String clauseText;
    /** 窗口天数；两参构造声明的政策恒为 0，表示不按天限制。 */
    protected final long windowDays;

    /** 声明一条不按天限制的政策（{@code windowDays} 恒为 0）。 */
    AfterSalesPolicy(String title, String clauseText) {
        this(title, clauseText, 0);
    }

    AfterSalesPolicy(String title, String clauseText, long windowDays) {
        this.title = title;
        this.clauseText = clauseText;
        this.windowDays = windowDays;
    }

    public String getTitle() {
        return title;
    }

    public String getClauseText() {
        return clauseText;
    }

    /** 该政策是否适用于当前订单状态与签收天数。 */
    public abstract boolean appliesTo(String orderStatus, long daysSinceReceipt);

    /**
     * 选出适用政策，落在声明顺序靠前的优先。
     *
     * <p><b>本方法只看订单状态与按订单创建时间近似得到的完整 24 小时天数；不足
     * 24 小时的余数由调用方截断。它看不到用户主张的退款理由、商品使用状态或凭证。</b>
     * 已签收且完整天数不超过 7 时返回 {@link #SEVEN_DAY_NO_REASON}，超过 7 时返回
     * 兼容码 {@link #QUALITY_ISSUE}；
     * 后者同样没有核验质量问题。需要按退款理由区分时，必须另行传入并核验相应事实，
     * 同时调整调用方与跨仓库契约。当前模型只支持立即完成的整单退款。</p>
     *
     * @param orderStatus 订单状态，如 {@code PENDING}/{@code PAID}/{@code SHIPPED}/
     *                    {@code DELIVERED}/{@code RECEIVED}/{@code CANCELLED}
     * @param daysSinceReceipt 当前由调用方按订单创建时间近似得到的完整 24 小时天数；
     *                         不足 24 小时的余数已截断，未签收订单该值无意义
     * @return 适用政策；无任何政策适用时返回 {@code null}
     */
    public static AfterSalesPolicy resolve(String orderStatus, long daysSinceReceipt) {
        return Arrays.stream(values())
                .filter(policy -> policy.appliesTo(orderStatus, daysSinceReceipt))
                .findFirst()
                .orElse(null);
    }
}
