package com.mall.module.order.service;

import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Produces immutable snapshots of the after-sales policy catalog used by both
 * policy readers and refund eligibility checks.
 */
@Component
public class AfterSalesPolicyCatalog {

    public PolicyCatalogVO currentSnapshot() {
        List<PolicyClauseVO> clauses = Arrays.stream(AfterSalesPolicy.values())
                .map(policy -> PolicyClauseVO.of(
                        policy.name(), policy.getTitle(), policy.getClauseText()))
                .toList();
        return PolicyCatalogVO.of(clauses);
    }
}
