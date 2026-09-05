package com.example.aifactory.workflow.temporal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public final class TemporalAdmissionUnavailableException extends RuntimeException {
    public TemporalAdmissionUnavailableException(String message) { super(message); }
    public TemporalAdmissionUnavailableException(String message, Throwable cause) { super(message, cause); }
}
