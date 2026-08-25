package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;

import java.util.List;

/** 退款人工审批的结构化结果；人工决策与资金执行状态分开表达。 */
public record RefundApprovalResult(
    Status status,
    String approvalId,
    String orderId,
    String refundNo,
    RefundApplicationStatus applicationStatus,
    ApprovalStatus approvalStatus,
    ExecutionStatus executionStatus,
    String policyVersion,
    List<String> ruleIds,
    String operator,
    long decidedAtMs,
    String message) {

    public RefundApprovalResult {
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public enum Status {
        APPROVED,
        REJECTED,
        EXISTING,
        NOT_FOUND,
        CONFLICT,
        FAILED
    }
}
