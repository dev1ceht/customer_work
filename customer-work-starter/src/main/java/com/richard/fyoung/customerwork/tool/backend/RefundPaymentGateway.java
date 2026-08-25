package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;

/**
 * 退款支付渠道适配器。它只负责向外部渠道发起/查询动作，不负责修改本地退款状态。
 *
 * <p>重复调用必须使用 {@link RefundPaymentRequest#idempotencyKey()} 去重；渠道超时返回
 * {@link RefundPaymentResult.Status#UNKNOWN}，调用方必须先查询再决定是否重试。</p>
 */
public interface RefundPaymentGateway {

    RefundPaymentResult execute(RefundPaymentRequest request) throws Exception;

    /** 查询同一业务幂等键在渠道侧的受理结果；默认实现表示暂时无法确认。 */
    default RefundPaymentResult query(String idempotencyKey) throws Exception {
        return new RefundPaymentResult(RefundPaymentResult.Status.UNKNOWN, null,
            "支付渠道结果暂时无法确认");
    }

    /** 未配置真实渠道时的安全失败实现，绝不把模拟结果标成已执行。 */
    static RefundPaymentGateway unavailable() {
        return request -> new RefundPaymentResult(RefundPaymentResult.Status.UNAVAILABLE, null,
            "退款支付渠道未配置");
    }

    record RefundPaymentRequest(String refundNo, String orderId, BigDecimal amount,
                                String idempotencyKey) {
    }

    record RefundPaymentResult(Status status, String reference, String message) {
        public enum Status {
            ACCEPTED,
            FAILED,
            UNKNOWN,
            NOT_FOUND,
            UNAVAILABLE
        }
    }
}
