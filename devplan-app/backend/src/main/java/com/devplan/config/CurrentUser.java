package com.devplan.config;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The logged-in user, taken from the Keycloak token of the current request. */
public record CurrentUser(String userId, String username, String name, String email, Set<String> roles,
                          String token) {

    public static CurrentUser get() {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var jwt = auth.getToken();
        Set<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().substring("ROLE_".length()))
                .collect(Collectors.toSet());
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), roles, jwt.getTokenValue());
    }

    public boolean has(String role) {
        return roles.contains(role);
    }

    /** Realm roles mapped to the case roles of development.caseteam (and the engine tenant roles). */
    public Set<String> caseRoles() {
        return roles.stream().map(CurrentUser::caseRole).filter(r -> r != null).collect(Collectors.toSet());
    }

    public static String caseRole(String realmRole) {
        return switch (realmRole) {
            case "employee" -> "Employee";
            case "manager" -> "Manager";
            case "hr" -> "HR";
            default -> null;
        };
    }
}
