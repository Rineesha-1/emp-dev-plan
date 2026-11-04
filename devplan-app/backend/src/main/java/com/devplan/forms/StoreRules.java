package com.devplan.forms;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Business rules on DB-stored form data that a JSON schema cannot express (FRD-02 validation table, exact messages).
 * They run on every save of the store, also for a draft, when the value is filled in.
 */
@Component
public class StoreRules {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcClient db;

    public StoreRules(JdbcClient db) {
        this.db = db;
    }

    public void check(String store, String employeeId, JsonNode value) {
        if ("employee".equals(store)) {
            employee(employeeId, value);
        }
    }

    private void employee(String employeeId, JsonNode value) {
        String email = value.path("privateEmail").asText("").trim();
        if (!email.isEmpty()) {
            if (!EMAIL.matcher(email).matches()) {
                throw bad("Enter a valid e-mail address.");
            }
            boolean taken = db.sql("SELECT count(*) FROM employee WHERE lower(private_email) = lower(?) AND employee_id <> ?")
                    .param(email).param(employeeId).query(Integer.class).single() > 0;
            if (taken) { // ONB-09
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An employee with this e-mail address already exists.");
            }
        }
        String birth = value.path("dateOfBirth").asText("").trim();
        if (!birth.isEmpty()) {
            try {
                if (LocalDate.parse(birth).plusYears(16).isAfter(LocalDate.now())) {
                    throw bad("The employee must be at least 16 years old.");
                }
            } catch (DateTimeParseException e) {
                throw bad("Date of birth: enter a valid date.");
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
