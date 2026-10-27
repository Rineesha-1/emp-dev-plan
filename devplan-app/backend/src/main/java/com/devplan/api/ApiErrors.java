package com.devplan.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.engine.EngineException;

/** Errors as {"error": "..."} with the engine's own status (e.g. 401/404 when a user is not in the case team). */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(EngineException.class)
    ResponseEntity<Map<String, String>> engine(EngineException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", "Case engine: " + e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }
}
