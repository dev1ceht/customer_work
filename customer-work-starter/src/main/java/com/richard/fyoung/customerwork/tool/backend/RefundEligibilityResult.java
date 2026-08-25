package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款资格决策的结构化结果。金额、政策版本和规则证据均由 Backend 给出，Agent 只能消费结果。
 *
 * @author owlzhangfq@gmail.com
 */
public record RefundEligibilityResult(
        Decision decision,
        List<String> reasonCodes,
        BigDecimal refundableAmount,
        RiskLevel riskLevel,
        String policyVersion,
        List<String> ruleIds) {

    public RefundEligibilityResult {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    /** 退款资格决策。 */
    public enum Decision {
        ELIGIBLE,
        HUMAN_REVIEW,
        INELIGIBLE
    }

    /** 首期风险等级；高风险规则在后续安全升级切片扩展。 */
    public enum RiskLevel {
        NORMAL,
        HIGH
    }
}
