package com.devplan.api;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.EngineClient;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Development cases. The engine is the system of record: lists, case data (the case file) and permissions all come
 * from the engine, called with the user's own token. Only starting a case reads this app's employee directory.
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {
    private static final List<String> TRIGGERS =
            List.of("ANNUAL_REVIEW", "NEW_ROLE", "CAREER_GROWTH", "PERFORMANCE_IMPROVEMENT", "ONBOARDING");

    private final DevplanProperties props;
    private final EngineClient engine;
    private final Employees employees;

    public CaseController(DevplanProperties props, EngineClient engine, Employees employees) {
        this.props = props;
        this.engine = engine;
        this.employees = employees;
    }

    /** The active development cases this user may see (the engine filters on case team and tenant roles). */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode c : activeCases()) {
            String phase = phase(c);
            result.add(Map.of("caseId", c.path("id").asText(), "employee", c.path("file").path("Employee"),
                    "startedAt", c.path("createdOn").asText(), "state", c.path("state").asText(), "phase", phase,
                    "round", round(c, phase)));
        }
        return result;
    }

    /** The active development cases this user may see, each read in full (the list has no case file). */
    private List<JsonNode> activeCases() {
        JsonNode cases = engine.get("/cases?tenant=" + props.engine().tenant() + "&definition=development"
                + "&state=Active&numberOfResults=500");
        List<JsonNode> result = new ArrayList<>();
        cases.forEach(summary -> result.add(engine.get("/cases/" + summary.path("id").asText())));
        return result;
    }

    public record StartRequest(String employeeId, String trigger) {
    }

    /**
     * HR (anyone) or a manager (own reports) starts a development case; the team gets Employee + Manager.
     * An employee has at most one active case. If it has no plan running (closed, or never started), the new plan is
     * a new round in that case: the Employee item gets the new reason and Start development plan is raised.
     * Synchronized so that two quick requests for the same employee cannot both pass the check.
     */
    @PostMapping
    public synchronized Map<String, String> start(@RequestBody StartRequest request) {
        CurrentUser user = CurrentUser.get();
        if (!user.has("hr") && !user.has("manager")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HR or a manager can start a development case");
        }
        if (request.trigger() == null || !TRIGGERS.contains(request.trigger())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the reason for the plan");
        }
        Employee employee = employees.visibleTo(user).stream()
                .filter(e -> e.employeeId().equals(request.employeeId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your employee"));
        Employee manager = employee.managerId() == null ? null : employees.byId(employee.managerId()).orElse(null);
        if (employee.userId() == null || manager == null || manager.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Employee and manager need a Keycloak account");
        }
        JsonNode existing = activeCases().stream()
                .filter(c -> employee.employeeId().equals(c.path("file").path("Employee").path("employeeId").asText()))
                .findFirst().orElse(null);
        if (existing != null && RUNNING.contains(phase(existing))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, employee.name() + " already has a development plan running");
        }
        Map<String, Object> employeeInput = new LinkedHashMap<>();
        employeeInput.put("employeeId", employee.employeeId());
        employeeInput.put("name", employee.name());
        employeeInput.put("jobTitle", employee.jobTitle());
        employeeInput.put("managerId", manager.employeeId());
        employeeInput.put("managerName", manager.name());
        employeeInput.put("trigger", request.trigger());
        if (existing != null) {
            return Map.of("caseId", startNewRound(existing, employeeInput));
        }
        Map<String, Object> body = Map.of(
                "definition", props.engine().definition(),
                "tenant", props.engine().tenant(),
                "inputs", Map.of("Employee", employeeInput),
                "caseTeam", Map.of(
                        "users", List.of(
                                Map.of("userId", employee.userId(), "caseRoles", List.of("Employee")),
                                Map.of("userId", manager.userId(), "caseRoles", List.of("Manager"), "isOwner", true)),
                        "tenantRoles", List.of(Map.of("tenantRole", "HR", "caseRoles", List.of("HR"))),
                        "groups", List.of()));
        return Map.of("caseId", engine.post("/cases", body).path("caseInstanceId").asText());
    }

    private static final List<String> RUNNING = List.of("plan", "perform", "evaluate");

    /** Refreshes the Employee item (new reason, current job and manager) and raises Start development plan. */
    private String startNewRound(JsonNode existing, Map<String, Object> employeeInput) {
        String caseId = existing.path("id").asText();
        Set<String> activeStages = new HashSet<>();
        existing.path("planitems").forEach(p -> {
            if ("Active".equals(p.path("currentState").asText())) {
                activeStages.add(p.path("id").asText());
            }
        });
        String startEvent = null;
        for (JsonNode p : existing.path("planitems")) {
            if ("pi_start_plan".equals(p.path("definitionId").asText()) && "Available".equals(p.path("currentState").asText())
                    && activeStages.contains(p.path("stageId").asText())) {
                startEvent = p.path("id").asText();
            }
        }
        if (startEvent == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The existing case cannot start a new plan right now");
        }
        engine.put("/cases/" + caseId + "/casefile/update/Employee", employeeInput);
        engine.post("/cases/" + caseId + "/planitems/" + startEvent + "/Occur", null);
        return caseId;
    }

    /** The case as the engine has it: case file, plan items, phase, events this user may raise, activities, tasks. */
    @GetMapping("/{caseId}")
    public Map<String, Object> get(@PathVariable String caseId) {
        JsonNode engineCase = engine.get("/cases/" + caseId); // the engine decides whether this user may see it
        String phase = phase(engineCase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("state", engineCase.path("state").asText());
        result.put("phase", phase);
        result.put("round", round(engineCase, phase));
        result.put("caseFile", engineCase.path("file"));
        result.put("planItems", engineCase.path("planitems"));
        result.put("raisableEvents", raisableEvents(caseId));
        result.put("discretionaryItems", engine.get("/cases/" + caseId + "/discretionaryitems").path("discretionaryItems"));
        result.put("tasks", engine.get("/tasks/case/" + caseId));
        return result;
    }

    /** Finished rounds are in PreviousRounds; a running round counts as the next one. */
    private static int round(JsonNode engineCase, String phase) {
        int finished = engineCase.path("file").path("PreviousRounds").size();
        return List.of("plan", "perform", "evaluate").contains(phase) ? finished + 1 : finished;
    }

    /** Where the current plan round is: plan, perform or evaluate; waiting when no round runs; else the case state. */
    static String phase(JsonNode engineCase) {
        String state = engineCase.path("state").asText();
        if (!"Active".equals(state)) {
            return state.toLowerCase();
        }
        Set<String> activeStages = new HashSet<>();
        engineCase.path("planitems").forEach(p -> {
            if ("Stage".equals(p.path("type").asText()) && "Active".equals(p.path("currentState").asText())) {
                activeStages.add(p.path("definitionId").asText());
            }
        });
        if (activeStages.contains("pi_evaluate")) {
            return "evaluate";
        }
        if (activeStages.contains("pi_perform")) {
            return "perform";
        }
        return activeStages.contains("pi_plan") ? "plan" : "waiting";
    }

    /**
     * Plan item ids (definitionId) of the user events this user may raise: the userEvent's authorizedRoleRefs from
     * the case's own definition, matched against the user's roles in this case team (directly or via a tenant role).
     */
    private Set<String> raisableEvents(String caseId) {
        CurrentUser user = CurrentUser.get();
        JsonNode team = engine.get("/cases/" + caseId + "/caseteam");
        Set<String> myRoles = new HashSet<>();
        team.path("users").forEach(u -> {
            if (user.userId().equals(u.path("userId").asText())) {
                u.path("caseRoles").forEach(r -> myRoles.add(r.asText()));
            }
        });
        team.path("tenantRoles").forEach(t -> {
            if (user.caseRoles().contains(t.path("tenantRole").asText())) {
                t.path("caseRoles").forEach(r -> myRoles.add(r.asText()));
            }
        });

        Document definition = parseXml(engine.get("/cases/" + caseId + "/definition").asText());
        Map<String, String> roleNames = new HashMap<>();
        Map<String, String> eventRoleRefs = new HashMap<>();
        NodeList roles = definition.getElementsByTagName("role");
        for (int i = 0; i < roles.getLength(); i++) {
            Element role = (Element) roles.item(i);
            roleNames.put(role.getAttribute("id"), role.getAttribute("name"));
        }
        NodeList events = definition.getElementsByTagName("userEvent");
        for (int i = 0; i < events.getLength(); i++) {
            Element event = (Element) events.item(i);
            eventRoleRefs.put(event.getAttribute("id"), event.getAttribute("authorizedRoleRefs").trim());
        }
        Set<String> result = new HashSet<>();
        NodeList planItems = definition.getElementsByTagName("planItem");
        for (int i = 0; i < planItems.getLength(); i++) {
            Element planItem = (Element) planItems.item(i);
            String refs = eventRoleRefs.get(planItem.getAttribute("definitionRef"));
            if (refs == null) {
                continue;
            }
            boolean allowed = refs.isEmpty() || List.of(refs.split("\\s+")).stream()
                    .anyMatch(ref -> myRoles.contains(roleNames.get(ref)));
            if (allowed) {
                result.add(planItem.getAttribute("id"));
            }
        }
        return result;
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Case definition is not readable");
        }
    }

    @PostMapping("/{caseId}/events/{planItemId}")
    public void raiseEvent(@PathVariable String caseId, @PathVariable String planItemId) {
        engine.post("/cases/" + caseId + "/planitems/" + planItemId + "/Occur", null);
    }

    /** Plans a discretionary activity task; the body is the item as listed in discretionaryItems. */
    @PostMapping("/{caseId}/plan")
    public JsonNode plan(@PathVariable String caseId, @RequestBody JsonNode discretionaryItem) {
        Map<String, String> body = Map.of("name", discretionaryItem.path("name").asText(),
                "definitionId", discretionaryItem.path("definitionId").asText(),
                "parentId", discretionaryItem.path("parentId").asText());
        return engine.post("/cases/" + caseId + "/discretionaryitems/plan", body);
    }
}
