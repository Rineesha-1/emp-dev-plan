package com.devplan.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.HandlerInterceptor;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The engine only accepts users who are members of its tenant. On a user's first API call this registers them,
 * with tenant roles taken from their Keycloak realm roles, using the devplan-backend service account (tenant owner).
 */
@Component
public class TenantRegistrar implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TenantRegistrar.class);

    private final DevplanProperties props;
    private final EngineClient engine;
    private final RestClient keycloak = RestClient.create();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    private volatile String serviceToken;
    private volatile Instant serviceTokenExpiry = Instant.EPOCH;

    public TenantRegistrar(DevplanProperties props, EngineClient engine) {
        this.props = props;
        this.engine = engine;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            return true;
        }
        CurrentUser user = CurrentUser.get();
        if (registered.add(user.userId())) {
            try {
                register(user);
            } catch (RuntimeException e) {
                registered.remove(user.userId());
                log.warn("Could not register user {} in engine tenant: {}", user.username(), e.getMessage());
            }
        }
        return true;
    }

    private void register(CurrentUser user) {
        String path = "/tenant/" + props.engine().tenant() + "/users";
        try {
            engine.call(HttpMethod.GET, path + "/" + user.userId(), null, serviceToken(), null);
            return; // already a member
        } catch (EngineException notMember) {
            if (notMember.status() >= 500) {
                throw notMember;
            }
        }
        Map<String, Object> body = Map.of("userId", user.userId(), "roles", user.caseRoles(),
                "name", user.name() == null ? user.username() : user.name(),
                "email", user.email() == null ? "" : user.email());
        engine.call(HttpMethod.POST, path, body, serviceToken(), null);
        log.info("Registered {} in engine tenant {} with roles {}", user.username(), props.engine().tenant(),
                user.caseRoles());
    }

    private String serviceToken() {
        if (serviceToken != null && Instant.now().isBefore(serviceTokenExpiry)) {
            return serviceToken;
        }
        var k = props.keycloak();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", k.backendClientId());
        form.add("client_secret", k.backendClientSecret());
        JsonNode token = keycloak.post().uri(k.internalUrl() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        serviceToken = token.path("access_token").asText();
        serviceTokenExpiry = Instant.now().plusSeconds(token.path("expires_in").asLong(60) - 30);
        return serviceToken;
    }
}
