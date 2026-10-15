package com.alipay.sofa.runtime.api.annotation;
/** Shape of the internal Runtime 4.19.1 API, not a runtime implementation. */
public @interface SofaService {
    Class<?> interfaceType() default void.class;
    String uniqueId() default "";
}
