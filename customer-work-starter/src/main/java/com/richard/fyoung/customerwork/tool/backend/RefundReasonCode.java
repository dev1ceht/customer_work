package com.richard.fyoung.customerwork.tool.backend;

/**
 * 退款申请允许使用的标准原因码。
 *
 * <p>原因文本仍然是不可信用户输入；它只用于人工审核说明，不能改变资格或退款金额。</p>
 */
public enum RefundReasonCode {
    CHANGED_MIND,
    ORDERED_BY_MISTAKE,
    DUPLICATE_ORDER,
    ORDER_INFO_ERROR,
    OTHER
}
