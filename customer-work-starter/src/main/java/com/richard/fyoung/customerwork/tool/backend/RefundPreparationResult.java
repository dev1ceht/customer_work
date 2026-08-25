package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户确认前的退款摘要。只有 {@link Status#READY} 才会携带可提交的确认令牌和金额。
 */
public record RefundPreparationResult(
    Status status,
    String orderId,
    String productName,
    BigDecimal refundableAmount,
    String reasonCode,
    RefundEligibilityResult.RiskLevel riskLevel,
    String policyVersion,
    List<String> reasonCodes,
    List<String> ruleIds,
    String confirmationToken,
    long confirmationExpiresAtMs,
    boolean fulfillmentFreezeRequired,
    boolean manualApprovalRequired) {

    public RefundPreparationResult {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public enum Status {
        READY,
        HUMAN_REVIEW,
        INELIGIBLE,
        FAILED
    }
}
