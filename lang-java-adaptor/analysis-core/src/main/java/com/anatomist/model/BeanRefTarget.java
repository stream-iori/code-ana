package com.anatomist.model;

/** Stable bean identity shared by Spring extraction and persistence adapters. */
public record BeanRefTarget(String beanId, String className) {}
