package com.mindcli.plan;

import java.io.IOException;

public class PlanParseException extends IOException {
    public PlanParseException(String message) {
        super(message);
    }

    public PlanParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
