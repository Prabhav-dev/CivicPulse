package com.civicpulse.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class RequestBodyReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestBodyReader() { }

    public static Map<String, Object> readJson(InputStream stream, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        byte[] body = stream.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw new PayloadTooLargeException(maxBytes);
        return JSON.readValue(body, new TypeReference<>() { });
    }
}