package com.richard.fyoung.customerwork.tool.backend;

/** 退款申请与资金执行生命周期。 */
public enum RefundApplicationStatus {
    PENDING_REVIEW,
    REJECTED,
    APPROVED_PENDING_EXECUTION,
    EXECUTING,
    EXECUTED,
    EXECUTION_FAILED;

    /** 这些状态仍占用订单的全额退款资格，防止并发重复申请。 */
    public boolean isActive() {
        return this != REJECTED;
    }
}
