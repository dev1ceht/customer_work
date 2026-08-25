package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintTicketResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉工单工具组单测：建单返回工单号、查单状态、未知工单兜底。
 * @author owlzhangfq@gmail.com
 */
class ComplaintToolsTest {

    private final ComplaintTools tools = new ComplaintTools(new MockComplaintBackend());

    @Test
    void fileComplaint_shouldReturnTicketId() {
        StepVerifier.create(tools.fileComplaint("20260613001", "物流太慢且客服态度差"))
            .assertNext(r -> {
                assertEquals(ComplaintTicketResult.SubmissionStatus.CREATED, r.submissionStatus());
                assertTrue(r.ticketId().startsWith("CP"));
                assertEquals("PROCESSING", r.status());
            })
            .verifyComplete();
    }

    @Test
    void queryComplaint_shouldHandleUnknownTicket() {
        StepVerifier.create(tools.queryComplaint("CP-not-exist"))
            .assertNext(r -> assertEquals(ComplaintTicketResult.SubmissionStatus.NOT_FOUND,
                r.submissionStatus()))
            .verifyComplete();
    }

    @Test
    void fileComplaint_shouldReturnPriorityAndSanitizedEvidenceResult() {
        StepVerifier.create(tools.fileComplaint("20260613001", "客服态度差 13812345678",
                "已查询订单状态", "要求人工跟进", "用户明确要求升级", "HIGH"))
            .assertNext(r -> {
                assertEquals(ComplaintTicketResult.SubmissionStatus.CREATED, r.submissionStatus());
                assertEquals(com.richard.fyoung.customerwork.capability.routing.TicketPriority.HIGH, r.priority());
                assertTrue(r.reasonCodes().contains("COMPLAINT_EVIDENCE_SANITIZED"));
            })
            .verifyComplete();
    }

    @Test
    void fileComplaint_shouldRejectMissingSummary() {
        StepVerifier.create(tools.fileComplaint("20260613001", "", "已核验订单状态",
                "请人工处理", "用户明确要求升级", "HIGH"))
            .assertNext(r -> {
                assertEquals(ComplaintTicketResult.SubmissionStatus.UNKNOWN, r.submissionStatus());
                assertTrue(r.reasonCodes().contains("COMPLAINT_SUMMARY_MISSING"));
            })
            .verifyComplete();
    }
}
