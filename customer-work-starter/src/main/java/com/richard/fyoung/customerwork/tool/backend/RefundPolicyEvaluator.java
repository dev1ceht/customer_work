package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;
import java.util.List;

import static com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult.Decision.ELIGIBLE;
import static com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult.Decision.HUMAN_REVIEW;
import static com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult.Decision.INELIGIBLE;
import static com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult.RiskLevel.NORMAL;

/** Backend 内部退款政策评估器；不作为 Agent Tool 暴露。 */
final class RefundPolicyEvaluator {

    static final String POLICY_VERSION = RefundHandlingReleaseVersions.POLICY_VERSION;

    private static final String RULE_ORDER_PAID = "REFUND-001";
    private static final String RULE_NOT_SHIPPED = "REFUND-002";
    private static final String RULE_TERMINAL_ORDER = "REFUND-003";
    private static final String RULE_DATA_COMPLETE = "REFUND-004";
    private static final String RULE_NO_EFFECTIVE_REFUND = "REFUND-005";
    private static final String RULE_HIGH_RISK_AMOUNT = RefundRiskEvaluator.HIGH_RISK_RULE_ID;
    private static final BigDecimal HIGH_RISK_AMOUNT = new BigDecimal("1000.00");

    RefundEligibilityResult evaluate(String orderStatus, BigDecimal amount) {
        OrderResultFactory.StateFacts facts = OrderResultFactory.stateFacts(orderStatus);
        if (facts.paymentStatus() == OrderQueryResult.PaymentStatus.UNPAID) {
            return result(INELIGIBLE, List.of("ORDER_UNPAID"), null,
                List.of(RULE_ORDER_PAID));
        }
        if (facts.fulfillmentStatus() == OrderQueryResult.FulfillmentStatus.CANCELLED) {
            return result(INELIGIBLE, List.of("ORDER_CANCELLED"), null,
                List.of(RULE_TERMINAL_ORDER));
        }
        if (facts.paymentStatus() == OrderQueryResult.PaymentStatus.REFUNDED) {
            return result(INELIGIBLE, List.of("ORDER_ALREADY_REFUNDED"), null,
                List.of(RULE_TERMINAL_ORDER));
        }
        if (facts.fulfillmentStatus() == OrderQueryResult.FulfillmentStatus.SHIPPED) {
            return result(HUMAN_REVIEW, List.of("ORDER_ALREADY_SHIPPED"), null,
                List.of(RULE_NOT_SHIPPED));
        }
        if (facts.fulfillmentStatus() == OrderQueryResult.FulfillmentStatus.DELIVERED) {
            return result(HUMAN_REVIEW, List.of("ORDER_ALREADY_DELIVERED"), null,
                List.of(RULE_NOT_SHIPPED));
        }
        if (amount == null || amount.signum() <= 0) {
            return result(HUMAN_REVIEW, List.of("REFUNDABLE_AMOUNT_UNAVAILABLE"), null,
                List.of(RULE_DATA_COMPLETE));
        }
        if (facts.paymentStatus() == OrderQueryResult.PaymentStatus.PAID
                && facts.fulfillmentStatus() == OrderQueryResult.FulfillmentStatus.NOT_SHIPPED) {
            if (amount.compareTo(HIGH_RISK_AMOUNT) >= 0) {
                return result(HUMAN_REVIEW, List.of("REFUND_HIGH_RISK_AMOUNT"), null,
                    RefundEligibilityResult.RiskLevel.HIGH, List.of(RULE_HIGH_RISK_AMOUNT));
            }
            return result(ELIGIBLE, List.of("ORDER_PAID", "NOT_SHIPPED"), amount,
                List.of(RULE_ORDER_PAID, RULE_NOT_SHIPPED));
        }
        return result(HUMAN_REVIEW, List.of("ORDER_STATE_UNKNOWN"), null,
            List.of(RULE_DATA_COMPLETE));
    }

    RefundEligibilityResult orderNotFound() {
        return result(INELIGIBLE, List.of("ORDER_NOT_FOUND"), null, List.of(RULE_DATA_COMPLETE));
    }

    RefundEligibilityResult unavailable() {
        return result(HUMAN_REVIEW, List.of("REFUND_POLICY_UNAVAILABLE"), null,
            List.of(RULE_DATA_COMPLETE));
    }

    RefundEligibilityResult existingRefundRequiresReview() {
        return result(HUMAN_REVIEW, List.of("EXISTING_REFUND_REQUIRES_REVIEW"), null,
            List.of(RULE_NO_EFFECTIVE_REFUND));
    }

    private RefundEligibilityResult result(RefundEligibilityResult.Decision decision,
                                            List<String> reasonCodes,
                                            BigDecimal amount,
                                            List<String> ruleIds) {
        return result(decision, reasonCodes, amount, NORMAL, ruleIds);
    }

    private RefundEligibilityResult result(RefundEligibilityResult.Decision decision,
                                            List<String> reasonCodes,
                                            BigDecimal amount,
                                            RefundEligibilityResult.RiskLevel riskLevel,
                                            List<String> ruleIds) {
        return new RefundEligibilityResult(decision, reasonCodes, amount, riskLevel, POLICY_VERSION, ruleIds);
    }
}
