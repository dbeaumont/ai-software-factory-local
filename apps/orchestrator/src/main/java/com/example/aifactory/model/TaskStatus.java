package com.example.aifactory.model;

public enum TaskStatus {
    QUEUED,
    CLONING,
    PLANNING,
    GENERATING_PATCH,
    APPLYING_PATCH,
    TESTING,
    QUALITY_SCANNING,
    SECURITY_SCANNING,
    REVIEWING,
    WAITING_APPROVAL,
    APPROVED,
    PR_CREATED,
    CANCELLED,
    FAILED
}
