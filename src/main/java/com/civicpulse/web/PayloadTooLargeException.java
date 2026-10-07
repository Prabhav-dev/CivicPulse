package com.civicpulse.web;

public final class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(int maxBytes) {
        super("Request body exceeds the " + maxBytes + " byte limit");
    }
}