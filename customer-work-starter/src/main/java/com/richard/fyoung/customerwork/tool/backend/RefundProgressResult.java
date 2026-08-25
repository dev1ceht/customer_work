package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;

import java.util.List;

/** 退款进度的结构化快照，明确区分申请、人工审批和资金执行状态。 */
public record RefundProgressResult(
    QueryStatus queryStatus,
    String orderId,
    String refundNo,
    RefundApplicationStatus applicationStatus,
    ApprovalStatus approvalStatus,
    ExecutionStatus executionStatus,
    String policyVersion,
    List<String> ruleIds,
    String message,
    String executionReference) {

    public RefundProgressResult(QueryStatus queryStatus, String orderId, String refundNo,
                                RefundApplicationStatus applicationStatus,
                                ApprovalStatus approvalStatus, ExecutionStatus executionStatus,
                                String policyVersion, List<String> ruleIds, String message) {
        this(queryStatus, orderId, refundNo, applicationStatus, approvalStatus, executionStatus,
            policyVersion, ruleIds, message, null);
    }

    public RefundProgressResult {
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public enum QueryStatus {
        FOUND,
        NOT_FOUND,
        UNKNOWN
    }
}
