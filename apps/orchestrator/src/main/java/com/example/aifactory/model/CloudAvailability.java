package com.example.aifactory.model;

public record CloudAvailability(boolean available, String error) {
    public static CloudAvailability reachable() {
        return new CloudAvailability(true, null);
    }

    public static CloudAvailability unavailable(String error) {
        return new CloudAvailability(false, error);
    }
}