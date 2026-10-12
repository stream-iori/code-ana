package com.anatomist.model;

/** Minimal language-neutral type hierarchy fact exposed to configured member resolution. */
public record TypeRelationFact(String sourceType, String targetType, String relation) {}
