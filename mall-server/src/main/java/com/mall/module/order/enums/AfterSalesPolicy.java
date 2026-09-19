package com.mall.module.order.enums;

import java.util.Arrays;

/**
 * 售后政策。
 *
 * <p><b>判定参数与条款文本刻意放在同一处。</b>Agent 侧会用 RAG 检索条款来组织解释，
 * 如果文本另有来源，就可能出现「代码判定拒绝、解释却说可以」的矛盾。放在一起，
 * 一致性由构造保证，而不是靠约定。</p>
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

    /** 签收后 7 天内无理由。 */
    SEVEN_DAY_NO_REASON("7 天无理由退货",
            "自签收之日起 7 天内，商品未使用且不影响二次销售的，可申请无理由退货。",
            7) {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "RECEIVED".equals(orderStatus) && daysSinceReceipt <= windowDays;
        }
    },

    /** 已发货但尚未签收。 */
    SHIPPED_NOT_RECEIVED("已发货未签收退款",
            "订单已发货但尚未签收的，可申请退款；退款在货物退回后完成。") {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "SHIPPED".equals(orderStatus) || "DELIVERED".equals(orderStatus);
        }
    },

    /** 质量问题，不受 7 天窗口限制。 */
    QUALITY_ISSUE("质量问题退货",
            "商品存在质量问题的，凭有效凭证可申请退货退款，不受 7 天期限限制。") {
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
     * <p><b>本方法只看订单状态与签收天数，看不到用户主张的申诉理由。</b>
     * 因此签收第 2 天提出的<b>质量投诉</b>，返回值同样是 {@link #SEVEN_DAY_NO_REASON}，
     * 调用方拿到的标题与条款文本是「7 天无理由」，而不是质量问题的表述。
     * 需要按申诉理由区分时（例如运费承担方不同），本方法的信息不足以支撑，
     * 必须另行传入申诉类型——届时需同步调整调用方与跨仓库契约。
     * 当前模型里只有整单退款，没有运费或部分退款字段，故暂不引入该参数。</p>
     *
     * @param orderStatus 订单状态，如 {@code PENDING}/{@code PAID}/{@code SHIPPED}/
     *                    {@code DELIVERED}/{@code RECEIVED}/{@code CANCELLED}
     * @param daysSinceReceipt 签收天数；未签收的订单该值无意义
     * @return 适用政策；无任何政策适用时返回 {@code null}
     */
    public static AfterSalesPolicy resolve(String orderStatus, long daysSinceReceipt) {
        return Arrays.stream(values())
                .filter(policy -> policy.appliesTo(orderStatus, daysSinceReceipt))
                .findFirst()
                .orElse(null);
    }
}
