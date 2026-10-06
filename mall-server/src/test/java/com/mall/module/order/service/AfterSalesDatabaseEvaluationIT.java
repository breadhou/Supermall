package com.mall.module.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.MallApplication;
import com.mall.common.exception.BusinessException;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL probes. No test transaction, manufactured SQL state or mock DB. */
@EnabledIfEnvironmentVariable(named = "AFTER_SALES_EVAL_DB_TEST", matches = "true")
@SpringBootTest(classes = {MallApplication.class, EvaluationProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AfterSalesDatabaseEvaluationIT {
    private static final String REASON = "Evaluation development transaction probe";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Autowired private RefundExecutionService execution;
    @Autowired private RefundEligibilityService eligibility;
    @Autowired private OrderMapper orders;
    @Autowired private RefundMapper refunds;
    @Autowired private DataSource dataSource;
    @Autowired private EvaluationProbeConfiguration.ProbeControl control;
    private Long orderId;
    private Long ownerId;
    private BigDecimal expectedAmount;
    private String beforeStatus;
    private String probe;
    private Path output;
    private long started;

    @BeforeEach
    void loadOwnedFixtureAndMeasureClock() throws Exception {
        started = System.nanoTime();
        probe = System.getenv("AFTER_SALES_EVAL_PROBE");
        assertTrue(List.of("ROLLBACK_AFTER_INSERT", "CONCURRENT_IDEMPOTENCY", "LEGACY_PENDING", "STALE_POLICY", "POLICY_WINDOW_FIXED_TIME").contains(probe));
        Path ledgerPath = Path.of(System.getenv("AFTER_SALES_EVAL_LEDGER")).toRealPath();
        output = Path.of(System.getenv("AFTER_SALES_EVAL_OUTPUT")).toRealPath();
        assertEquals(ledgerPath.getParent().resolve("probe-" + probe), output);
        JsonNode ledger = json.readTree(ledgerPath.toFile());
        assertEquals(1, ledger.path("schemaVersion").asInt());
        assertEquals("COMPLETED", ledger.path("status").asText());
        assertEquals(ledger.path("trialId").asText(), ledgerPath.getParent().getFileName().toString());
        assertEquals(ledger.path("caseId").asText(), ledgerPath.getParent().getParent().getFileName().toString());
        assertEquals(ledger.path("runId").asText(), ledgerPath.getParent().getParent().getParent().getFileName().toString());
        assertEquals(1, ledger.path("orders").size());
        String alias = ledger.path("orders").fieldNames().next();
        JsonNode fixtureOrder = ledger.path("orders").path(alias);
        String owner = fixtureOrder.path("owner").asText();
        assertEquals(ledger.path("fixture").path("activeActor").asText(), owner);
        orderId = decimalId(fixtureOrder.path("orderId"));
        ownerId = decimalId(ledger.path("actors").path(owner).path("userId"));
        expectedAmount = new BigDecimal(fixtureOrder.path("expectedPaidAmount").asText());
        assertTrue(expectedAmount.signum() > 0);
        beforeStatus = fixtureOrder.path("preparedStatus").asText();
        assertEquals(fixtureOrder.path("requestedStatus").asText(), beforeStatus);
        assertTrue(List.of("PAID", "DELIVERED", "RECEIVED").contains(beforeStatus));
        int registered = 0;
        int created = 0;
        for (JsonNode operation : ledger.path("operations")) {
            assertEquals("COMPLETED", operation.path("state").asText());
            if ("REGISTER".equals(operation.path("action").asText()) && owner.equals(operation.path("alias").asText())) {
                assertEquals(ownerId, decimalId(operation.path("receipt").path("data").path("id")));
                registered++;
            }
            if ("ORDER".equals(operation.path("action").asText()) && alias.equals(operation.path("alias").asText())) {
                assertEquals(orderId, decimalId(operation.path("receipt").path("data").path("id")));
                assertEquals(ownerId, decimalId(operation.path("receipt").path("data").path("userId")));
                created++;
            }
        }
        assertEquals(1, registered);
        assertEquals(1, created);
        assertTrue(AopUtils.isAopProxy(execution), "Real transaction proxy is required");
        measureJvmDatabaseClock();
        UserContext.setUserId(ownerId);
        assertOrder(beforeStatus);
        List<Refund> before = actualRefunds();
        assertEquals("LEGACY_PENDING".equals(probe) ? 1 : 0, before.size());
        for (Refund refund : before) {
            assertRefund(refund, "PENDING");
        }
    }

    @AfterEach
    void clearContextAndDelegates() {
        control.clear();
        UserContext.clear();
    }

    @Test
    void rollbackAfterInsert() throws Exception {
        assertEquals("ROLLBACK_AFTER_INSERT", probe);
        control.rollback(orderId);
        assertThrows(EvaluationProbeConfiguration.ProbeRollbackException.class,
                () -> execution.execute(orderId, REASON));
        assertTrue(control.realInsertObserved.get());
        long refundCountAfterRollback = actualRefunds().size();
        assertEquals(0, refundCountAfterRollback);
        String orderStatusAfterRollback = orders.selectById(orderId).getStatus();
        assertEquals(beforeStatus, orderStatusAfterRollback);
        assertOrder(beforeStatus);
        writeEvidence("REJECTED");
    }

    @Test
    void concurrentIdempotency() throws Exception {
        assertEquals("CONCURRENT_IDEMPOTENCY", probe);
        control.concurrent(orderId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<RefundEligibilityVO> receipts = new ArrayList<>();
        try {
            List<Future<RefundEligibilityVO>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(ownerId);
                    try {
                        return execution.execute(orderId, REASON);
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            for (Future<RefundEligibilityVO> future : futures) {
                receipts.add(future.get(40, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(45, TimeUnit.SECONDS), "Probe callers must terminate");
        }
        assertEquals(2, control.qualifiedReads.get());
        long freshReceipts = receipts.stream().filter(r -> r.isEligible() && !r.isRefundExists()).count();
        long idempotentReceipts = receipts.stream().filter(r -> !r.isEligible() && r.isRefundExists()).count();
        assertEquals(1, freshReceipts);
        assertEquals(1, idempotentReceipts);
        for (RefundEligibilityVO receipt : receipts) {
            assertEquals(orderId, receipt.getOrderId());
            assertEquals(0, expectedAmount.compareTo(receipt.getRefundableAmount()));
        }
        List<Refund> rows = actualRefunds();
        long refundCountAfterConcurrentCalls = rows.size();
        assertEquals(1, refundCountAfterConcurrentCalls);
        assertRefund(rows.get(0), "REFUNDED");
        assertOrder("REFUNDED");
        writeEvidence("COMPLETED");
    }

    @Test
    void legacyPendingDoesNotMeanRefunded() throws Exception {
        assertEquals("LEGACY_PENDING", probe);
        Refund before = actualRefunds().get(0);
        RefundEligibilityVO receipt = execution.execute(orderId, REASON);
        assertFalse(receipt.isEligible());
        assertTrue(receipt.isRefundExists());
        assertEquals(orderId, receipt.getOrderId());
        assertEquals(0, expectedAmount.compareTo(receipt.getRefundableAmount()));
        assertEquals("该订单已有退款申请在处理中", receipt.getReason());
        List<Refund> after = actualRefunds();
        assertEquals(1, after.size());
        assertEquals(before.getId(), after.get(0).getId());
        assertRefund(after.get(0), "PENDING");
        assertOrder(beforeStatus);
        writeEvidence("NOT_APPLICABLE");
    }

    @Test
    void stalePolicyDoesNotWrite() throws Exception {
        assertEquals("STALE_POLICY", probe);
        RefundEligibilityVO qualified = eligibility.check(orderId);
        assertTrue(qualified.isEligible());
        assertNotNull(qualified.getCatalogFingerprint());
        BusinessException rejection = assertThrows(BusinessException.class, () -> execution.execute(orderId,
                REASON, "stale-evaluation-" + qualified.getCatalogFingerprint(), qualified.getPolicyCode()));
        assertEquals(50005, rejection.getStatus().getCode());
        assertEquals(0, actualRefunds().size());
        assertOrder(beforeStatus);
        writeEvidence("REJECTED");
    }

    private Long decimalId(JsonNode node) {
        // Private journal receipts may be integer JSON; ledger IDs must be exact decimal strings.
        assertTrue(node.isTextual() || node.isIntegralNumber());
        assertTrue(node.asText().matches("[1-9][0-9]*"));
        return Long.valueOf(node.asText());
    }

    @Test
    void policyWindowFixedTime() throws Exception {
        assertEquals("POLICY_WINDOW_FIXED_TIME", probe);
        Order fixture = orders.selectById(orderId);
        assertEquals("RECEIVED", fixture.getStatus());
        JsonNode persistedBefore = json.valueToTree(fixture);
        JsonNode refundsBefore = json.valueToTree(actualRefunds());
        RefundEligibilityEvaluatorFixedTimeTest.assertWindow(
                RefundEligibilityEvaluatorFixedTimeTest.assessWindow(fixture), expectedAmount);
        assertEquals(persistedBefore, json.valueToTree(orders.selectById(orderId)));
        assertEquals(refundsBefore, json.valueToTree(actualRefunds()));
        assertOrder(beforeStatus);
        writeEvidence("NOT_APPLICABLE");
    }

    private List<Refund> actualRefunds() {
        return refunds.selectList(new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
    }

    private void assertOrder(String status) {
        Order actual = orders.selectById(orderId);
        assertNotNull(actual);
        assertEquals(orderId, actual.getId());
        assertEquals(ownerId, actual.getUserId());
        assertEquals(status, actual.getStatus());
        assertEquals(0, expectedAmount.compareTo(actual.getTotalAmount()));
    }

    private void assertRefund(Refund actual, String status) {
        assertEquals(orderId, actual.getOrderId());
        assertEquals(ownerId, actual.getUserId());
        assertEquals(status, actual.getStatus());
        assertEquals(0, expectedAmount.compareTo(actual.getAmount()));
    }

    private LocalDateTime databaseTime(Statement statement) throws Exception {
        try (ResultSet row = statement.executeQuery("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f')")) {
            assertTrue(row.next());
            LocalDateTime result = LocalDateTime.parse(row.getString(1));
            assertFalse(row.next());
            return result;
        }
    }

    private void measureJvmDatabaseClock() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            LocalDateTime before = databaseTime(statement);
            LocalDateTime jvm = LocalDateTime.now(); // Actual evaluator clock, actual test JVM.
            LocalDateTime after = databaseTime(statement);
            long intervalMs = Duration.between(before, after).toMillis();
            long lowerMs = Duration.between(before, jvm).toMillis();
            long upperMs = Duration.between(after, jvm).toMillis();
            json.writeValue(output.resolve("clock-evidence.json").toFile(), Map.of(
                    "dbBefore", before.toString(), "dbAfter", after.toString(), "jvm", jvm.toString(),
                    "zoneId", ZoneId.systemDefault().toString(), "intervalMs", intervalMs,
                    "jvmOffsetLowerMs", lowerMs, "jvmOffsetUpperMs", upperMs));
            assertTrue(intervalMs >= 0 && intervalMs <= 10_000);
            assertTrue(lowerMs >= -2_000 && upperMs <= 2_000, "Actual JVM and DB local clocks must align");
        }
    }

    private void writeEvidence(String receiptClass) throws Exception {
        json.writeValue(output.resolve("probe-result.json").toFile(), Map.of("probe", probe,
                "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "receiptClass", receiptClass, "assertionsPassed", true));
    }
}
