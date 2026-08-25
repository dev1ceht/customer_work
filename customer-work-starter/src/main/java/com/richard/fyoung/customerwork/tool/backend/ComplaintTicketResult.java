package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.routing.TicketPriority;

import java.util.List;

/** Agent 可见的结构化投诉工单结果。 */
public record ComplaintTicketResult(
        SubmissionStatus submissionStatus,
        String ticketId,
        TicketPriority priority,
        String status,
        List<String> reasonCodes) {

    public ComplaintTicketResult {
        submissionStatus = submissionStatus == null ? SubmissionStatus.UNKNOWN : submissionStatus;
        priority = priority == null ? TicketPriority.MEDIUM : priority;
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public enum SubmissionStatus {
        CREATED,
        NOT_FOUND,
        UNAVAILABLE,
        UNKNOWN
    }

    public static ComplaintTicketResult created(String ticketId, TicketPriority priority, String status,
                                                List<String> reasonCodes) {
        return new ComplaintTicketResult(SubmissionStatus.CREATED, ticketId, priority, status, reasonCodes);
    }

    public static ComplaintTicketResult notFound(String ticketId) {
        return new ComplaintTicketResult(SubmissionStatus.NOT_FOUND, ticketId, TicketPriority.MEDIUM,
            "UNKNOWN", List.of("COMPLAINT_NOT_FOUND"));
    }

    public static ComplaintTicketResult unavailable() {
        return new ComplaintTicketResult(SubmissionStatus.UNAVAILABLE, null, TicketPriority.MEDIUM,
            "UNKNOWN", List.of("COMPLAINT_SYSTEM_UNAVAILABLE"));
    }

    public static ComplaintTicketResult rejectedEvidence(TicketPriority priority, String reasonCode) {
        return new ComplaintTicketResult(SubmissionStatus.UNKNOWN, null, priority, "UNKNOWN",
            List.of(reasonCode));
    }
}
