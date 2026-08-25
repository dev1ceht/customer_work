package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;
import com.richard.fyoung.customerwork.tool.backend.RefundApplicationStatus;
import com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult;
import com.richard.fyoung.customerwork.tool.backend.RefundPreparationResult;
import com.richard.fyoung.customerwork.tool.backend.RefundProgressResult;
import com.richard.fyoung.customerwork.tool.backend.RefundSubmissionResult;
import com.richard.fyoung.customerwork.safety.security.UserIdentityContext;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 售后 / 退款工具组单测。重点验证两条资金安全红线：
 * 超期拒绝、退款只生成"待人工确认工单"而不直接打款。
 * @author owlzhangfq@gmail.com
 */
class AfterSalesToolsTest {

    private final MockAfterSalesBackend backend = new MockAfterSalesBackend();
    private final AfterSalesTools tools = new AfterSalesTools(backend, "test-session");
    private static final UserPrincipal USER =
        new UserPrincipal("user-1", "alice", "Alice", "default");

    @Test
    void checkRefund_shouldRouteShippedOrderToHumanReview() {
        StepVerifier.create(tools.checkRefundEligibility("20260613001"))
            .assertNext(result -> {
                assertEquals(RefundEligibilityResult.Decision.HUMAN_REVIEW, result.decision());
                assertTrue(result.reasonCodes().contains("ORDER_ALREADY_SHIPPED"));
                assertNull(result.refundableAmount(), "人工审核分支不得暴露未经确认的可退金额");
                assertEquals("refund-policy-2026-01", result.policyVersion());
            })
            .verifyComplete();
    }

    @Test
    void checkRefund_shouldAcceptPaidNotShippedOrder() {
        StepVerifier.create(tools.checkRefundEligibility("20260613003"))
            .assertNext(result -> {
                assertEquals(RefundEligibilityResult.Decision.ELIGIBLE, result.decision());
                assertEquals(new BigDecimal("199.00"), result.refundableAmount());
                assertEquals(RefundEligibilityResult.RiskLevel.NORMAL, result.riskLevel());
                assertTrue(result.reasonCodes().contains("ORDER_PAID"));
                assertTrue(result.reasonCodes().contains("NOT_SHIPPED"));
                assertTrue(result.ruleIds().contains("REFUND-001"));
                assertTrue(result.ruleIds().contains("REFUND-002"));
            })
            .verifyComplete();
    }

    @Test
    void submitRefund_shouldCreatePendingReview_afterUserConfirmation() {
        RefundPreparationResult prepared = UserIdentityContext.callWith(USER,
            () -> tools.prepareRefund("20260613003", "CHANGED_MIND", "暂时不需要了").block());
        RefundSubmissionResult submitted = UserIdentityContext.callWith(USER,
            () -> tools.submitRefund("20260613003", "CHANGED_MIND", "暂时不需要了",
                prepared.confirmationToken()).block());

        assertEquals(RefundPreparationResult.Status.READY, prepared.status());
        assertEquals(RefundSubmissionResult.Status.CREATED, submitted.status());
        assertEquals(RefundApplicationStatus.PENDING_REVIEW, submitted.applicationStatus());
        assertTrue(submitted.message().contains("人工"));
    }

    @Test
    void queryRefundApplication_shouldRetryOneTransientRead_andPreserveInvocationContext() {
        AfterSalesBackend readBackend = mock(AfterSalesBackend.class);
        AtomicInteger subscriptions = new AtomicInteger();
        List<String> invocationContexts = new java.util.ArrayList<>();
        when(readBackend.queryRefundApplication("20260613003")).thenReturn(reactor.core.publisher.Mono.defer(() -> {
            invocationContexts.add(com.richard.fyoung.customerwork.tool.backend.RefundInvocationContext.get());
            if (subscriptions.getAndIncrement() == 0) {
                return reactor.core.publisher.Mono.error(new TimeoutException("read timeout"));
            }
            return reactor.core.publisher.Mono.just(new RefundSubmissionResult(
                RefundSubmissionResult.Status.NOT_FOUND, "20260613003", null, null, null, null,
                RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                List.of("REFUND_APPLICATION_NOT_FOUND"), List.of(), "safe to retry",
                "REFUND:FULL:default:20260613003"));
        }));

        RefundSubmissionResult result = new AfterSalesTools(readBackend, "trace-session")
            .queryRefundApplication("20260613003").block();

        assertEquals(RefundSubmissionResult.Status.NOT_FOUND, result.status());
        assertEquals(2, subscriptions.get(), "只读瞬时错误最多重试一次");
        assertEquals(List.of("trace-session", "trace-session"), invocationContexts);
    }

    @Test
    void queryRefundProgress_shouldReturnProgress() {
        StepVerifier.create(tools.queryRefundProgress("20260613001"))
            .assertNext(r -> {
                assertEquals(RefundProgressResult.QueryStatus.NOT_FOUND, r.queryStatus());
                assertTrue(r.message().contains("退款申请"));
            })
            .verifyComplete();
    }

    @Test
    void queryRefundProgress_shouldPreserveApplicationApprovalAndExecutionStates() {
        AfterSalesBackend readBackend = mock(AfterSalesBackend.class);
        when(readBackend.queryRefundProgressStatus("20260613003")).thenReturn(
            reactor.core.publisher.Mono.just(new RefundProgressResult(
                RefundProgressResult.QueryStatus.FOUND, "20260613003", "RF-123",
                RefundApplicationStatus.APPROVED_PENDING_EXECUTION, ApprovalStatus.APPROVED,
                ExecutionStatus.NOT_APPLICABLE, "refund-policy-2026-01",
                List.of("REFUND-001"), "等待执行")));

        StepVerifier.create(new AfterSalesTools(readBackend, "progress-session")
                .queryRefundProgress("20260613003"))
            .assertNext(result -> {
                assertEquals(RefundApplicationStatus.APPROVED_PENDING_EXECUTION,
                    result.applicationStatus());
                assertEquals(ApprovalStatus.APPROVED, result.approvalStatus());
                assertEquals(ExecutionStatus.NOT_APPLICABLE, result.executionStatus());
                assertEquals(List.of("REFUND-001"), result.ruleIds());
            })
            .verifyComplete();
    }

    @Test
    void submitReturn_shouldGenerateReturnTicket() {
        StepVerifier.create(tools.submitReturn("20260613001", "尺码不合适"))
            .assertNext(r -> assertTrue(r.contains("退货工单")))
            .verifyComplete();
    }

    @Test
    void submitExchange_shouldGenerateExchangeTicket() {
        StepVerifier.create(tools.submitExchange("20260613001", "颜色不喜欢", "白色"))
            .assertNext(r -> assertTrue(r.contains("换货工单") && r.contains("白色")))
            .verifyComplete();
    }

    @Test
    void checkPriceProtection_shouldReturnResult() {
        StepVerifier.create(tools.checkPriceProtection("20260613001"))
            .assertNext(r -> assertTrue(r.contains("价保")))
            .verifyComplete();
    }

    @Test
    void requestInvoice_shouldAcceptApplication() {
        StepVerifier.create(tools.requestInvoice("20260613001", "张三"))
            .assertNext(r -> assertTrue(r.contains("发票") && r.contains("张三")))
            .verifyComplete();
    }
}
