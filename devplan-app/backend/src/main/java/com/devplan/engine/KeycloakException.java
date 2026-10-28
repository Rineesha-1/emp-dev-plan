package com.devplan.engine;

/** A non-2xx answer from Keycloak's Admin REST API; the API passes the status on to the browser. */
public class KeycloakException extends RuntimeException {
    private final int status;

    public KeycloakException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
