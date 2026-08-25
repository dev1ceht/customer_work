package com.richard.fyoung.customerwork.tool.backend;

import java.util.Optional;

/** 退款原因的边界校验；不把用户文本解释成业务规则。 */
final class RefundReasonValidator {

    static final int MAX_REASON_TEXT_LENGTH = 500;

    private RefundReasonValidator() {
    }

    static Validation validate(String reasonCode, String reasonText) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return Validation.invalid("REASON_CODE_REQUIRED");
        }
        Optional<RefundReasonCode> parsed = parse(reasonCode);
        if (parsed.isEmpty()) {
            return Validation.invalid("REASON_CODE_NOT_ALLOWED");
        }
        if (reasonText == null || reasonText.isBlank()) {
            return Validation.invalid("REASON_TEXT_REQUIRED");
        }
        String normalizedText = reasonText.trim();
        if (normalizedText.length() > MAX_REASON_TEXT_LENGTH) {
            return Validation.invalid("REASON_TEXT_TOO_LONG");
        }
        return new Validation(true, parsed.get(), normalizedText, null);
    }

    private static Optional<RefundReasonCode> parse(String reasonCode) {
        try {
            return Optional.of(RefundReasonCode.valueOf(reasonCode.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    record Validation(boolean valid, RefundReasonCode code, String text, String errorCode) {
        static Validation invalid(String errorCode) {
            return new Validation(false, null, null, errorCode);
        }
    }
}
