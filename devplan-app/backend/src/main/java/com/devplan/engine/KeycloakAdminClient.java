package com.devplan.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.devplan.config.DevplanProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Creates employee accounts in Keycloak via its Admin REST API, using the devplan-backend service account (granted
 * the realm-management client roles manage-users and view-realm). Used only by the Add Employee feature; everything
 * else in this app only ever validates tokens Keycloak itself already issued.
 */
@Component
public class KeycloakAdminClient {
    private final RestClient rest;
    private final DevplanProperties.Keycloak props;
    private final KeycloakServiceAccount serviceAccount;
    private final ObjectMapper json;

    public KeycloakAdminClient(DevplanProperties props, KeycloakServiceAccount serviceAccount, ObjectMapper json) {
        this.rest = RestClient.builder().baseUrl(props.keycloak().adminUrl()).build();
        this.props = props.keycloak();
        this.serviceAccount = serviceAccount;
        this.json = json;
    }

    /** Creates an enabled user with no password and no required actions yet; returns the new user's Keycloak id. */
    public String createUser(String name, String username, String email) {
        Map<String, Object> body = Map.of("username", username, "email", email, "emailVerified", false,
                "enabled", true, "firstName", firstName(name), "lastName", lastName(name));
        String location = exchange(HttpMethod.POST, "/users", body).getHeaders().getFirst("Location");
        if (location == null) {
            throw new KeycloakException(502, "Keycloak did not return the new user's id");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** Realm roles can't be assigned by name directly; Keycloak wants their full representation. */
    public void assignRealmRole(String userId, String role) {
        JsonNode roleRepresentation = getJson("/roles/" + role);
        exchange(HttpMethod.POST, "/users/" + userId + "/role-mappings/realm", List.of(roleRepresentation));
    }

    /** Has Keycloak email the user a link to set their own password; nobody else ever sees or sets it. */
    public void sendSetPasswordEmail(String userId, String redirectUri) {
        exchange(HttpMethod.PUT, b -> b.path("/users/{userId}/execute-actions-email")
                .queryParam("client_id", props.webClientId()).queryParam("redirect_uri", redirectUri)
                .build(userId), List.of("UPDATE_PASSWORD"));
    }

    /** Best-effort cleanup when a later onboarding step fails after the Keycloak user was already created. */
    public void deleteUser(String userId) {
        exchange(HttpMethod.DELETE, "/users/" + userId, null);
    }

    private JsonNode getJson(String path) {
        String text = exchange(HttpMethod.GET, path, null).getBody();
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new KeycloakException(502, "Keycloak returned invalid JSON for " + path);
        }
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        return exchange(method, b -> b.path(path).build(), body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, Function<UriBuilder, URI> uri, Object body) {
        var request = rest.method(method).uri(uri).headers(h -> h.setBearerAuth(serviceAccount.token()));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            if (res.getStatusCode().isError()) {
                throw new KeycloakException(res.getStatusCode().value(), text.isBlank() ? res.getStatusText() : text);
            }
            return ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders()).body(text);
        });
    }

    private static String firstName(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    private static String lastName(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? "" : name.substring(space + 1);
    }
}
