package com.richard.fyoung.customerwork.tool.backend;

import java.util.function.Supplier;

/**
 * 可信的 Agent 调用关联上下文，仅用于审批记录的 session correlation，不参与身份判断。
 */
public final class RefundInvocationContext {

    public static final String KEY = "customer-work.refund-invocation";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RefundInvocationContext() {
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(sessionId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWith(String sessionId, Supplier<T> action) {
        String previous = CURRENT.get();
        set(sessionId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
