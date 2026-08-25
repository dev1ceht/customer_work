package com.richard.fyoung.customerwork.tool.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物流自由文本兼容适配的保守结构化测试。 */
class LogisticsResultFactoryTest {

    @Test
    void legacyTrace_shouldExposeStatusEventsAndUnknownFreshness() {
        LogisticsQueryResult result = LogisticsResultFactory.fromLegacyText(
            "20260613001", "订单 20260613001 物流：[6-11 已揽收]→[6-13 派送中]。");

        assertEquals(LogisticsQueryResult.QueryStatus.FOUND, result.queryStatus());
        assertEquals(LogisticsQueryResult.LogisticsStatus.OUT_FOR_DELIVERY, result.logisticsStatus());
        assertEquals(LogisticsQueryResult.FreshnessStatus.UNKNOWN, result.freshnessStatus());
        assertEquals("6-13", result.lastEventAt());
        assertTrue(result.reasonCodes().contains("LOGISTICS_FRESHNESS_UNKNOWN"));
    }

    @Test
    void missingTrace_shouldNotBeReportedAsNormal() {
        LogisticsQueryResult result = LogisticsResultFactory.fromTrace("20260613003", "");

        assertEquals(LogisticsQueryResult.LogisticsStatus.UNKNOWN, result.logisticsStatus());
        assertTrue(result.reasonCodes().contains("LOGISTICS_TRACE_MISSING"));
    }

    @Test
    void deliveredTraceWithSignatureDispute_shouldCarryEscalationReason() {
        LogisticsQueryResult result = LogisticsResultFactory.fromTrace("20260613002",
            "[6-13 已签收，但用户反馈未收到]");

        assertEquals(LogisticsQueryResult.LogisticsStatus.DELIVERED, result.logisticsStatus());
        assertTrue(result.reasonCodes().contains("LOGISTICS_SIGNATURE_DISPUTE"));
    }
}
