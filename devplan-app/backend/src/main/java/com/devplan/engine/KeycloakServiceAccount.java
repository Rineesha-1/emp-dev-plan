package com.devplan.engine;

import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.devplan.config.DevplanProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** A cached client-credentials token for the devplan-backend service account, shared by any code that needs to
 * call the engine or Keycloak's Admin API as that account (it is both the engine tenant's owner and, via its
 * realm-management role grant, allowed to manage users). */
@Component
public class KeycloakServiceAccount {
    private final DevplanProperties props;
    private final RestClient keycloak = RestClient.create();
    private volatile String token;
    private volatile Instant expiry = Instant.EPOCH;

    public KeycloakServiceAccount(DevplanProperties props) {
        this.props = props;
    }

    public synchronized String token() {
        if (token != null && Instant.now().isBefore(expiry)) {
            return token;
        }
        var k = props.keycloak();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", k.backendClientId());
        form.add("client_secret", k.backendClientSecret());
        JsonNode response = keycloak.post().uri(k.internalUrl() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        token = response.path("access_token").asText();
        expiry = Instant.now().plusSeconds(response.path("expires_in").asLong(60) - 30);
        return token;
    }
}
