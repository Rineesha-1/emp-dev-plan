package com.devplan.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.devplan.config.CurrentUser;

@Repository
public class Employees {

    public record Employee(String employeeId, String name, String jobTitle, String username, String userId,
                           String managerId, String role) {
    }

    private static final String SELECT = "SELECT employee_id, name, job_title, username, user_id, manager_id, role FROM employee ";

    private final JdbcClient db;

    public Employees(JdbcClient db) {
        this.db = db;
    }

    public Optional<Employee> byId(String employeeId) {
        return db.sql(SELECT + "WHERE employee_id = ?").param(employeeId).query(Employee.class).optional();
    }

    public Optional<Employee> byUsername(String username) {
        return db.sql(SELECT + "WHERE username = ?").param(username).query(Employee.class).optional();
    }

    /** Adds a new employee, with the real Keycloak user id already known. */
    public Employee insert(String name, String jobTitle, String username, String userId, String managerId,
                           String role) {
        String employeeId = UUID.randomUUID().toString();
        db.sql("INSERT INTO employee (employee_id, name, job_title, username, user_id, manager_id, role) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(employeeId, name, jobTitle, username, userId, managerId, role).update();
        return new Employee(employeeId, name, jobTitle, username, userId, managerId, role);
    }

    /** HR sees everyone, a manager their direct reports, an employee themselves. */
    public List<Employee> visibleTo(CurrentUser user) {
        if (user.has("hr")) {
            return db.sql(SELECT + "ORDER BY name").query(Employee.class).list();
        }
        Optional<Employee> self = byUsername(user.username());
        if (self.isEmpty()) {
            return List.of();
        }
        if (user.has("manager")) {
            return db.sql(SELECT + "WHERE manager_id = ? OR employee_id = ? ORDER BY name")
                    .param(self.get().employeeId()).param(self.get().employeeId()).query(Employee.class).list();
        }
        return List.of(self.get());
    }
}
