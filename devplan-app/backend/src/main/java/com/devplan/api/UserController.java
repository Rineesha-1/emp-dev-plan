package com.devplan.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;

@RestController
@RequestMapping("/api")
public class UserController {
    private final DevplanProperties props;
    private final Employees employees;

    public UserController(DevplanProperties props, Employees employees) {
        this.props = props;
        this.employees = employees;
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
}
