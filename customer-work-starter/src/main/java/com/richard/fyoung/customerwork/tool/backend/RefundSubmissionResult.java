package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;
import java.util.List;

/** 用户明确确认后，退款后端返回的结构化申请结果。 */
public record RefundSubmissionResult(
    Status status,
    String orderId,
    String refundNo,
    String approvalId,
    RefundApplicationStatus applicationStatus,
    BigDecimal refundableAmount,
    RefundEligibilityResult.RiskLevel riskLevel,
    String policyVersion,
    List<String> reasonCodes,
    List<String> ruleIds,
    String message,
    String idempotencyKey) {

    /** 兼容任务 02 的构造方式；生产写链路会填充服务端生成的稳定幂等标识。 */
    public RefundSubmissionResult(Status status, String orderId, String refundNo, String approvalId,
                                  RefundApplicationStatus applicationStatus, BigDecimal refundableAmount,
                                  RefundEligibilityResult.RiskLevel riskLevel, String policyVersion,
                                  List<String> reasonCodes, List<String> ruleIds, String message) {
        this(status, orderId, refundNo, approvalId, applicationStatus, refundableAmount, riskLevel,
            policyVersion, reasonCodes, ruleIds, message, null);
    }

    public RefundSubmissionResult {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public enum Status {
        CREATED,
        EXISTING,
        NOT_FOUND,
        HUMAN_REVIEW,
        FAILED,
        UNKNOWN
    }
}
