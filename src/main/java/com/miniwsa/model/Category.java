package com.miniwsa.model;

/**
 * Attack category reported by the WAF rule that fired ({@code rule.category}).
 */
public enum Category {
    INJECTION,
    XSS,
    PROTOCOL_VIOLATION,
    DATA_LEAKAGE,
    BOT,
    DOS,
    RATE_LIMIT
}
