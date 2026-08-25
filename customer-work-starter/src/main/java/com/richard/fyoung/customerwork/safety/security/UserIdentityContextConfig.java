package com.richard.fyoung.customerwork.safety.security;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/** 无论是否开启租户行级过滤，都注册用户主体的 Reactor 上下文传播。 */
@Configuration
public class UserIdentityContextConfig {

    @PostConstruct
    public void registerContextAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            new UserIdentityContextThreadLocalAccessor());
    }
}
