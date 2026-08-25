package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;

import java.math.BigDecimal;
import java.util.List;

import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.FulfillmentStatus.CANCELLED;
import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.FulfillmentStatus.CLOSED;
import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.FulfillmentStatus.DELIVERED;
import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.FulfillmentStatus.NOT_SHIPPED;
import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.FulfillmentStatus.SHIPPED;
import static com.richard.fyoung.customerwork.tool.backend.OrderQueryResult.QueryStatus.FOUND;

/** 将现有单列订单状态保守映射为支付与履约两个 Agent 可见事实。 */
final class OrderResultFactory {

    private OrderResultFactory() {
    }

    static OrderQueryResult found(String orderId, String productSummary, BigDecimal amount,
                                  String orderStatus, String createdDate) {
        StateFacts facts = stateFacts(orderStatus);
        return new OrderQueryResult(FOUND, orderId, facts.paymentStatus(), facts.fulfillmentStatus(),
            productSummary, amount, createdDate, facts.reasonCodes());
    }

    static StateFacts stateFacts(String orderStatus) {
        OrderQueryResult.PaymentStatus paymentStatus;
        OrderQueryResult.FulfillmentStatus fulfillmentStatus;
        List<String> reasonCodes = List.of();
        if (OrderStatuses.PENDING_PAYMENT.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.UNPAID;
            fulfillmentStatus = NOT_SHIPPED;
        } else if (OrderStatuses.PAID.equals(orderStatus)
                || OrderStatuses.PENDING_SHIPMENT.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.PAID;
            fulfillmentStatus = NOT_SHIPPED;
        } else if (OrderStatuses.SHIPPED.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.PAID;
            fulfillmentStatus = SHIPPED;
        } else if (OrderStatuses.RECEIVED.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.PAID;
            fulfillmentStatus = DELIVERED;
        } else if (OrderStatuses.CANCELLED.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.UNKNOWN;
            fulfillmentStatus = CANCELLED;
        } else if (OrderStatuses.REFUNDED.equals(orderStatus)) {
            paymentStatus = OrderQueryResult.PaymentStatus.REFUNDED;
            fulfillmentStatus = CLOSED;
        } else {
            paymentStatus = OrderQueryResult.PaymentStatus.UNKNOWN;
            fulfillmentStatus = OrderQueryResult.FulfillmentStatus.UNKNOWN;
            reasonCodes = List.of("ORDER_STATE_UNKNOWN");
        }
        return new StateFacts(paymentStatus, fulfillmentStatus, reasonCodes);
    }

    static OrderQueryResult notFound(String orderId) {
        return new OrderQueryResult(OrderQueryResult.QueryStatus.NOT_FOUND, orderId,
            OrderQueryResult.PaymentStatus.UNKNOWN, OrderQueryResult.FulfillmentStatus.UNKNOWN,
            null, null, null, List.of("ORDER_NOT_FOUND"));
    }

    static OrderQueryResult unavailable(String orderId) {
        return new OrderQueryResult(OrderQueryResult.QueryStatus.UNAVAILABLE, orderId,
            OrderQueryResult.PaymentStatus.UNKNOWN, OrderQueryResult.FulfillmentStatus.UNKNOWN,
            null, null, null, List.of("ORDER_SYSTEM_UNAVAILABLE"));
    }

    record StateFacts(OrderQueryResult.PaymentStatus paymentStatus,
                      OrderQueryResult.FulfillmentStatus fulfillmentStatus,
                      List<String> reasonCodes) {
    }
}
