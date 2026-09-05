package com.mindcli.platform.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared Jackson mapper factory for production code. */
public final class JsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper prettyMapper() {
        return PRETTY_MAPPER;
    }

    public static ObjectMapper newMapper() {
        return new ObjectMapper();
    }
}
