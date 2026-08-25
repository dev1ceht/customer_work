package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.safety.security.UserPrincipal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * 退款确认令牌签发与验证。
 *
 * <p>令牌是短期、带签名的服务端声明，绑定租户、已验签用户、订单、全额金额和政策版本。
 * Agent 不能构造或修改这些字段；提交时后端仍会重新查询并重新计算。</p>
 */
public final class RefundConfirmationTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final boolean configured;
    private final Clock clock;
    private final long ttlMs;

    public RefundConfirmationTokenService(String secret) {
        this(secret, Clock.systemUTC(), Duration.ofMinutes(10));
    }

    public RefundConfirmationTokenService(String secret, Clock clock, Duration ttl) {
        if (clock == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("refund confirmation token clock and ttl are required");
        }
        this.configured = secret != null && !secret.isBlank();
        this.secret = this.configured ? secret.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.clock = clock;
        this.ttlMs = ttl.toMillis();
    }

    public String issue(UserPrincipal principal, String orderId, BigDecimal amount, String policyVersion) {
        long now = clock.millis();
        return issue(principal, orderId, amount, policyVersion, now);
    }

    /** 供固定时钟测试使用；生产调用方应使用 {@link #issue(UserPrincipal, String, BigDecimal, String)}。 */
    public String issue(UserPrincipal principal, String orderId, BigDecimal amount,
                        String policyVersion, long issuedAtMs) {
        requireIdentity(principal);
        if (orderId == null || orderId.isBlank() || amount == null || amount.signum() <= 0
            || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("refund confirmation token claims are incomplete");
        }
        if (!configured) {
            throw new IllegalStateException("refund confirmation token secret is not configured");
        }
        long expiresAtMs = Math.addExact(issuedAtMs, ttlMs);
        String nonce = randomNonce();
        String payload = String.join(".", VERSION,
            encode(principal.tenantId()), encode(principal.userId()), encode(orderId),
            encode(amount.stripTrailingZeros().toPlainString()), encode(policyVersion),
            Long.toString(expiresAtMs), encode(nonce));
        return payload + "." + encodeBytes(sign(payload));
    }

    public Verification verify(String token, UserPrincipal principal, String orderId,
                               BigDecimal currentAmount, String policyVersion) {
        if (token == null || token.isBlank()) {
            return Verification.invalid("REFUND_CONFIRMATION_REQUIRED");
        }
        if (!configured) {
            return Verification.invalid("REFUND_CONFIRMATION_UNAVAILABLE");
        }
        if (principal == null || principal.userId() == null || principal.userId().isBlank()
            || principal.tenantId() == null || principal.tenantId().isBlank()) {
            return Verification.invalid("REFUND_IDENTITY_REQUIRED");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 9 || !VERSION.equals(parts[0])) {
            return Verification.invalid("REFUND_CONFIRMATION_TOKEN_INVALID");
        }
        String payload = String.join(".", java.util.Arrays.copyOf(parts, 8));
        byte[] suppliedSignature;
        try {
            suppliedSignature = decodeBytes(parts[8]);
        } catch (IllegalArgumentException e) {
            return Verification.invalid("REFUND_CONFIRMATION_TOKEN_INVALID");
        }
        if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
            return Verification.invalid("REFUND_CONFIRMATION_TOKEN_TAMPERED");
        }
        try {
            String tenantId = decode(parts[1]);
            String userId = decode(parts[2]);
            String boundOrderId = decode(parts[3]);
            BigDecimal boundAmount = new BigDecimal(decode(parts[4]));
            String boundPolicyVersion = decode(parts[5]);
            long expiresAtMs = Long.parseLong(parts[6]);
            if (clock.millis() >= expiresAtMs) {
                return Verification.invalid("REFUND_CONFIRMATION_TOKEN_EXPIRED");
            }
            if (!principal.tenantId().equals(tenantId) || !principal.userId().equals(userId)
                || !java.util.Objects.equals(orderId, boundOrderId)
                || currentAmount == null || currentAmount.compareTo(boundAmount) != 0
                || !java.util.Objects.equals(policyVersion, boundPolicyVersion)) {
                return Verification.invalid("REFUND_CONFIRMATION_CONTEXT_CHANGED");
            }
            return new Verification(true, null,
                new Claims(tenantId, userId, boundOrderId, boundAmount, boundPolicyVersion, expiresAtMs));
        } catch (RuntimeException e) {
            return Verification.invalid("REFUND_CONFIRMATION_TOKEN_INVALID");
        }
    }

    public long nowMs() {
        return clock.millis();
    }

    public long expiresAtMs() {
        return Math.addExact(clock.millis(), ttlMs);
    }

    public boolean isConfigured() {
        return configured;
    }

    private static void requireIdentity(UserPrincipal principal) {
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()
            || principal.userId() == null || principal.userId().isBlank()) {
            throw new IllegalArgumentException("verified refund identity is required");
        }
    }

    private String randomNonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return encodeBytes(bytes);
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign refund confirmation token", e);
        }
    }

    private static String encode(String value) {
        return encodeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeBytes(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String decode(String value) {
        return new String(decodeBytes(value), StandardCharsets.UTF_8);
    }

    private static byte[] decodeBytes(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public record Claims(String tenantId, String userId, String orderId,
                         BigDecimal amount, String policyVersion, long expiresAtMs) {
    }

    public record Verification(boolean valid, String errorCode, Claims claims) {
        static Verification invalid(String errorCode) {
            return new Verification(false, errorCode, null);
        }
    }
}
