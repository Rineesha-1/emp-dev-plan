package com.devplan.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls the CaseFabric engine REST API with the logged-in user's own Keycloak token, so the engine applies its
 * own case-team and role checks. The engine's query side is eventually consistent: after a command it returns a
 * Case-Last-Modified header, which is sent along on that user's next calls so they see their own changes.
 */
@Component
public class EngineClient {
    private static final String LAST_MODIFIED = "Case-Last-Modified";

    private final RestClient rest;
    private final ObjectMapper json;
    private final Map<String, String> lastModifiedByUser = new ConcurrentHashMap<>();

    public EngineClient(DevplanProperties props, ObjectMapper json) {
        this.rest = RestClient.builder().baseUrl(props.engine().url()).build();
        this.json = json;
    }

    public JsonNode get(String path) {
        return asUser(HttpMethod.GET, path, null);
    }

    public JsonNode post(String path, Object body) {
        return asUser(HttpMethod.POST, path, body);
    }

    public JsonNode put(String path, Object body) {
        return asUser(HttpMethod.PUT, path, body);
    }

    private JsonNode asUser(HttpMethod method, String path, Object body) {
        CurrentUser user = CurrentUser.get();
        return call(method, path, body, user.token(), user.userId());
    }

    /** Calls the engine with an explicit token; consistencyKey is null for calls outside a user's flow. */
    public JsonNode call(HttpMethod method, String path, Object body, String token, String consistencyKey) {
        var request = rest.method(method).uri(path).headers(h -> {
            h.setBearerAuth(token);
            String lastModified = consistencyKey == null ? null : lastModifiedByUser.get(consistencyKey);
            if (lastModified != null) {
                h.set(LAST_MODIFIED, lastModified);
            }
        });
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> {
            String lastModified = res.getHeaders().getFirst(LAST_MODIFIED);
            if (lastModified != null && consistencyKey != null) {
                lastModifiedByUser.put(consistencyKey, lastModified);
            }
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            if (res.getStatusCode().isError()) {
                throw new EngineException(res.getStatusCode().value(), text.isBlank() ? res.getStatusText() : text);
            }
            return parse(text);
        });
    }

    private JsonNode parse(String text) throws IOException {
        if (text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (IOException notJson) {
            return json.getNodeFactory().textNode(text);
        }
    }
}
