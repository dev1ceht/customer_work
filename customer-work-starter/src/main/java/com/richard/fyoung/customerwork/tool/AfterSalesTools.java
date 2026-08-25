package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult;
import com.richard.fyoung.customerwork.tool.backend.RefundInvocationContext;
import com.richard.fyoung.customerwork.tool.backend.RefundPreparationResult;
import com.richard.fyoung.customerwork.tool.backend.RefundProgressResult;
import com.richard.fyoung.customerwork.tool.backend.RefundSubmissionResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * 售后/退款工具组。退款动作严格分成摘要准备和确认提交两步；工具层不接受金额，
 * 也不自行写审批记录，所有身份、资格、幂等和事务规则由 Backend 执行。
 *
 * @author owlzhangfq@gmail.com
 */
public class AfterSalesTools {

    private final AfterSalesBackend backend;
    /** 仅用于审批审计的服务端会话关联，不参与身份判断。 */
    private final String sessionId;

    public AfterSalesTools(AfterSalesBackend backend) {
        this(backend, null);
    }

    public AfterSalesTools(AfterSalesBackend backend, String sessionId) {
        this.backend = backend;
        this.sessionId = sessionId;
    }

    @Tool(description = "由退款 Backend 根据可信订单事实计算仅退款资格、全部可退余额和规则证据。发起退款前必须先调用。",
          readOnly = true)
    public Mono<RefundEligibilityResult> checkRefundEligibility(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return ToolReadRetry.once(backend.checkRefundEligibility(orderId), "checkRefundEligibility");
    }

    @Tool(description = "根据可信订单事实准备退款摘要。必须先展示全部可退金额、商品、履约冻结和人工审批说明，再请求用户明确确认。",
          readOnly = true)
    public Mono<RefundPreparationResult> prepareRefund(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reasonCode", description = "标准退款原因码：CHANGED_MIND、ORDERED_BY_MISTAKE、DUPLICATE_ORDER、ORDER_INFO_ERROR、OTHER")
            String reasonCode,
            @ToolParam(name = "reasonText", description = "退款原因说明，最多 500 个字符")
            String reasonText) {
        return withInvocationContext(() -> ToolReadRetry.once(
            backend.prepareRefund(orderId, reasonCode, reasonText), "prepareRefund"));
    }

    @Tool(description = "在用户明确确认退款摘要后，使用确认令牌创建待人工审核退款申请。金额由 Backend 重新计算；本工具不直接打款。")
    public Mono<RefundSubmissionResult> submitRefund(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reasonCode", description = "标准退款原因码：CHANGED_MIND、ORDERED_BY_MISTAKE、DUPLICATE_ORDER、ORDER_INFO_ERROR、OTHER")
            String reasonCode,
            @ToolParam(name = "reasonText", description = "退款原因说明，最多 500 个字符，仅供人工审核参考")
            String reasonText,
            @ToolParam(name = "confirmationToken", description = "用户确认退款摘要后由 Backend 签发的短期确认令牌")
            String confirmationToken) {
        return withInvocationContext(() -> backend.submitRefund(
            orderId, reasonCode, reasonText, confirmationToken));
    }

    @Tool(description = "按服务端稳定幂等键查询退款申请结果。提交结果未知时必须先调用；只有 NOT_FOUND 才可考虑同一订单重试。",
          readOnly = true)
    public Mono<RefundSubmissionResult> queryRefundApplication(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return withInvocationContext(() -> ToolReadRetry.once(
            backend.queryRefundApplication(orderId), "queryRefundApplication"));
    }

    @Tool(description = "结构化查询退款申请、人工审批和资金执行进度。审批通过不等于已退款，执行成功也不等于已经到账。",
          readOnly = true)
    public Mono<RefundProgressResult> queryRefundProgress(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return ToolReadRetry.once(backend.queryRefundProgressStatus(orderId), "queryRefundProgress");
    }

    @Tool(description = "对订单发起退货申请，生成退货工单并告知回寄流程。用户明确要求退货（退款并寄回商品）时调用。")
    public Mono<String> submitReturn(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "退货原因")
            String reason) {
        return backend.submitReturn(orderId, reason);
    }

    @Tool(description = "对订单发起换货申请（换规格/颜色/尺码等）。用户要求换货而非退款时调用。")
    public Mono<String> submitExchange(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "reason", description = "换货原因")
            String reason,
            @ToolParam(name = "newSpec", description = "要换成的规格/颜色/尺码")
            String newSpec) {
        return backend.submitExchange(orderId, reason, newSpec);
    }

    @Tool(description = "校验并申请价保（买后降价补差）。用户说'买完降价了''能退差价吗''价保'时调用。")
    public Mono<String> checkPriceProtection(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId) {
        return backend.checkPriceProtection(orderId);
    }

    @Tool(description = "为订单申请或重开发票。用户要求开发票/重开发票时调用，需提供发票抬头。")
    public Mono<String> requestInvoice(
            @ToolParam(name = "orderId", description = "订单号")
            String orderId,
            @ToolParam(name = "invoiceTitle", description = "发票抬头（个人姓名或公司名称）")
            String invoiceTitle) {
        return backend.requestInvoice(orderId, invoiceTitle);
    }

    private <T> Mono<T> withInvocationContext(Supplier<Mono<T>> action) {
        return Mono.defer(() -> {
            String previous = RefundInvocationContext.get();
            RefundInvocationContext.set(sessionId);
            return action.get().doFinally(signal -> {
                if (previous == null) {
                    RefundInvocationContext.clear();
                } else {
                    RefundInvocationContext.set(previous);
                }
            });
        }).contextWrite(context -> sessionId == null || sessionId.isBlank()
            ? context : context.put(RefundInvocationContext.KEY, sessionId));
    }
}
