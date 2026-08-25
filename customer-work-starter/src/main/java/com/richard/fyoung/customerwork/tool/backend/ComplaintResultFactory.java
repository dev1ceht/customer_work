package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.routing.TicketPriority;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将旧投诉后端自由文本保守适配为结构化结果。 */
final class ComplaintResultFactory {

    private static final Pattern TICKET_ID = Pattern.compile("\\bCP[-A-Za-z0-9]+\\b");

    private ComplaintResultFactory() {
    }

    static ComplaintTicketResult fromCreateText(String response, ComplaintEvidence evidence) {
        if (response == null || response.isBlank()) {
            return new ComplaintTicketResult(ComplaintTicketResult.SubmissionStatus.UNKNOWN, null,
                evidence.priority(), "UNKNOWN", List.of("COMPLAINT_RESULT_UNKNOWN"));
        }
        if (response.contains("系统暂时不可用") || response.contains("暂时不可用")) {
            return ComplaintTicketResult.unavailable();
        }
        String ticketId = ticketId(response);
        if (ticketId == null) {
            return new ComplaintTicketResult(ComplaintTicketResult.SubmissionStatus.UNKNOWN, null,
                evidence.priority(), "UNKNOWN", List.of("COMPLAINT_RESULT_UNPARSEABLE"));
        }
        return ComplaintTicketResult.created(ticketId, evidence.priority(), "PROCESSING",
            List.of("COMPLAINT_EVIDENCE_SANITIZED", "COMPLAINT_LEGACY_TEXT"));
    }

    static ComplaintTicketResult fromQueryText(String ticketId, String response) {
        if (response == null || response.isBlank()) {
            return new ComplaintTicketResult(ComplaintTicketResult.SubmissionStatus.UNKNOWN, ticketId,
                TicketPriority.MEDIUM, "UNKNOWN", List.of("COMPLAINT_RESULT_UNKNOWN"));
        }
        if (response.contains("未查询到")) {
            return ComplaintTicketResult.notFound(ticketId);
        }
        if (response.contains("暂时不可用") || response.contains("系统不可用")) {
            return ComplaintTicketResult.unavailable();
        }
        String status = response.contains("已处理完毕") ? "RESOLVED" :
            (response.contains("处理中") ? "PROCESSING" : "UNKNOWN");
        return new ComplaintTicketResult(ComplaintTicketResult.SubmissionStatus.CREATED, ticketId,
            TicketPriority.MEDIUM, status, List.of("COMPLAINT_PRIORITY_UNAVAILABLE"));
    }

    private static String ticketId(String response) {
        Matcher matcher = TICKET_ID.matcher(response);
        return matcher.find() ? matcher.group() : null;
    }
}
