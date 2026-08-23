package com.example.aifactory.model;

public enum TaskStatus {
    QUEUED,
    CLONING,
    PLANNING,
    GENERATING_PATCH,
    APPLYING_PATCH,
    TESTING,
    SECURITY_SCANNING,
    REVIEWING,
    WAITING_APPROVAL,
    APPROVED,
    PR_CREATED,
    FAILED
}
