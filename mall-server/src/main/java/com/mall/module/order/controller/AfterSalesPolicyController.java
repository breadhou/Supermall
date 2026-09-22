package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Exposes the policy clauses defined with the eligibility rules.
 *
 * <p>Each response carries an immutable clause snapshot and a fingerprint derived from that
 * snapshot. A caller can compare fingerprints from separate responses to detect catalog drift.</p>
 */
@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesPolicyController {

    @GetMapping("/policies")
    public Result<PolicyCatalogVO> listPolicies() {
        List<PolicyClauseVO> clauses = Arrays.stream(AfterSalesPolicy.values())
                .map(policy -> PolicyClauseVO.of(
                        policy.name(), policy.getTitle(), policy.getClauseText()))
                .toList();

        Result<PolicyCatalogVO> result = Result.build();
        result.success(PolicyCatalogVO.of(clauses));
        return result;
    }
}
