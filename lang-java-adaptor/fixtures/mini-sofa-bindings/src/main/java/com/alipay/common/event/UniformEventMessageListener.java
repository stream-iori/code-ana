package com.alipay.common.event;
public interface UniformEventMessageListener {
    void onUniformEvent(UniformEvent event, UniformEventContext context) throws Exception;
}
