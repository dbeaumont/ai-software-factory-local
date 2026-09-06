package com.example.aifactory.a2a;

/** Resolves a short-lived credential only inside an activity worker, never inside workflow code. */
public interface A2aCredentialVault {
    char[] consume(A2aAuthGrant grant);
}
