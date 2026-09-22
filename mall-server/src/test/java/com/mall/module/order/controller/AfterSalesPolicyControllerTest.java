package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AfterSalesPolicyControllerTest {

    private final AfterSalesPolicyController controller = new AfterSalesPolicyController();

    @Test
    void listPolicies_shouldReturnSuccessfulCatalogWithEveryPolicy() {
        Result<PolicyCatalogVO> result = controller.listPolicies();

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(AfterSalesPolicy.values().length, result.getData().getClauses().size());
    }

    @Test
    void listPolicies_shouldExposeEachPolicyUsingItsEnumNameAsCode() {
        List<PolicyClauseVO> clauses = controller.listPolicies().getData().getClauses();
        Map<String, PolicyClauseVO> clausesByCode = clauses.stream()
                .collect(Collectors.toMap(PolicyClauseVO::getCode, clause -> clause));

        for (AfterSalesPolicy policy : AfterSalesPolicy.values()) {
            PolicyClauseVO clause = clausesByCode.get(policy.name());
            assertNotNull(clause, () -> "missing clause for " + policy.name());
            assertEquals(policy.getTitle(), clause.getTitle());
            assertEquals(policy.getClauseText(), clause.getClauseText());
        }
    }

    @Test
    void listPolicies_shouldExposeNonblankClauseTextForEveryPolicy() {
        List<PolicyClauseVO> clauses = controller.listPolicies().getData().getClauses();

        for (PolicyClauseVO clause : clauses) {
            assertNotNull(clause.getCode());
            assertFalse(clause.getClauseText().isBlank(),
                    () -> "clause text is blank for " + clause.getCode());
        }
    }

    @Test
    void listPolicies_shouldIncludeANonblankFingerprint() {
        String fingerprint = controller.listPolicies().getData().getFingerprint();

        assertNotNull(fingerprint);
        assertFalse(fingerprint.isBlank());
    }

    @Test
    void listPolicies_shouldKeepFingerprintStableAcrossCalls() {
        String first = controller.listPolicies().getData().getFingerprint();
        String second = controller.listPolicies().getData().getFingerprint();

        assertEquals(first, second);
    }

    @Test
    void catalogFingerprint_shouldChangeWhenClauseTextChanges() {
        PolicyClauseVO original = PolicyClauseVO.of("X", "Title", "Original clause");
        PolicyClauseVO edited = PolicyClauseVO.of("X", "Title", "Edited clause");

        assertFalse(PolicyCatalogVO.of(List.of(original)).getFingerprint()
                .equals(PolicyCatalogVO.of(List.of(edited)).getFingerprint()));
    }

    @Test
    void catalogFingerprint_shouldChangeWhenClauseOrderChanges() {
        PolicyClauseVO first = PolicyClauseVO.of("A", "First", "First clause");
        PolicyClauseVO second = PolicyClauseVO.of("B", "Second", "Second clause");

        assertFalse(PolicyCatalogVO.of(List.of(first, second)).getFingerprint()
                .equals(PolicyCatalogVO.of(List.of(second, first)).getFingerprint()));
    }

    @Test
    void catalog_shouldKeepAnImmutableSnapshotOfItsInputClauses() {
        PolicyClauseVO first = PolicyClauseVO.of("A", "First", "First clause");
        PolicyClauseVO second = PolicyClauseVO.of("B", "Second", "Second clause");
        List<PolicyClauseVO> source = new ArrayList<>(List.of(first));

        PolicyCatalogVO catalog = PolicyCatalogVO.of(source);
        String fingerprint = catalog.getFingerprint();
        source.add(second);

        assertEquals(1, catalog.getClauses().size());
        assertEquals("A", catalog.getClauses().get(0).getCode());
        assertEquals(fingerprint, catalog.getFingerprint());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> catalog.getClauses().add(second));
    }
}
