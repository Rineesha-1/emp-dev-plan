package com.devplan.forms;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Option lists for task-model fields marked "x-options": the field gets a "oneOf" of {const, title} from the app DB,
 * so dropdowns follow the data without changing the form. A new list = one entry here.
 */
@Component
public class Lookups {
    public record Option(String value, String title) {
    }

    private final JdbcClient db;

    public Lookups(JdbcClient db) {
        this.db = db;
    }

    /** The options of a lookup for a form: buddies leave out the employee the form is about; goals are the titles of
     * the goals of the form's plan round. */
    public List<Option> options(String lookup, FormContext ctx) {
        if ("goals".equals(lookup)) {
            return ctx.roundId() == null ? List.of() : db.sql("SELECT title, title FROM dev_goal WHERE round_id = ? "
                    + "AND title IS NOT NULL ORDER BY seq").param(ctx.roundId())
                    .query((rs, n) -> new Option(rs.getString(1), rs.getString(2))).list();
        }
        String employeeId = ctx.employeeId();
        Map<String, String> sql = Map.of(
                "managers", "SELECT employee_id, name FROM employee WHERE role = 'manager' AND stage = 'ACTIVE' ORDER BY name",
                "buddies", "SELECT employee_id, name FROM employee WHERE stage = 'ACTIVE' AND user_id IS NOT NULL "
                        + "AND role IN ('employee', 'manager') AND employee_id <> coalesce(?, '') ORDER BY name",
                "departments", "SELECT name, name FROM department ORDER BY name",
                "jobTitles", "SELECT name, name FROM job_title ORDER BY name");
        String query = sql.get(lookup);
        if (query == null) {
            return List.of();
        }
        var statement = db.sql(query);
        if (query.contains("?")) {
            statement = statement.param(employeeId);
        }
        return statement.query((rs, n) -> new Option(rs.getString(1), rs.getString(2))).list();
    }
}
