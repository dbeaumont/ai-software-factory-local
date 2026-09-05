package com.example.aifactory.service;

/** Allocates a globally unique, human-readable task number. */
public interface TicketNumberGenerator {
    String next();
}
