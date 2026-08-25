package com.richard.fyoung.customerwork.tool.backend;

import java.util.Locale;

/** 退款高风险信号识别；只负责升级人工，不负责替代资格或金额裁决。 */
final class RefundRiskEvaluator {

    static final String HIGH_RISK_RULE_ID = "REFUND-006";
    static final String UNTRUSTED_REASON_RULE_ID = "REFUND-007";

    private static final String[] INSTRUCTION_SIGNALS = {
        "忽略", "系统指令", "提示词", "管理员", "客服主管", "直接打款", "绕过审核",
        "跳过确认", "授权", "tool", "function", "system prompt", "ignore previous"
    };

    private RefundRiskEvaluator() {
    }

    static boolean reasonRequiresHumanReview(String reasonText) {
        if (reasonText == null || reasonText.isBlank()) {
            return false;
        }
        String normalized = reasonText.toLowerCase(Locale.ROOT);
        for (String signal : INSTRUCTION_SIGNALS) {
            if (normalized.contains(signal.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
