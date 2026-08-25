package com.richard.fyoung.customerwork.tool.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.LogisticsStatus.DELIVERED;
import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.LogisticsStatus.IN_TRANSIT;
import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.LogisticsStatus.OUT_FOR_DELIVERY;
import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.LogisticsStatus.PENDING_SHIPMENT;
import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.LogisticsStatus.UNKNOWN;
import static com.richard.fyoung.customerwork.tool.backend.LogisticsQueryResult.QueryStatus.FOUND;

/** 将物流后端的历史自由文本适配为保守的结构化结果。 */
final class LogisticsResultFactory {

    private static final Pattern EVENT_PATTERN = Pattern.compile("\\[([^\\]]+)]");

    private LogisticsResultFactory() {
    }

    static LogisticsQueryResult fromLegacyText(String orderId, String response) {
        if (response == null || response.isBlank()) {
            return unavailable(orderId);
        }
        if (response.contains("未查询到")) {
            return notFound(orderId);
        }
        if (response.contains("暂时不可用") || response.contains("系统不可用")) {
            return unavailable(orderId);
        }
        String trace = response;
        int marker = response.indexOf("物流：");
        if (marker >= 0) {
            trace = response.substring(marker + "物流：".length()).trim();
        }
        if (trace.endsWith("。")) {
            trace = trace.substring(0, trace.length() - 1);
        }
        LogisticsQueryResult result = fromTrace(orderId, trace);
        List<String> reasons = new ArrayList<>(result.reasonCodes());
        reasons.add("LOGISTICS_LEGACY_TEXT");
        return new LogisticsQueryResult(result.queryStatus(), result.orderId(), result.logisticsStatus(),
            result.freshnessStatus(), result.events(), result.lastEventAt(), result.promisedDeliveryAt(), reasons);
    }

    static LogisticsQueryResult fromTrace(String orderId, String trace) {
        if (trace == null || trace.isBlank()) {
            return new LogisticsQueryResult(FOUND, orderId, UNKNOWN,
                LogisticsQueryResult.FreshnessStatus.UNKNOWN, List.of(), null, null,
                List.of("LOGISTICS_TRACE_MISSING", "LOGISTICS_FRESHNESS_UNKNOWN",
                    "DELIVERY_PROMISE_UNAVAILABLE"));
        }

        List<LogisticsQueryResult.LogisticsEvent> events = new ArrayList<>();
        Matcher matcher = EVENT_PATTERN.matcher(trace);
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            int separator = token.indexOf(' ');
            if (separator > 0) {
                events.add(new LogisticsQueryResult.LogisticsEvent(
                    token.substring(0, separator), token.substring(separator + 1).trim()));
            } else {
                events.add(new LogisticsQueryResult.LogisticsEvent(null, token));
            }
        }
        if (events.isEmpty()) {
            events.add(new LogisticsQueryResult.LogisticsEvent(null, trace.trim()));
        }

        List<String> reasons = new ArrayList<>(List.of(
            "LOGISTICS_FRESHNESS_UNKNOWN", "DELIVERY_PROMISE_UNAVAILABLE"));
        LogisticsQueryResult.LogisticsStatus status = statusOf(trace);
        if (status == UNKNOWN) {
            reasons.add("LOGISTICS_STATUS_UNKNOWN");
        }
        addExceptionReasons(trace, reasons);
        LogisticsQueryResult.LogisticsEvent last = events.get(events.size() - 1);
        return new LogisticsQueryResult(FOUND, orderId, status,
            LogisticsQueryResult.FreshnessStatus.UNKNOWN, events,
            last.occurredAt(), null, reasons);
    }

    static LogisticsQueryResult notFound(String orderId) {
        return new LogisticsQueryResult(LogisticsQueryResult.QueryStatus.NOT_FOUND, orderId, UNKNOWN,
            LogisticsQueryResult.FreshnessStatus.UNKNOWN,
            List.of(), null, null, List.of("ORDER_NOT_FOUND", "LOGISTICS_NOT_FOUND"));
    }

    static LogisticsQueryResult unavailable(String orderId) {
        return new LogisticsQueryResult(LogisticsQueryResult.QueryStatus.UNAVAILABLE, orderId, UNKNOWN,
            LogisticsQueryResult.FreshnessStatus.UNKNOWN,
            List.of(), null, null, List.of("LOGISTICS_SYSTEM_UNAVAILABLE"));
    }

    private static LogisticsQueryResult.LogisticsStatus statusOf(String trace) {
        if (trace.contains("签收") || trace.contains("已送达")) {
            return DELIVERED;
        }
        if (trace.contains("派送") || trace.contains("配送")) {
            return OUT_FOR_DELIVERY;
        }
        if (trace.contains("揽收") || trace.contains("分拨") || trace.contains("运输")) {
            return IN_TRANSIT;
        }
        if (trace.contains("待发货")) {
            return PENDING_SHIPMENT;
        }
        return UNKNOWN;
    }

    private static void addExceptionReasons(String trace, List<String> reasons) {
        if (trace.contains("签收争议") || trace.contains("未收到")
            || trace.contains("未签收") || trace.contains("冒领") || trace.contains("错收")) {
            reasons.add("LOGISTICS_SIGNATURE_DISPUTE");
        }
        if (trace.contains("丢失") || trace.contains("遗失")) {
            reasons.add("LOGISTICS_LOST");
        }
        if (trace.contains("破损") || trace.contains("损坏")) {
            reasons.add("LOGISTICS_DAMAGED");
        }
    }
}
