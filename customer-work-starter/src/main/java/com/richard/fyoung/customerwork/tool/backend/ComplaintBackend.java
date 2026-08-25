package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 投诉工单后端（扩展点）：对接你自己的工单 / 客诉系统。
 * 默认 {@link MockComplaintBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface ComplaintBackend {

    /**
     * 投诉单号前缀：属于本 SPI 的输出契约而不是某个实现的内部细节——
     * 调用方按这个前缀识别单号类型，Mock 与落库两个实现必须一致。
     */
    String ID_PREFIX = "CP";

    /** 创建投诉工单，返回工单号与受理说明。 */
    Mono<String> fileComplaint(String orderId, String content);

    /**
     * 使用最小化投诉证据创建结构化工单结果。旧实现可只实现文本方法，适配层会保守降级。
     */
    default Mono<ComplaintTicketResult> fileComplaintStructured(String orderId, ComplaintEvidence evidence) {
        if (evidence == null || evidence.summary().isBlank()) {
            return Mono.just(ComplaintTicketResult.rejectedEvidence(
                evidence == null ? null : evidence.priority(), "COMPLAINT_SUMMARY_MISSING"));
        }
        return fileComplaint(orderId, evidence.storageText())
            .map(response -> ComplaintResultFactory.fromCreateText(response, evidence))
            .onErrorResume(e -> Mono.just(ComplaintTicketResult.unavailable()));
    }

    /** 查询投诉工单处理状态。 */
    Mono<String> queryComplaint(String ticketId);

    /** 查询结构化投诉工单状态。 */
    default Mono<ComplaintTicketResult> queryComplaintStructured(String ticketId) {
        return queryComplaint(ticketId)
            .map(response -> ComplaintResultFactory.fromQueryText(ticketId, response))
            .onErrorResume(e -> Mono.just(ComplaintTicketResult.unavailable()));
    }
}
