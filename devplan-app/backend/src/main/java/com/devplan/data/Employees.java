package com.devplan.data;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.devplan.config.CurrentUser;

@Repository
public class Employees {

    public record Employee(String employeeId, String name, String jobTitle, String username, String userId,
                           String managerId) {
    }

    private static final String SELECT = "SELECT employee_id, name, job_title, username, user_id, manager_id FROM employee ";

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
