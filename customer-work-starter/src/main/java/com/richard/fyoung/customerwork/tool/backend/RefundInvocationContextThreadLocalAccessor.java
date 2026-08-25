package com.richard.fyoung.customerwork.tool.backend;

import io.micrometer.context.ThreadLocalAccessor;

/** 让可信的会话关联在工具异步执行时仍可用于审批审计。 */
public class RefundInvocationContextThreadLocalAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return RefundInvocationContext.KEY;
    }

    @Override
    public String getValue() {
        return RefundInvocationContext.get();
    }

    @Override
    public void setValue(String value) {
        RefundInvocationContext.set(value);
    }

    @Override
    public void setValue() {
        RefundInvocationContext.clear();
    }
}
