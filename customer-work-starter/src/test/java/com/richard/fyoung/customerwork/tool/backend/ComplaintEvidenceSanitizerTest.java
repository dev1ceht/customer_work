package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.routing.TicketPriority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 投诉最小证据的脱敏、白名单字段和长度边界测试。 */
class ComplaintEvidenceSanitizerTest {

    @Test
    void sanitize_shouldMaskSensitiveValuesAndKeepOnlyBoundedFacts() {
        ComplaintEvidence evidence = ComplaintEvidenceSanitizer.sanitize(
            "20260613001",
            "客服未按承诺处理，手机号 13812345678，邮箱 a@example.com",
            "地址：上海市浦东新区某路 1 号；身份证 110101199001011234",
            "希望人工跟进并说明处理结果",
            "用户明确要求升级",
            "HIGH");

        String stored = evidence.storageText();
        assertEquals("20260613001", evidence.orderId());
        assertEquals(TicketPriority.HIGH, evidence.priority());
        assertFalse(stored.contains("13812345678"));
        assertFalse(stored.contains("a@example.com"));
        assertFalse(stored.contains("110101199001011234"));
        assertFalse(stored.contains("上海市浦东新区某路 1 号"));
        assertTrue(stored.contains("问题摘要=") && stored.contains("用户诉求="));
    }

    @Test
    void sanitize_shouldDefaultUnknownPriorityAndTruncateFields() {
        ComplaintEvidence evidence = ComplaintEvidenceSanitizer.sanitize(
            "无", "x".repeat(500), "", "", "", "NOT_A_PRIORITY");

        assertEquals(TicketPriority.MEDIUM, evidence.priority());
        assertTrue(evidence.summary().length() <= 300);
    }

    @Test
    void directEvidenceConstruction_shouldStillSanitizeSensitiveValues() {
        ComplaintEvidence evidence = new ComplaintEvidence(
            "13812345678", "手机号 13812345678，邮箱 a@example.com", "地址：上海市浦东新区某路 1 号",
            "请人工处理", "用户明确要求升级", TicketPriority.HIGH);

        String stored = evidence.storageText();
        assertEquals("[已脱敏]", evidence.orderId());
        assertFalse(stored.contains("13812345678"));
        assertFalse(stored.contains("a@example.com"));
        assertFalse(stored.contains("上海市浦东新区某路 1 号"));
    }
}
