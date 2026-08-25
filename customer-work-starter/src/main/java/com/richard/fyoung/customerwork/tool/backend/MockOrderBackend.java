package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * 订单后端的默认演示实现（内存 Mock）与单元测试。由 {@code ToolBackendConfig} 以
 * {@code @ConditionalOnMissingBean} 注册为默认 Bean；生产提供自定义 {@link OrderBackend} Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public class MockOrderBackend implements OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(MockOrderBackend.class);

    private static final Map<String, MockOrder> MOCK_ORDERS = Map.of(
        "20260613001", new MockOrder("旗舰款无线降噪耳机", new BigDecimal("299.00"),
            OrderStatuses.SHIPPED, "2026-06-10"),
        "20260613002", new MockOrder("商务降噪头戴耳机", new BigDecimal("1599.00"),
            OrderStatuses.RECEIVED, "2026-05-20"),
        "20260613003", new MockOrder("运动防汗蓝牙耳机", new BigDecimal("199.00"),
            OrderStatuses.PENDING_SHIPMENT, "2026-06-13"),
        "20260613004", new MockOrder("旗舰款无线降噪耳机", new BigDecimal("299.00"),
            OrderStatuses.REFUNDED, "2026-06-08"),
        "20260613005", new MockOrder("示例待支付商品", new BigDecimal("399.00"),
            OrderStatuses.PENDING_PAYMENT, "2026-06-14")
    );

    @Override
    public Mono<OrderQueryResult> queryOrder(String orderId) {
        log.info("[MockOrderBackend] query order: {}", orderId);
        return Mono.fromSupplier(() -> {
                MockOrder order = MOCK_ORDERS.get(orderId);
                return order == null
                    ? OrderResultFactory.notFound(orderId)
                    : OrderResultFactory.found(orderId, order.productSummary(), order.amount(),
                        order.status(), order.createdDate());
            })
            .delayElement(Duration.ofMillis(120))
            .onErrorResume(e -> Mono.just(OrderResultFactory.unavailable(orderId)));
    }

    @Override
    public Mono<String> queryLogistics(String orderId) {
        log.info("[MockOrderBackend] 查询物流: {}", orderId);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "订单 " + orderId + " 物流：[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。"
                    : "未查询到订单 " + orderId + " 的物流信息。")
            .delayElement(Duration.ofMillis(100))
            .onErrorResume(e -> Mono.just("物流系统暂时不可用，建议稍后再试。"));
    }

    @Override
    public Mono<LogisticsQueryResult> queryLogisticsFacts(String orderId) {
        log.info("[MockOrderBackend] query structured logistics: {}", orderId);
        return Mono.fromSupplier(() -> {
                if (!MOCK_ORDERS.containsKey(orderId)) {
                    return LogisticsResultFactory.notFound(orderId);
                }
                return LogisticsResultFactory.fromTrace(orderId,
                    "[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]");
            })
            .delayElement(Duration.ofMillis(100))
            .onErrorResume(e -> Mono.just(LogisticsResultFactory.unavailable(orderId)));
    }

    @Override
    public Mono<String> modifyAddress(String orderId, String newAddress) {
        log.info("[MockOrderBackend] 改地址: order={}, addr={}", orderId, newAddress);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "订单 " + orderId + " 收货地址已更新为「" + newAddress + "」，将按新地址派送。"
                    : "未查询到订单 " + orderId + "，无法改址，请核对订单号。")
            .delayElement(Duration.ofMillis(90));
    }

    @Override
    public Mono<String> cancelOrder(String orderId, String reason) {
        log.info("[MockOrderBackend] 取消订单: order={}, reason={}", orderId, reason);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "订单 " + orderId + " 已受理取消申请（原因：" + reason + "）；若已支付，款项原路退回。"
                    : "未查询到订单 " + orderId + "，无法取消，请核对订单号。")
            .delayElement(Duration.ofMillis(90));
    }

    @Override
    public Mono<String> urgeShipment(String orderId) {
        log.info("[MockOrderBackend] 催发货: {}", orderId);
        return Mono.fromSupplier(() ->
                MOCK_ORDERS.containsKey(orderId)
                    ? "已为订单 " + orderId + " 提交加急发货标记，仓库将优先处理，预计 24 小时内出库。"
                    : "未查询到订单 " + orderId + "，请核对订单号。")
            .delayElement(Duration.ofMillis(80));
    }

    private record MockOrder(String productSummary, BigDecimal amount, String status, String createdDate) {
    }
}
