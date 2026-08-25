package com.richard.fyoung.customerwork.tool.backend;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** 识别写入结果可能未知的底层异常；未知时必须先查幂等结果，不能盲目重试。 */
final class RefundWriteUncertainty {

    private RefundWriteUncertainty() {
    }

    static boolean isUncertain(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException
                || current instanceof SQLTimeoutException
                || current instanceof SQLTransientConnectionException
                || current instanceof SocketTimeoutException
                || current instanceof IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("timed out")
                    || normalized.contains("connection reset") || normalized.contains("connection closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
