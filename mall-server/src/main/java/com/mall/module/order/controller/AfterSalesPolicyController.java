package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the policy clauses defined with the eligibility rules.
 *
 * <p>Each response carries an immutable clause snapshot and a fingerprint derived from that
 * snapshot. A caller can compare fingerprints from separate responses to detect catalog drift.</p>
 */
@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesPolicyController {

    private final AfterSalesPolicyCatalog policyCatalog;

    public AfterSalesPolicyController(AfterSalesPolicyCatalog policyCatalog) {
        this.policyCatalog = policyCatalog;
    }

    @GetMapping("/policies")
    public Result<PolicyCatalogVO> listPolicies() {
        Result<PolicyCatalogVO> result = Result.build();
        result.success(policyCatalog.currentSnapshot());
        return result;
    }
}
