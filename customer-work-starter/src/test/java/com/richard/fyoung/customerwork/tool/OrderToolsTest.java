package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult;
import com.richard.fyoung.customerwork.tool.backend.OrderQueryResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单工具组单测：工具壳委托默认 Mock 后端，验证响应式逻辑（无需 API Key）。
 * @author owlzhangfq@gmail.com
 */
class OrderToolsTest {

    private final OrderTools tools = new OrderTools(new MockOrderBackend());

    @Test
    void queryOrder_shouldSeparatePaymentAndFulfillmentStatus_forKnownOrder() {
        StepVerifier.create(tools.queryOrder("20260613003"))
            .assertNext(result -> {
                assertEquals(OrderQueryResult.QueryStatus.FOUND, result.queryStatus());
                assertEquals(OrderQueryResult.PaymentStatus.PAID, result.paymentStatus());
                assertEquals(OrderQueryResult.FulfillmentStatus.NOT_SHIPPED, result.fulfillmentStatus());
                assertEquals("运动防汗蓝牙耳机", result.productSummary());
                assertEquals(new BigDecimal("199.00"), result.orderAmount());
            })
            .verifyComplete();
    }

    @Test
    void queryOrder_shouldHandleUnknownOrder() {
        StepVerifier.create(tools.queryOrder("不存在的订单"))
            .assertNext(result -> {
                assertEquals(OrderQueryResult.QueryStatus.NOT_FOUND, result.queryStatus());
                assertTrue(result.reasonCodes().contains("ORDER_NOT_FOUND"));
            })
            .verifyComplete();
    }

    @Test
    void queryLogistics_shouldReturnTrace_forKnownOrder() {
        StepVerifier.create(tools.queryLogistics("20260613001"))
            .assertNext(result -> {
                assertEquals(LogisticsQueryResult.QueryStatus.FOUND, result.queryStatus());
                assertEquals(LogisticsQueryResult.LogisticsStatus.OUT_FOR_DELIVERY, result.logisticsStatus());
                assertEquals(LogisticsQueryResult.FreshnessStatus.UNKNOWN, result.freshnessStatus());
                assertTrue(result.events().stream().anyMatch(event -> event.description().contains("派送中")));
            })
            .verifyComplete();
    }

    @Test
    void queryLogistics_shouldHandleUnknownOrder() {
        StepVerifier.create(tools.queryLogistics("9999"))
            .assertNext(result -> {
                assertEquals(LogisticsQueryResult.QueryStatus.NOT_FOUND, result.queryStatus());
                assertTrue(result.reasonCodes().contains("LOGISTICS_NOT_FOUND"));
            })
            .verifyComplete();
    }

    @Test
    void modifyAddress_shouldUpdateForKnownOrder() {
        StepVerifier.create(tools.modifyAddress("20260613001", "上海市浦东新区xx路1号"))
            .assertNext(r -> assertTrue(r.contains("地址已更新")))
            .verifyComplete();
    }

    @Test
    void cancelOrder_shouldAcceptForKnownOrder() {
        StepVerifier.create(tools.cancelOrder("20260613001", "不想要了"))
            .assertNext(r -> assertTrue(r.contains("取消")))
            .verifyComplete();
    }

    @Test
    void urgeShipment_shouldMarkExpedite_forKnownOrder() {
        StepVerifier.create(tools.urgeShipment("20260613001"))
            .assertNext(r -> assertTrue(r.contains("加急") || r.contains("优先")))
            .verifyComplete();
    }
}
