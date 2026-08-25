package com.richard.fyoung.customerwork.safety.security;

import io.micrometer.context.ThreadLocalAccessor;

/** 让已验签的用户主体跨 Reactor 线程边界传播到同步业务后端。 */
public class UserIdentityContextThreadLocalAccessor implements ThreadLocalAccessor<UserPrincipal> {

    @Override
    public Object key() {
        return UserIdentityContext.KEY;
    }

    @Override
    public UserPrincipal getValue() {
        return UserIdentityContext.get();
    }

    @Override
    public void setValue(UserPrincipal value) {
        UserIdentityContext.set(value);
    }

    @Override
    public void setValue() {
        UserIdentityContext.clear();
    }
}
