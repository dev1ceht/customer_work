package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 售后后端（扩展点）：对接你自己的退款规则引擎 / 工单系统。
 * 默认 {@link MockAfterSalesBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface AfterSalesBackend {

    /** 发布门禁使用的退款 Tool/Backend 结构化契约版本。 */
    String CONTRACT_VERSION = RefundHandlingReleaseVersions.TOOL_CONTRACT_VERSION;

    /** 根据可信订单事实校验退款资格；Agent 不提供资格标记或退款金额。 */
    Mono<RefundEligibilityResult> checkRefundEligibility(String orderId);

    /** 准备退款摘要；用户确认前不得创建申请。 */
    Mono<RefundPreparationResult> prepareRefund(String orderId, String reasonCode, String reasonText);

    /** 使用绑定当前用户、订单、金额和政策版本的确认令牌创建待人工审核申请。 */
    Mono<RefundSubmissionResult> submitRefund(String orderId, String reasonCode,
                                              String reasonText, String confirmationToken);

    /** 查询服务端稳定幂等键对应的退款申请；NOT_FOUND 才表示可以安全考虑同键重试。 */
    Mono<RefundSubmissionResult> queryRefundApplication(String orderId);

    /** 查询退款/退货进度。 */
    Mono<String> queryRefundProgress(String orderId);

    /** 查询结构化退款申请、人工审批和资金执行状态；旧自由文本方法保留给兼容调用方。 */
    default Mono<RefundProgressResult> queryRefundProgressStatus(String orderId) {
        return Mono.just(new RefundProgressResult(RefundProgressResult.QueryStatus.UNKNOWN, orderId,
            null, null, null, null, null, java.util.List.of(),
            "退款进度暂时无法确认，已转人工处理"));
    }

    /** 人工审批通过退款申请；该入口只推进人工决策，不直接执行资金动作。 */
    default Mono<RefundApprovalResult> approveRefund(String approvalId, String operator, String note) {
        return Mono.just(new RefundApprovalResult(RefundApprovalResult.Status.FAILED, approvalId,
            null, null, null, null, null, null, java.util.List.of(), operator, 0L,
            "当前退款后端未提供人工审批绑定能力"));
    }

    /** 人工拒绝退款申请，并释放履约冻结。 */
    default Mono<RefundApprovalResult> denyRefund(String approvalId, String operator, String note) {
        return Mono.just(new RefundApprovalResult(RefundApprovalResult.Status.FAILED, approvalId,
            null, null, null, null, null, null, java.util.List.of(), operator, 0L,
            "当前退款后端未提供人工审批绑定能力"));
    }

    /** 由受控的支付执行服务执行已批准的退款；不是 Agent Tool。 */
    default Mono<RefundExecutionResult> executeRefund(String approvalId) {
        return Mono.just(new RefundExecutionResult(RefundExecutionResult.Status.UNAVAILABLE,
            approvalId, null, null, null, null, null, null, null, null, java.util.List.of(),
            "当前退款后端未提供支付执行能力"));
    }

    /** 对原退款申请做安全恢复；未知结果必须先查询渠道，不创建新申请。 */
    default Mono<RefundExecutionResult> retryRefundExecution(String approvalId) {
        return Mono.just(new RefundExecutionResult(RefundExecutionResult.Status.UNAVAILABLE,
            approvalId, null, null, null, null, null, null, null, null, java.util.List.of(),
            "当前退款后端未提供支付执行恢复能力"));
    }

    /** 提交退货申请（生成退货工单）。 */
    Mono<String> submitReturn(String orderId, String reason);

    /** 提交换货申请（生成换货工单）。 */
    Mono<String> submitExchange(String orderId, String reason, String newSpec);

    /** 校验并申请价保（买后降价补差）。 */
    Mono<String> checkPriceProtection(String orderId);

    /** 申请/重开发票。 */
    Mono<String> requestInvoice(String orderId, String invoiceTitle);
}
