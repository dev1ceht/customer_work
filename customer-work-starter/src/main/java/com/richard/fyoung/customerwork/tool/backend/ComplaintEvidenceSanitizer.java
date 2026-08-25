package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.routing.TicketPriority;

import java.util.regex.Pattern;

/**
 * 投诉写入前的确定性白名单/脱敏层。它是防御性校验，不替代数据库权限和审计控制。
 */
public final class ComplaintEvidenceSanitizer {

    static final int ORDER_ID_LIMIT = 64;
    static final int SUMMARY_LIMIT = 300;
    static final int FACTS_LIMIT = 500;
    static final int REQUEST_LIMIT = 240;
    static final int REASON_LIMIT = 240;

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern PAYMENT_NUMBER = Pattern.compile("(?<!\\d)\\d{12,19}(?!\\d)");
    private static final Pattern ADDRESS = Pattern.compile(
        "(地址|收货地址|住址)\\s*[:：][^，,。；;\\r\\n]{0,80}");

    private ComplaintEvidenceSanitizer() {
    }

    public static ComplaintEvidence sanitize(String orderId, String summary, String keyFacts,
                                             String customerRequest, String escalationReason,
                                             String priority) {
        return new ComplaintEvidence(
            sanitizeOrderId(orderId),
            sanitizeText(summary, SUMMARY_LIMIT),
            sanitizeText(keyFacts, FACTS_LIMIT),
            sanitizeText(customerRequest, REQUEST_LIMIT),
            sanitizeText(escalationReason, REASON_LIMIT),
            TicketPriority.fromName(priority));
    }

    public static String sanitizeText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
            .replaceAll("[\\r\\n\\t]+", " ")
            .trim();
        sanitized = ID_CARD.matcher(sanitized).replaceAll("[已脱敏]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[已脱敏]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[已脱敏]");
        sanitized = PAYMENT_NUMBER.matcher(sanitized).replaceAll("[已脱敏]");
        sanitized = ADDRESS.matcher(sanitized).replaceAll("$1:[已脱敏]");
        return truncate(sanitized, limit);
    }

    static String sanitizeOrderId(String value) {
        String sanitized = sanitizeText(value, ORDER_ID_LIMIT);
        return sanitized.isBlank() ? "无" : sanitized;
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
