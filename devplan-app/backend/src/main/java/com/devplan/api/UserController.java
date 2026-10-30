package com.devplan.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.KeycloakAdminClient;

@RestController
@RequestMapping("/api")
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final DevplanProperties props;
    private final Employees employees;
    private final KeycloakAdminClient keycloak;

    public UserController(DevplanProperties props, Employees employees, KeycloakAdminClient keycloak) {
        this.props = props;
        this.employees = employees;
        this.keycloak = keycloak;
    }

    /** Public: what the browser needs to log in with Keycloak. */
    @GetMapping("/config")
    public Map<String, String> config() {
        var k = props.keycloak();
        return Map.of("keycloakUrl", k.publicUrl(), "realm", k.realm(), "clientId", k.webClientId());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser user = CurrentUser.get();
        Map<String, Object> me = new HashMap<>();
        me.put("userId", user.userId());
        me.put("username", user.username());
        me.put("name", user.name());
        me.put("roles", user.roles());
        me.put("employee", employees.byUsername(user.username()).orElse(null));
        return me;
    }

    @GetMapping("/employees")
    public List<Employee> employees() {
        return employees.visibleTo(CurrentUser.get());
    }

    public record AddEmployeeRequest(String name, String jobTitle, String username, String email, String role,
                                     String managerId) {
    }

    /** HR-only: creates the Keycloak account (no password — Keycloak emails the person a link to set their own)
     * and the employee record. If any step after the Keycloak user is created fails, that user is deleted again
     * so a failed attempt never leaves a live, orphaned Keycloak account behind. */
    @PostMapping("/employees")
    public Map<String, String> addEmployee(@RequestBody AddEmployeeRequest req) {
        if (!CurrentUser.get().has("hr")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HR can add employees");
        }
        if (isBlank(req.name()) || isBlank(req.username()) || isBlank(req.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name, username and email are required");
        }
        if (CurrentUser.caseRole(req.role()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be employee, manager or hr");
        }
        if (employees.byUsername(req.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, req.username() + " is already in use");
        }
        if (req.managerId() != null && !employees.byId(req.managerId()).map(m -> "manager".equals(m.role())).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The manager must be an employee with the manager role");
        }

        String userId = keycloak.createUser(req.name(), req.username(), req.email());
        try {
            keycloak.assignRealmRole(userId, req.role());
            keycloak.sendSetPasswordEmail(userId, props.app().baseUrl());
            employees.insert(req.name(), req.jobTitle(), req.username(), userId, req.managerId(), req.role());
        } catch (RuntimeException e) {
            try {
                keycloak.deleteUser(userId);
            } catch (RuntimeException cleanupFailed) {
                log.error("Could not clean up orphaned Keycloak user {} ({}) after a failed Add Employee: {}",
                        userId, req.username(), cleanupFailed.getMessage());
            }
            throw e;
        }
        log.info("Added employee {} ({}) with role {}", req.username(), userId, req.role());
        return Map.of("username", req.username(), "email", req.email(), "message", "Invite email sent");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
