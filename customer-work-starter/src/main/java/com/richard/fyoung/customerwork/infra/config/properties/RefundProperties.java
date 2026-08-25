package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/** 退款确认令牌配置；生产环境必须通过密钥管理系统注入 secret。 */
@Data
public class RefundProperties {

    /** HMAC 密钥；空值时退款确认申请安全降级为人工处理。 */
    private String confirmationTokenSecret = "";

    /** 确认令牌有效期，单位秒。 */
    private long confirmationTokenTtlSeconds = 600;
}
