package com.richard.fyoung.customerwork.safety.security;

import java.util.function.Supplier;

/**
 * 当前请求中已经验签的终端用户主体。
 *
 * <p>退款后端只能从这里读取 userId/tenantId；退款命令、原因文本、订单会话和模型输出都不是身份来源。</p>
 */
public final class UserIdentityContext {

    public static final String KEY = "customer-work.user-principal";
    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private UserIdentityContext() {
    }

    public static UserPrincipal get() {
        return CURRENT.get();
    }

    public static UserPrincipal require() {
        UserPrincipal principal = CURRENT.get();
        if (principal == null || principal.userId() == null || principal.userId().isBlank()
            || principal.tenantId() == null || principal.tenantId().isBlank()) {
            throw new IllegalStateException("verified user identity is missing");
        }
        return principal;
    }

    public static void set(UserPrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWith(UserPrincipal principal, Supplier<T> action) {
        UserPrincipal previous = CURRENT.get();
        set(principal);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }
}
