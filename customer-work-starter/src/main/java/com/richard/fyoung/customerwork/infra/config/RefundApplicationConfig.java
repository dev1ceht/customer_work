package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.tool.backend.RefundConfirmationTokenService;
import com.richard.fyoung.customerwork.tool.backend.RefundInvocationContextThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 退款确认令牌装配；未配置密钥时保持安全降级，不生成可提交令牌。 */
@Configuration
public class RefundApplicationConfig {

    @PostConstruct
    public void registerInvocationContextAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            new RefundInvocationContextThreadLocalAccessor());
    }

    @Bean
    @ConditionalOnMissingBean
    public RefundConfirmationTokenService refundConfirmationTokenService(CustomerWorkProperties properties) {
        var refund = properties.getRefund();
        long ttlSeconds = Math.max(1L, refund.getConfirmationTokenTtlSeconds());
        return new RefundConfirmationTokenService(refund.getConfirmationTokenSecret(),
            java.time.Clock.systemUTC(), Duration.ofSeconds(ttlSeconds));
    }
}
