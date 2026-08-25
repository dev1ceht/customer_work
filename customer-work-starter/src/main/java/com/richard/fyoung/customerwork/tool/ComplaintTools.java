package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintEvidence;
import com.richard.fyoung.customerwork.tool.backend.ComplaintEvidenceSanitizer;
import com.richard.fyoung.customerwork.tool.backend.ComplaintTicketResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 投诉工单工具组：建单 / 查单。业务委托给可替换的 {@link ComplaintBackend}。
 *
 * <p>把投诉从"仅识别意图"升级为"可建单 + 可跟踪"，与 {@code transferToHuman} 协同（高危/升级转人工）。</p>
 * @author owlzhangfq@gmail.com
 */
public class ComplaintTools {

    private final ComplaintBackend backend;

    public ComplaintTools(ComplaintBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "使用最小化、脱敏后的投诉事实创建工单。用户明确投诉时调用；不传入完整对话、身份证、地址或支付信息。",
          readOnly = false)
    public Mono<ComplaintTicketResult> fileComplaint(
            @ToolParam(name = "orderId", description = "相关订单号（无则填 '无'）")
            String orderId,
            @ToolParam(name = "summary", description = "投诉问题摘要，最多 300 字")
            String summary,
            @ToolParam(name = "keyFacts", description = "已核验的关键事实和时间，最多 500 字")
            String keyFacts,
            @ToolParam(name = "customerRequest", description = "用户诉求，最多 240 字")
            String customerRequest,
            @ToolParam(name = "escalationReason", description = "升级原因，最多 240 字")
            String escalationReason,
            @ToolParam(name = "priority", description = "投诉优先级：LOW、MEDIUM、HIGH 或 URGENT")
            String priority) {
        ComplaintEvidence evidence = ComplaintEvidenceSanitizer.sanitize(orderId, summary, keyFacts,
            customerRequest, escalationReason, priority);
        if (evidence.summary().isBlank()) {
            return Mono.just(ComplaintTicketResult.rejectedEvidence(evidence.priority(),
                "COMPLAINT_SUMMARY_MISSING"));
        }
        return backend.fileComplaintStructured(evidence.orderId(), evidence);
    }

    /** Java 兼容入口：旧调用方的自由文本也必须先经过脱敏。不是 Agent Tool。 */
    public Mono<ComplaintTicketResult> fileComplaint(String orderId, String content) {
        ComplaintEvidence evidence = ComplaintEvidence.legacy(orderId, content);
        if (evidence.summary().isBlank()) {
            return Mono.just(ComplaintTicketResult.rejectedEvidence(evidence.priority(),
                "COMPLAINT_SUMMARY_MISSING"));
        }
        return backend.fileComplaintStructured(evidence.orderId(), evidence);
    }

    @Tool(description = "查询投诉工单的结构化处理状态。用户询问投诉进度时调用；查询结果未知时不要重复创建工单。",
          readOnly = true)
    public Mono<ComplaintTicketResult> queryComplaint(
            @ToolParam(name = "ticketId", description = "投诉工单号，如 CP123456")
            String ticketId) {
        return backend.queryComplaintStructured(ticketId);
    }
}
