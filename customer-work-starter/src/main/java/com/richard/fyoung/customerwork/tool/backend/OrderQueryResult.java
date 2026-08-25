package com.richard.fyoung.customerwork.tool.backend;

import java.math.BigDecimal;
import java.util.List;

/**
 * Agent 可见的结构化订单事实，明确分离支付状态与履约状态。
 *
 * @author owlzhangfq@gmail.com
 */
public record OrderQueryResult(
        QueryStatus queryStatus,
        String orderId,
        PaymentStatus paymentStatus,
        FulfillmentStatus fulfillmentStatus,
        String productSummary,
        BigDecimal orderAmount,
        String createdDate,
        List<String> reasonCodes) {

    public OrderQueryResult {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public enum QueryStatus {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    public enum PaymentStatus {
        UNPAID,
        PAID,
        REFUNDED,
        UNKNOWN
    }

    public enum FulfillmentStatus {
        NOT_SHIPPED,
        SHIPPED,
        DELIVERED,
        CANCELLED,
        CLOSED,
        UNKNOWN
    }
}
