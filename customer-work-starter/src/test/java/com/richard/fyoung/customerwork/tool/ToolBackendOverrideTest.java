package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult;
import com.richard.fyoung.customerwork.tool.backend.OrderQueryResult;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证扩展点：使用者自定义后端可被工具壳直接驱动；ToolRegistrar 用自定义后端时工具组完整注册。
 * @author owlzhangfq@gmail.com
 */
class ToolBackendOverrideTest {

    /** 模拟使用者接入自有订单系统的实现。 */
    static class CustomOrderBackend implements OrderBackend {
        @Override
        public Mono<OrderQueryResult> queryOrder(String orderId) {
            return Mono.just(new OrderQueryResult(OrderQueryResult.QueryStatus.FOUND, orderId,
                OrderQueryResult.PaymentStatus.PAID, OrderQueryResult.FulfillmentStatus.NOT_SHIPPED,
                "CUSTOM", new BigDecimal("1.00"), "2026-01-01", List.of()));
        }

        @Override
        public Mono<String> queryLogistics(String orderId) {
            return Mono.just("CUSTOM:logistics=" + orderId);
        }
    }

    @Test
    void orderTools_shouldUseCustomBackend() {
        OrderTools tools = new OrderTools(new CustomOrderBackend());
        assertEquals("X1", tools.queryOrder("X1").block().orderId());
        LogisticsQueryResult logistics = tools.queryLogistics("X1").block();
        assertEquals(LogisticsQueryResult.QueryStatus.FOUND, logistics.queryStatus());
        assertTrue(logistics.reasonCodes().contains("LOGISTICS_LEGACY_TEXT"));
        assertTrue(logistics.events().get(0).description().contains("CUSTOM:logistics=X1"));
    }

    @Test
    void toolRegistrar_shouldRegisterAllBusinessTools_withCustomBackend() {
        Toolkit toolkit = new Toolkit();
        new ToolRegistrar(
            new CustomOrderBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockProductBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockMemberBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend(),
            new com.richard.fyoung.customerwork.capability.handoff.HandoffService(),
            null)
            .registerBusinessTools(toolkit);

        Set<String> names = toolkit.getToolNames();
        assertTrue(names.contains("queryOrder") && names.contains("submitRefund")
            && names.contains("searchKnowledge") && names.contains("transferToHuman")
            && names.contains("recommendProducts") && names.contains("queryRefundProgress")
            && names.contains("cancelOrder") && names.contains("queryPoints")
            && names.contains("fileComplaint"),
            "应注册全部业务工具（售前/订单/售后/会员/投诉/人工）: " + names);
    }
}
