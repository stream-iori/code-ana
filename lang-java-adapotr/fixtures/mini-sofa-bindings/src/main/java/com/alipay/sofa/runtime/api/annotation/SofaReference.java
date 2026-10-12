package com.alipay.sofa.runtime.api.annotation;
public @interface SofaReference {
    Class<?> interfaceType() default void.class;
    String uniqueId() default "";
}
