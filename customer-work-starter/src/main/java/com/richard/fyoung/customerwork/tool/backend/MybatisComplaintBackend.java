package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.ComplaintDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import com.richard.fyoung.customerwork.capability.routing.TicketPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 投诉工单后端的 MyBatis-Plus 实现：投诉工单真实落库到 {@code cw_complaint}。
 *
 * <p>输出文案对齐 {@link MockComplaintBackend}；建单走 {@link ComplaintMapper}（BaseMapper.insert），查询走
 * BaseMapper.selectById。本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc} 时装配
 * （种子数据集中在 {@code customer-work-schema.sql}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisComplaintBackend implements ComplaintBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisComplaintBackend.class);

    /** 工单状态：处理中 / 已回复。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final ComplaintMapper complaintMapper;

    public MybatisComplaintBackend(ComplaintMapper complaintMapper) {
        this.complaintMapper = complaintMapper;
    }

    @Override
    public Mono<String> fileComplaint(String orderId, String content) {
        ComplaintEvidence evidence = ComplaintEvidence.legacy(orderId, content);
        return Mono.fromSupplier(() -> legacyCreateText(orderId, doFileComplaint(evidence)));
    }

    @Override
    public Mono<ComplaintTicketResult> fileComplaintStructured(String orderId, ComplaintEvidence evidence) {
        return Mono.fromSupplier(() -> doFileComplaint(evidence));
    }

    @Override
    public Mono<String> queryComplaint(String ticketId) {
        return Mono.fromSupplier(() -> doQueryComplaint(ticketId));
    }

    @Override
    public Mono<ComplaintTicketResult> queryComplaintStructured(String ticketId) {
        return Mono.fromSupplier(() -> doQueryComplaintStructured(ticketId));
    }

    private ComplaintTicketResult doFileComplaint(ComplaintEvidence evidence) {
        if (evidence == null || evidence.summary().isBlank()) {
            return ComplaintTicketResult.rejectedEvidence(
                evidence == null ? null : evidence.priority(), "COMPLAINT_SUMMARY_MISSING");
        }
        String complaintNo = ComplaintBackend.ID_PREFIX + System.currentTimeMillis();
        try {
            ComplaintDO record = new ComplaintDO();
            record.setComplaintNo(complaintNo);
            record.setOrderId(evidence.orderId());
            record.setContent(evidence.storageText());
            record.setStatus(STATUS_PROCESSING);
            record.setPriority(evidence.priority().name());
            record.setCreatedAtMs(System.currentTimeMillis());
            complaintMapper.insert(record);
            log.info("file complaint: ticket={}, order={}, priority={}", complaintNo, evidence.orderId(), evidence.priority());
            return ComplaintTicketResult.created(complaintNo, evidence.priority(), STATUS_PROCESSING,
                java.util.List.of("COMPLAINT_EVIDENCE_SANITIZED"));
        } catch (Exception e) {
            log.error("file complaint failed, code={}, orderId={}",
                "COMPLAINT-BACKEND-FILE-FAIL", evidence.orderId(), e);
            return ComplaintTicketResult.unavailable();
        }
    }

    private String doQueryComplaint(String ticketId) {
        try {
            ComplaintDO complaint = complaintMapper.selectById(ticketId);
            if (complaint == null) {
                return "未查询到投诉工单 " + ticketId + "，请核对工单号。";
            }
            if (STATUS_RESOLVED.equals(complaint.getStatus())) {
                return "投诉工单 " + ticketId + " 当前状态：已处理完毕，感谢您的反馈。";
            }
            return "投诉工单 " + ticketId + " 当前状态：处理中，已分派至对应部门，预计 1 个工作日内回复。";
        } catch (Exception e) {
            log.error("query complaint failed, code={}, ticketId={}", "COMPLAINT-BACKEND-QUERY-FAIL", ticketId, e);
            return "投诉工单查询暂时不可用，建议稍后再试。";
        }
    }

    private ComplaintTicketResult doQueryComplaintStructured(String ticketId) {
        try {
            ComplaintDO complaint = complaintMapper.selectById(ticketId);
            if (complaint == null) {
                return ComplaintTicketResult.notFound(ticketId);
            }
            TicketPriority priority = TicketPriority.fromName(complaint.getPriority());
            String status = STATUS_RESOLVED.equals(complaint.getStatus()) ? "RESOLVED" : STATUS_PROCESSING;
            return ComplaintTicketResult.created(ticketId, priority, status, java.util.List.of());
        } catch (Exception e) {
            log.error("query structured complaint failed, code={}, ticketId={}",
                "COMPLAINT-BACKEND-QUERY-FACTS-FAIL", ticketId, e);
            return ComplaintTicketResult.unavailable();
        }
    }

    private String legacyCreateText(String orderId, ComplaintTicketResult result) {
        return result.submissionStatus() == ComplaintTicketResult.SubmissionStatus.CREATED
            ? "已为您创建投诉工单 " + result.ticketId() + "（订单 " + orderId + "），"
                + "我们将在 24 小时内跟进处理，结果将短信通知您。"
            : "投诉工单系统暂时不可用，已为您转接人工坐席。";
    }
}
