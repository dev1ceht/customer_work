package com.richard.fyoung.customerwork.tool.backend;

import java.util.List;

/**
 * Agent 可见的结构化物流事实。没有明确的更新时间或承诺时效时，结果必须显式保留 UNKNOWN，
 * 不能由工具或 Agent 推测。
 *
 * @author owlzhangfq@gmail.com
 */
public record LogisticsQueryResult(
        QueryStatus queryStatus,
        String orderId,
        LogisticsStatus logisticsStatus,
        FreshnessStatus freshnessStatus,
        List<LogisticsEvent> events,
        String lastEventAt,
        String promisedDeliveryAt,
        List<String> reasonCodes) {

    public LogisticsQueryResult {
        queryStatus = queryStatus == null ? QueryStatus.UNAVAILABLE : queryStatus;
        logisticsStatus = logisticsStatus == null ? LogisticsStatus.UNKNOWN : logisticsStatus;
        freshnessStatus = freshnessStatus == null ? FreshnessStatus.UNKNOWN : freshnessStatus;
        events = events == null ? List.of() : List.copyOf(events);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public enum QueryStatus {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    public enum LogisticsStatus {
        PENDING_SHIPMENT,
        IN_TRANSIT,
        OUT_FOR_DELIVERY,
        DELIVERED,
        UNKNOWN
    }

    public enum FreshnessStatus {
        FRESH,
        STALE,
        UNKNOWN
    }

    /** 单条物流事件；时间格式由真实物流后端定义，缺失时为 null。 */
    public record LogisticsEvent(String occurredAt, String description) {
    }
}
