package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult;
import com.richard.fyoung.customerwork.tool.backend.OrderQueryResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 订单工具组（Tool Group「订单组」）。
 *
 * <p>本类是暴露给 LLM 的工具 Schema 壳，业务实现委托给可替换的 {@link OrderBackend}：
 * 默认走内存 Mock，生产提供自定义 {@code OrderBackend} Bean 即接入真实订单 / 物流系统。</p>
 * @author owlzhangfq@gmail.com
 */
public class OrderTools {

    private final OrderBackend backend;

    public OrderTools(OrderBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "根据订单号查询结构化订单事实，分别返回支付状态、履约状态、商品摘要、金额和创建日期。",
          readOnly = true)
    public Mono<OrderQueryResult> queryOrder(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        return ToolReadRetry.once(backend.queryOrder(orderId), "queryOrder");
    }

    @Tool(description = "查询订单的结构化物流事实，返回物流状态、轨迹事件、最近事件时间、承诺时效依据和信息新鲜度。信息未知时不得自行推测。",
          readOnly = true)
    public Mono<LogisticsQueryResult> queryLogistics(
            @ToolParam(name = "orderId", description = "订单号，例如 '20260613001'")
            String orderId) {
        return ToolReadRetry.once(backend.queryLogisticsFacts(orderId), "queryLogistics");
    }

    @Tool(description = "修改订单的收货地址（售中）。用户在发货前要求改地址时调用。")
    public Mono<String> modifyAddress(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "newAddress", description = "新的完整收货地址")
            String newAddress) {
        return backend.modifyAddress(orderId, newAddress);
    }

    @Tool(description = "取消订单（售中）。用户在发货前要求取消订单时调用。")
    public Mono<String> cancelOrder(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "取消原因")
            String reason) {
        return backend.cancelOrder(orderId, reason);
    }

    @Tool(description = "催发货（售中）。用户催促'怎么还不发货''能不能快点发'时调用。")
    public Mono<String> urgeShipment(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return backend.urgeShipment(orderId);
    }
}
