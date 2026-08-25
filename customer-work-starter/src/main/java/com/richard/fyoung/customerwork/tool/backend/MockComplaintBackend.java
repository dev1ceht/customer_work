package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投诉工单后端的默认演示实现与单元测试（内存工单库）。生产替换为真实工单/客诉系统。
 * @author owlzhangfq@gmail.com
 */
public class MockComplaintBackend implements ComplaintBackend {

    private static final Logger log = LoggerFactory.getLogger(MockComplaintBackend.class);

    /** ticketId -> 结构化工单快照（演示用内存存储）。 */
    private final ConcurrentHashMap<String, ComplaintTicketResult> tickets = new ConcurrentHashMap<>();

    @Override
    public Mono<String> fileComplaint(String orderId, String content) {
        return Mono.fromSupplier(() -> {
            ComplaintEvidence evidence = ComplaintEvidence.legacy(orderId, content);
            ComplaintTicketResult result = create(evidence);
            return legacyCreateText(orderId, result);
        }).delayElement(Duration.ofMillis(80));
    }

    @Override
    public Mono<ComplaintTicketResult> fileComplaintStructured(String orderId, ComplaintEvidence evidence) {
        return Mono.fromSupplier(() -> create(evidence))
            .delayElement(Duration.ofMillis(80));
    }

    @Override
    public Mono<String> queryComplaint(String ticketId) {
        return Mono.fromSupplier(() -> {
            log.info("[MockComplaintBackend] query complaint: {}", ticketId);
            ComplaintTicketResult result = tickets.get(ticketId);
            return result == null
                ? "未查询到投诉工单 " + ticketId + "，请核对工单号。"
                : legacyQueryText(ticketId, result);
        }).delayElement(Duration.ofMillis(70));
    }

    @Override
    public Mono<ComplaintTicketResult> queryComplaintStructured(String ticketId) {
        return Mono.fromSupplier(() -> {
            log.info("[MockComplaintBackend] query structured complaint: {}", ticketId);
            ComplaintTicketResult result = tickets.get(ticketId);
            return result == null ? ComplaintTicketResult.notFound(ticketId) : result;
        }).delayElement(Duration.ofMillis(70));
    }

    private ComplaintTicketResult create(ComplaintEvidence evidence) {
        if (evidence == null || evidence.summary().isBlank()) {
            return ComplaintTicketResult.rejectedEvidence(
                evidence == null ? null : evidence.priority(), "COMPLAINT_SUMMARY_MISSING");
        }
        String ticketId = ComplaintBackend.ID_PREFIX + System.currentTimeMillis();
        ComplaintTicketResult result = ComplaintTicketResult.created(ticketId, evidence.priority(), "PROCESSING",
            java.util.List.of("COMPLAINT_EVIDENCE_SANITIZED"));
        tickets.put(ticketId, result);
        log.info("[MockComplaintBackend] file complaint: ticket={}, order={}, priority={}",
            ticketId, evidence.orderId(), evidence.priority());
        return result;
    }

    private String legacyCreateText(String orderId, ComplaintTicketResult result) {
        return "已为您创建投诉工单 " + result.ticketId() + "（订单 " + orderId + "），"
            + "我们将在 24 小时内跟进处理，结果将短信通知您。";
    }

    private String legacyQueryText(String ticketId, ComplaintTicketResult result) {
        return "投诉工单 " + ticketId + " 当前状态：处理中，已分派至对应部门，预计 1 个工作日内回复。";
    }
}
