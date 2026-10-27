package com.devplan.engine;

/** A non-2xx answer from the case engine; the API passes the status on to the browser. */
public class EngineException extends RuntimeException {
    private final int status;

    public EngineException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
