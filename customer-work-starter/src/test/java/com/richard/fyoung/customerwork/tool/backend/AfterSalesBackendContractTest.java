package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Mock 与 MyBatis 适配器共同遵守的退款资格公共契约。 */
class AfterSalesBackendContractTest {

    @Test
    void implementations_shouldReturnSameDecisionEvidence_forPaidNotShippedOrder() {
        assertParity("20260613003", order("20260613003", OrderStatuses.PENDING_SHIPMENT, "199.00"));
    }

    @Test
    void implementations_shouldReturnSameDecisionEvidence_forShippedOrder() {
        assertParity("20260613001", order("20260613001", OrderStatuses.SHIPPED, "299.00"));
    }

    @Test
    void implementations_shouldRejectUnpaidOrder_withExplicitReason() {
        RefundEligibilityResult result = assertParity(
            "20260613005", order("20260613005", OrderStatuses.PENDING_PAYMENT, "399.00"));

        assertEquals(RefundEligibilityResult.Decision.INELIGIBLE, result.decision());
        assertTrue(result.reasonCodes().contains("ORDER_UNPAID"));
    }

    @Test
    void implementations_shouldReturnSameDecisionEvidence_forMissingOrder() {
        assertParity("missing-order", null);
    }

    @Test
    void implementations_shouldRequireHumanReview_whenAnEffectiveRefundAlreadyExists() {
        RefundEligibilityResult result = assertParity(
            "20260613006", order("20260613006", OrderStatuses.PENDING_SHIPMENT, "499.00"), 1);

        assertEquals(RefundEligibilityResult.Decision.HUMAN_REVIEW, result.decision());
        assertTrue(result.reasonCodes().contains("EXISTING_REFUND_REQUIRES_REVIEW"));
        assertNull(result.refundableAmount(),
            "首期不计算部分退款，不能向 Agent 暴露可能错误的剩余金额");
    }

    @Test
    void eligibilityConsultation_shouldNotCreateRefundApprovalOrInvoiceRecords() {
        String orderId = "read-only-order";
        OrderMapper orderMapper = mock(OrderMapper.class);
        RefundMapper refundMapper = mock(RefundMapper.class);
        InvoiceRequestMapper invoiceMapper = mock(InvoiceRequestMapper.class);
        when(orderMapper.selectById(orderId)).thenReturn(
            order(orderId, OrderStatuses.PENDING_SHIPMENT, "199.00"));

        RefundEligibilityResult result = new MybatisAfterSalesBackend(
            refundMapper, invoiceMapper, orderMapper).checkRefundEligibility(orderId).block();

        assertEquals(RefundEligibilityResult.Decision.ELIGIBLE, result.decision());
        verify(refundMapper).countEffectiveRefunds(orderId, "REFUND");
        verifyNoMoreInteractions(refundMapper);
        verifyNoInteractions(invoiceMapper);
    }

    @Test
    void humanReviewDecisions_shouldNotExposeUnconfirmedRefundableAmount() {
        RefundPolicyEvaluator evaluator = new RefundPolicyEvaluator();

        RefundEligibilityResult unknownState = evaluator.evaluate("UNEXPECTED", new BigDecimal("199.00"));
        RefundEligibilityResult invalidAmount = evaluator.evaluate(OrderStatuses.PENDING_SHIPMENT, BigDecimal.ZERO);

        assertEquals(RefundEligibilityResult.Decision.HUMAN_REVIEW, unknownState.decision());
        assertNull(unknownState.refundableAmount());
        assertEquals(RefundEligibilityResult.Decision.HUMAN_REVIEW, invalidAmount.decision());
        assertNull(invalidAmount.refundableAmount());
    }

    private RefundEligibilityResult assertParity(String orderId, OrderDO order) {
        return assertParity(orderId, order, 0);
    }

    private RefundEligibilityResult assertParity(String orderId, OrderDO order,
                                                  long effectiveRefundCount) {
        RefundEligibilityResult mockResult = new MockAfterSalesBackend()
            .checkRefundEligibility(orderId).block();
        RefundEligibilityResult mybatisResult = mybatisBackend(orderId, order, effectiveRefundCount)
            .checkRefundEligibility(orderId).block();
        assertEquals(mybatisResult, mockResult);
        return mockResult;
    }

    private AfterSalesBackend mybatisBackend(String orderId, OrderDO order,
                                             long effectiveRefundCount) {
        OrderMapper orderMapper = mock(OrderMapper.class);
        RefundMapper refundMapper = mock(RefundMapper.class);
        when(orderMapper.selectById(orderId)).thenReturn(order);
        when(refundMapper.countEffectiveRefunds(orderId, "REFUND"))
            .thenReturn(effectiveRefundCount);
        return new MybatisAfterSalesBackend(
            refundMapper, mock(InvoiceRequestMapper.class), orderMapper);
    }

    private OrderDO order(String orderId, String status, String amount) {
        OrderDO order = new OrderDO();
        order.setOrderId(orderId);
        order.setStatus(status);
        order.setAmount(new BigDecimal(amount));
        return order;
    }
}
