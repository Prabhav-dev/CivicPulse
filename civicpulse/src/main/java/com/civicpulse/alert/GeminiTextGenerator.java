package com.civicpulse.alert;

@FunctionalInterface
public interface GeminiTextGenerator {
    String generate(String model, String prompt) throws Exception;
}