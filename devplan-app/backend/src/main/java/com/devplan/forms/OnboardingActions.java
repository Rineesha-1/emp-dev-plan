package com.devplan.forms;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.EngineClient;
import com.devplan.engine.KeycloakAdminClient;
import com.devplan.engine.TenantRegistrar;
import com.devplan.onboarding.Checklists;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The "x-on-complete" actions of the onboarding forms: what happens besides saving the DB data when a task is
 * completed. Each returns the case-file outputs it derives (only IDs, names and flags), which the form layer adds to
 * the output it sends to the engine.
 */
@Component
public class OnboardingActions implements FormActions {
    private static final Set<String> ACTIONS = Set.of("createEmployee", "sendToIt", "itSetupDone", "confirmEquipment");

    private static final Logger log = LoggerFactory.getLogger(OnboardingActions.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Employees employees;
    private final KeycloakAdminClient keycloak;
    private final TenantRegistrar tenant;
    private final EngineClient engine;
    private final Checklists checklists;
    private final DevplanProperties props;

    public OnboardingActions(JdbcClient db, ObjectMapper json, Employees employees, KeycloakAdminClient keycloak,
                             TenantRegistrar tenant, EngineClient engine, Checklists checklists, DevplanProperties props) {
        this.db = db;
        this.json = json;
        this.employees = employees;
        this.keycloak = keycloak;
        this.tenant = tenant;
        this.engine = engine;
        this.checklists = checklists;
        this.props = props;
    }

    @Override
    public boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    @Override
    public ObjectNode run(String action, FormContext ctx) {
        return switch (action) {
            case "createEmployee" -> createEmployee(ctx);
            case "sendToIt" -> sendToIt(ctx);
            case "itSetupDone" -> itSetupDone(ctx);
            case "confirmEquipment" -> confirmEquipment(ctx);
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown form action " + action);
        };
    }

    /**
     * Step 1 done: the Keycloak account (Keycloak e-mails the set-password link to the private address), stage
     * ONBOARDING, the checklist, the employee and manager in the case team; returns the Employee case item.
     */
    private ObjectNode createEmployee(FormContext ctx) {
        Map<String, Object> row = db.sql("SELECT first_name, last_name, private_email, role, manager_id, job_title, "
                        + "user_id, username FROM employee WHERE employee_id = ?").param(ctx.employeeId())
                .query((rs, n) -> TableStore.row(rs)).single();
        String name = (row.get("first_name") + " " + row.get("last_name")).trim();
        String role = (String) row.get("role");
        Employee manager = employees.byId((String) row.get("manager_id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manager: This field is required."));
        if (manager.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The manager has no account yet");
        }

        String userId = (String) row.get("user_id");
        if (userId == null) { // the account is made once; completing again (after an engine error) reuses it
            String username = uniqueUsername((String) row.get("first_name"), (String) row.get("last_name"));
            userId = keycloak.createUser(name, username, (String) row.get("private_email"));
            try {
                keycloak.assignRealmRole(userId, role);
                db.sql("UPDATE employee SET username = ?, user_id = ? WHERE employee_id = ?")
                        .params(username, userId, ctx.employeeId()).update();
                tenant.register(userId, name, (String) row.get("private_email"), Set.of(CurrentUser.caseRole(role)));
                keycloak.sendSetPasswordEmail(userId, props.app().baseUrl());
            } catch (RuntimeException e) {
                try {
                    keycloak.deleteUser(userId);
                    db.sql("UPDATE employee SET username = NULL, user_id = NULL WHERE employee_id = ?")
                            .param(ctx.employeeId()).update();
                } catch (RuntimeException cleanupFailed) {
                    log.error("Could not clean up Keycloak user {} after a failed onboarding: {}", userId,
                            cleanupFailed.getMessage());
                }
                throw e;
            }
            log.info("Onboarding: created account {} for employee {}", username, ctx.employeeId());
        }
        db.sql("UPDATE employee SET name = ?, stage = 'ONBOARDING' WHERE employee_id = ?")
                .params(name, ctx.employeeId()).update();
        db.sql("UPDATE onboarding SET status = 'IN_PROGRESS' WHERE employee_id = ?").param(ctx.employeeId()).update();
        checklists.createDefaults(ctx.employeeId());

        addToTeam(ctx.caseId(), userId, "Employee");
        addToTeam(ctx.caseId(), manager.userId(), "Manager");
        mailRecipients(ctx, (String) row.get("private_email"), manager);

        ObjectNode output = json.createObjectNode();
        ObjectNode employee = output.putObject("Employee");
        employee.put("employeeId", ctx.employeeId());
        employee.put("name", name);
        employee.put("jobTitle", (String) row.get("job_title"));
        employee.put("managerId", manager.employeeId());
        employee.put("managerName", manager.name());
        employee.put("trigger", "ONBOARDING");
        return output;
    }

    /**
     * The addresses the case model's mail process tasks send to (welcome mail, IT notified, onboarding complete):
     * set on the Onboarding case file item before Employee details completes, so the welcome mail has them.
     */
    private void mailRecipients(FormContext ctx, String privateEmail, Employee manager) {
        String username = db.sql("SELECT username FROM employee WHERE employee_id = ?").param(ctx.employeeId())
                .query(String.class).single();
        String itEmail = db.sql("SELECT user_id FROM employee WHERE role = 'it_admin' AND user_id IS NOT NULL "
                        + "ORDER BY employee_id LIMIT 1").query(String.class).optional().map(keycloak::email).orElse("");
        Map<String, String> mail = new LinkedHashMap<>();
        mail.put("employeeEmail", privateEmail);
        mail.put("username", username);
        mail.put("managerEmail", keycloak.email(manager.userId()));
        mail.put("hrEmail", ctx.user().email() == null ? "" : ctx.user().email());
        mail.put("itEmail", itEmail);
        engine.put("/cases/" + ctx.caseId() + "/casefile/update/Onboarding", mail);
    }

    private void addToTeam(String caseId, String userId, String caseRole) {
        engine.post("/cases/" + caseId + "/caseteam/users", Map.of("userId", userId, "caseRoles", List.of(caseRole)));
    }

    /** firstname.lastname in plain ASCII, with a number added when taken. */
    private String uniqueUsername(String first, String last) {
        String base = Normalizer.normalize((first + "." + last).toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9.]", "");
        String candidate = base;
        for (int i = 2; employees.byUsername(candidate).isPresent(); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    /** Step 2 done: the request is sent; IT's checklist becomes what was requested, and the case flag starts the IT
     * admin's Equipment request. */
    private ObjectNode sendToIt(FormContext ctx) {
        db.sql("UPDATE onboarding SET it_status = 'SENT', it_sent_at = now() WHERE employee_id = ?")
                .param(ctx.employeeId()).update();
        Map<String, Object> request = db.sql("SELECT it_accessories::text AS accessories, it_accounts::text AS accounts "
                + "FROM onboarding WHERE employee_id = ?").param(ctx.employeeId()).query().singleRow();
        checklists.syncItItems(ctx.employeeId(), names(request.get("accessories")), names(request.get("accounts")));
        ObjectNode output = json.createObjectNode();
        // merged into the Onboarding item (no "replace"), so the mail addresses set in step 1 stay
        output.putObject("Onboarding").put("itRequestSent", true);
        return output;
    }

    /** The values of a JSON array column (e.g. the requested accounts). */
    private List<String> names(Object jsonArray) {
        if (jsonArray == null) {
            return List.of();
        }
        try {
            List<String> result = new ArrayList<>();
            json.readTree(jsonArray.toString()).forEach(n -> result.add(n.asText()));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** IT is done when every mandatory IT item (equipment, accounts) is ticked; the task shows them as checkboxes. */
    private ObjectNode itSetupDone(FormContext ctx) {
        List<String> open = db.sql("SELECT name FROM checklist_item WHERE employee_id = ? AND owner_role = 'IT_ADMIN' "
                + "AND mandatory AND status = 'OPEN' ORDER BY sort_order").param(ctx.employeeId()).query(String.class).list();
        if (!open.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tick all mandatory checklist items first: " + String.join(", ", open) + ".");
        }
        db.sql("UPDATE onboarding SET it_status = 'COMPLETED', it_completed_at = now() WHERE employee_id = ?")
                .param(ctx.employeeId()).update();
        return json.createObjectNode();
    }

    /** The employee confirmed: their assets are marked received when they ticked "I received all ...". */
    private ObjectNode confirmEquipment(FormContext ctx) {
        db.sql("UPDATE onboarding SET equipment_confirmed_at = now() WHERE employee_id = ?").param(ctx.employeeId()).update();
        db.sql("UPDATE asset SET received = coalesce((SELECT equipment_received FROM onboarding WHERE employee_id = ?), false) "
                + "WHERE employee_id = ?").params(ctx.employeeId(), ctx.employeeId()).update();
        checklists.markKindDone(ctx.employeeId(), "EQUIPMENT_CONFIRM", ctx.user());
        return json.createObjectNode();
    }
}
