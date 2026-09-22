package com.mall.module.order.entity.vo;

import lombok.Getter;

/**
 * A policy clause in the catalog exposed to the agent.
 *
 * <p>The value is immutable so a catalog's fingerprint cannot become detached from a clause
 * after the catalog is created.</p>
 */
@Getter
public final class PolicyClauseVO {

    private final String code;
    private final String title;
    private final String clauseText;

    private PolicyClauseVO(String code, String title, String clauseText) {
        this.code = code;
        this.title = title;
        this.clauseText = clauseText;
    }

    public static PolicyClauseVO of(String code, String title, String clauseText) {
        return new PolicyClauseVO(code, title, clauseText);
    }
}
