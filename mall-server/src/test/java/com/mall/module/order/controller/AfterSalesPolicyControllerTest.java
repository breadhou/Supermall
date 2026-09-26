package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import com.mall.module.order.service.impl.RefundEligibilityServiceImpl;
import com.mall.security.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AfterSalesPolicyControllerTest {

    private final AfterSalesPolicyCatalog policyCatalog = new AfterSalesPolicyCatalog();
    private final AfterSalesPolicyController controller = new AfterSalesPolicyController(policyCatalog);

    @Test
    void listPolicies_shouldReturnSuccessfulCatalogWithEveryPolicy() {
        Result<PolicyCatalogVO> result = controller.listPolicies();

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(AfterSalesPolicy.values().length, result.getData().getClauses().size());
    }

    @Test
    void policyEndpointAndEligibilityUseSameSnapshot() {
        PolicyCatalogVO snapshot = controller.listPolicies().getData();
        Long orderId = 9001L;
        Long userId = 1001L;
        Order order = new Order()
                .setId(orderId)
                .setUserId(userId)
                .setStatus("RECEIVED")
                .setTotalAmount(new BigDecimal("199.99"))
                .setCreatedAt(LocalDateTime.now().minusDays(2));
        OrderMapper orderMapper = mock(OrderMapper.class);
        RefundMapper refundMapper = mock(RefundMapper.class);
        when(orderMapper.selectById(orderId)).thenReturn(order);
        when(refundMapper.selectOne(any())).thenReturn(null);

        try (MockedStatic<UserContext> userContext = mockStatic(UserContext.class)) {
            userContext.when(UserContext::getUserId).thenReturn(userId);
            RefundEligibilityVO eligibility =
                    new RefundEligibilityServiceImpl(orderMapper, refundMapper, policyCatalog).check(orderId);
            JsonNode eligibilityJson = new ObjectMapper().valueToTree(eligibility);

            assertEquals(snapshot.getFingerprint(),
                    eligibilityJson.path("catalogFingerprint").asText(null));
            assertEquals(order.getStatus(), eligibilityJson.path("orderStatus").asText(null));
        }
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
