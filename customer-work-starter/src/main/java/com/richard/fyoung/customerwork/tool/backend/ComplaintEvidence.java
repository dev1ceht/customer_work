package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.routing.TicketPriority;

/** 已经过白名单字段和脱敏处理、允许写入投诉系统的最小证据。 */
public record ComplaintEvidence(
        String orderId,
        String summary,
        String keyFacts,
        String customerRequest,
        String escalationReason,
        TicketPriority priority) {

    public ComplaintEvidence {
        // 证据对象本身也是后端扩展点的输入，不能只依赖 Agent Tool 入口做脱敏。
        orderId = ComplaintEvidenceSanitizer.sanitizeOrderId(orderId);
        summary = ComplaintEvidenceSanitizer.sanitizeText(summary,
            ComplaintEvidenceSanitizer.SUMMARY_LIMIT);
        keyFacts = ComplaintEvidenceSanitizer.sanitizeText(keyFacts,
            ComplaintEvidenceSanitizer.FACTS_LIMIT);
        customerRequest = ComplaintEvidenceSanitizer.sanitizeText(customerRequest,
            ComplaintEvidenceSanitizer.REQUEST_LIMIT);
        escalationReason = ComplaintEvidenceSanitizer.sanitizeText(escalationReason,
            ComplaintEvidenceSanitizer.REASON_LIMIT);
        priority = priority == null ? TicketPriority.MEDIUM : priority;
    }

    /** 只拼接允许落库的字段，不携带原始对话。 */
    public String storageText() {
        StringBuilder text = new StringBuilder();
        append(text, "问题摘要", summary);
        append(text, "关键事实", keyFacts);
        append(text, "用户诉求", customerRequest);
        append(text, "升级原因", escalationReason);
        append(text, "优先级", priority.name());
        return text.toString();
    }

    public static ComplaintEvidence legacy(String orderId, String content) {
        return ComplaintEvidenceSanitizer.sanitize(orderId, content, "", "", "", "MEDIUM");
    }

    private static void append(StringBuilder text, String label, String value) {
        if (!value.isBlank()) {
            if (text.length() > 0) {
                text.append('；');
            }
            text.append(label).append('=').append(value);
        }
    }

}
