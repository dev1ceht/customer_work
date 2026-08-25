package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;

import java.util.List;

/** 退款资金执行结果；申请、人工审批和渠道执行状态分别保留。 */
public record RefundExecutionResult(
    Status status,
    String approvalId,
    String orderId,
    String refundNo,
    RefundApplicationStatus applicationStatus,
    ApprovalStatus approvalStatus,
    ExecutionStatus executionStatus,
    String idempotencyKey,
    String executionReference,
    String policyVersion,
    List<String> ruleIds,
    String message) {

    public RefundExecutionResult {
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public enum Status {
        EXECUTED,
        EXECUTION_FAILED,
        UNKNOWN,
        CONFLICT,
        NOT_FOUND,
        UNAVAILABLE
    }
}
