package com.devplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devplan")
public record DevplanProperties(Keycloak keycloak, Engine engine, App app) {

    public record Keycloak(String internalUrl, String issuer, String publicUrl, String adminUrl, String realm,
                           String webClientId, String backendClientId, String backendClientSecret) {
    }

    public record Engine(String url, String tenant, String definition) {
    }

    public record App(String baseUrl) {
    }
}
