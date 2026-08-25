package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

/** 服务端生成的全额退款业务幂等键；不作为 Agent 入参，按租户和订单稳定推导。 */
public final class RefundIdempotencyKey {

    private RefundIdempotencyKey() {
    }

    public static String fullRefund(String tenantId, String orderId) {
        if (tenantId == null || tenantId.isBlank() || orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("tenant and order are required for refund idempotency");
        }
        return "REFUND:FULL:" + TenantContext.canonicalizeTenantId(tenantId) + ":" + orderId.trim();
    }
}
