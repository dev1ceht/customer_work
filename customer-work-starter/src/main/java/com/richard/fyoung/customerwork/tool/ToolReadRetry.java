package com.richard.fyoung.customerwork.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** 只读业务 Tool 的一次性瞬时失败重试；写 Tool 不得复用此策略。 */
final class ToolReadRetry {

    private static final Logger log = LoggerFactory.getLogger(ToolReadRetry.class);

    private ToolReadRetry() {
    }

    static <T> Mono<T> once(Mono<T> operation, String toolName) {
        return operation.retryWhen(Retry.max(1)
            .filter(ToolReadRetry::isTransient)
            .doBeforeRetry(signal -> log.info(
                "read tool retry: tool={}, attempt={}, reason={}", toolName,
                signal.totalRetries() + 1, signal.failure().getClass().getSimpleName())));
    }

    private static boolean isTransient(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                || current instanceof SQLTimeoutException || current instanceof SQLTransientConnectionException
                || current instanceof IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("temporarily unavailable")
                    || normalized.contains("connection reset") || normalized.contains("connection closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
