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
import org.springframework.jdbc.core.simple.JdbcClient;
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
import com.devplan.forms.FormContext;
import com.devplan.forms.FormLayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private final JdbcClient db;
    private final FormLayer forms;
    private final ObjectMapper json;

    public CaseController(DevplanProperties props, EngineClient engine, Employees employees, JdbcClient db,
                          FormLayer forms, ObjectMapper json) {
        this.props = props;
        this.engine = engine;
        this.employees = employees;
        this.db = db;
        this.forms = forms;
        this.json = json;
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
        if (existing != null && "onboarding".equals(phase(existing))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, employee.name() + " is still onboarding");
        }
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
        Set<String> raisable = raisableEvents(caseId);
        result.put("raisableEvents", raisable);
        result.put("stages", stages(caseId, engineCase, raisable));
        result.put("discretionaryItems", engine.get("/cases/" + caseId + "/discretionaryitems").path("discretionaryItems"));
        result.put("tasks", engine.get("/tasks/case/" + caseId));
        return result;
    }

    /**
     * The top-level stages of the case plan as the definition has them (so a stage added in the Case Designer shows
     * up without UI changes), each with its state and the user events this user may raise for it now: the events
     * that start it (its entry criteria) and the available events inside it. While onboarding runs, only the
     * onboarding stage offers events; "Complete onboarding" is left to the onboarding checklist, which checks it.
     * The onboarding stage is left out of cases where it never runs (employees who were not onboarded here).
     */
    private List<Map<String, Object>> stages(String caseId, JsonNode engineCase, Set<String> raisable) {
        Document definition = parseXml(engine.get("/cases/" + caseId + "/definition").asText());
        Map<String, Element> byId = new HashMap<>();
        NodeList all = definition.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (!e.getAttribute("id").isEmpty()) {
                byId.putIfAbsent(e.getAttribute("id"), e);
            }
        }
        Element casePlan = (Element) definition.getElementsByTagName("casePlanModel").item(0);

        Map<String, JsonNode> instances = new HashMap<>(); // runtime plan item id -> plan item
        Map<String, JsonNode> latestByDefinition = new HashMap<>();
        engineCase.path("planitems").forEach(p -> {
            instances.put(p.path("id").asText(), p);
            latestByDefinition.put(p.path("definitionId").asText(), p);
        });
        boolean onboarding = "onboarding".equals(phase(engineCase));

        List<Map<String, Object>> result = new ArrayList<>();
        for (org.w3c.dom.Node n = casePlan.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element planItem) || !"planItem".equals(planItem.getTagName())) {
                continue;
            }
            Element def = byId.get(planItem.getAttribute("definitionRef"));
            if (def == null || !"stage".equals(def.getTagName())) {
                continue;
            }
            String stageId = planItem.getAttribute("id");
            JsonNode instance = latestByDefinition.get(stageId);
            String state = instance == null ? "Available" : instance.path("currentState").asText();
            boolean isOnboarding = OnboardingController.STAGE.equals(stageId);
            if (isOnboarding && "Available".equals(state)) {
                continue;
            }

            Set<String> startEvents = new HashSet<>(); // definition ids of the events in its entry criteria
            NodeList criteria = planItem.getElementsByTagName("entryCriterion");
            for (int i = 0; i < criteria.getLength(); i++) {
                Element sentry = byId.get(((Element) criteria.item(i)).getAttribute("sentryRef"));
                NodeList onParts = sentry == null ? null : sentry.getElementsByTagName("planItemOnPart");
                for (int j = 0; onParts != null && j < onParts.getLength(); j++) {
                    startEvents.add(((Element) onParts.item(j)).getAttribute("sourceRef"));
                }
            }
            List<Map<String, String>> events = new ArrayList<>();
            for (JsonNode p : instances.values()) {
                String definitionId = p.path("definitionId").asText();
                if (!"UserEvent".equals(p.path("type").asText()) || !"Available".equals(p.path("currentState").asText())
                        || !raisable.contains(definitionId) || OnboardingController.COMPLETE_EVENT.equals(definitionId)
                        || (onboarding && !isOnboarding)) {
                    continue;
                }
                boolean starts = startEvents.contains(definitionId) && "Available".equals(state);
                boolean inside = instance != null && "Active".equals(state) && insideActive(p, instance, instances);
                if (starts || inside) {
                    events.add(Map.of("id", p.path("id").asText(), "name", p.path("name").asText(),
                            "definitionId", definitionId));
                }
            }
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("planItemId", stageId);
            stage.put("name", planItem.getAttribute("name"));
            stage.put("state", state);
            stage.put("events", events);
            result.add(stage);
        }
        return result;
    }

    /** True when the plan item sits (at any depth) in the given stage instance and every stage on the way is Active. */
    private static boolean insideActive(JsonNode item, JsonNode stage, Map<String, JsonNode> instances) {
        String parent = item.path("stageId").asText(null);
        while (parent != null && !parent.isEmpty()) {
            JsonNode p = instances.get(parent);
            if (p == null || !"Active".equals(p.path("currentState").asText())) {
                return false;
            }
            if (parent.equals(stage.path("id").asText())) {
                return true;
            }
            parent = p.path("stageId").asText(null);
        }
        return false;
    }

    /** The case plan stage the development plan page shows. */
    static final String DEVELOPMENT_STAGE = "pi_development_plan";

    /**
     * The development plan page, built from the case model so a stage, task or form field added in the Case Designer
     * shows up without UI changes:
     * <ul>
     * <li>steps: the stages inside "Development plan" (1 · Plan, 2 · Perform plan, 3 · Evaluate plan), each with the
     * human tasks of its latest run and the activities that can be added to it now;</li>
     * <li>record: a read-only form made of the stored properties of all development task forms (Plan, Approval,
     * Activities, reviews, self-assessment, evaluation; one group each), and the data of every plan round from the
     * devplan DB in it.</li>
     * </ul>
     */
    @GetMapping("/{caseId}/plan")
    public Map<String, Object> plan(@PathVariable String caseId) {
        JsonNode engineCase = engine.get("/cases/" + caseId); // the engine decides whether this user may see it
        Document definition = parseXml(engine.get("/cases/" + caseId + "/definition").asText());
        Map<String, Element> byId = new HashMap<>();
        NodeList all = definition.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (!e.getAttribute("id").isEmpty()) {
                byId.putIfAbsent(e.getAttribute("id"), e);
            }
        }
        Map<String, JsonNode> instances = new HashMap<>();
        Map<String, JsonNode> latestByDefinition = new HashMap<>();
        engineCase.path("planitems").forEach(p -> {
            instances.put(p.path("id").asText(), p);
            latestByDefinition.put(p.path("definitionId").asText(), p);
        });
        JsonNode tasks = engine.get("/tasks/case/" + caseId);
        JsonNode discretionary = engine.get("/cases/" + caseId + "/discretionaryitems").path("discretionaryItems");

        List<Map<String, Object>> steps = new ArrayList<>();
        List<Element> taskModels = new ArrayList<>(); // human task definitions, in model order
        Element development = byId.get(DEVELOPMENT_STAGE);
        Element developmentStage = development == null ? null : byId.get(development.getAttribute("definitionRef"));
        for (Element step : childPlanItems(developmentStage)) {
            Element stageDef = byId.get(step.getAttribute("definitionRef"));
            if (stageDef == null || !"stage".equals(stageDef.getTagName())) {
                continue;
            }
            String stepId = step.getAttribute("id");
            JsonNode instance = latestByDefinition.get(stepId);
            List<Element> humanTasks = new ArrayList<>();
            collectHumanTasks(stageDef, byId, humanTasks);
            taskModels.addAll(humanTasks.stream().map(h -> byId.get(h.getAttribute("definitionRef"))).toList());
            Set<String> taskIds = new HashSet<>();
            humanTasks.forEach(h -> taskIds.add(h.getAttribute("id")));
            List<JsonNode> stepTasks = new ArrayList<>();
            if (instance != null) {
                tasks.forEach(task -> {
                    JsonNode item = instances.get(task.path("id").asText());
                    if (item != null && taskIds.contains(item.path("definitionId").asText())
                            && within(item, instance.path("id").asText(), instances)) {
                        stepTasks.add(((ObjectNode) task.deepCopy()).without(List.of("taskModel", "input", "output")));
                    }
                });
            }
            List<JsonNode> addable = new ArrayList<>();
            if (instance != null && "Active".equals(instance.path("currentState").asText())) {
                discretionary.forEach(d -> {
                    JsonNode parent = instances.get(d.path("parentId").asText());
                    if (parent != null && "Active".equals(parent.path("currentState").asText())
                            && within(parent, instance.path("id").asText(), instances)) {
                        addable.add(d);
                    }
                });
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("planItemId", stepId);
            s.put("name", step.getAttribute("name"));
            s.put("state", instance == null ? "Available" : instance.path("currentState").asText());
            s.put("tasks", stepTasks);
            s.put("discretionaryItems", addable);
            steps.add(s);
        }

        ObjectNode schema = json.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode groups = json.createArrayNode();
        for (Element humanTask : taskModels) {
            JsonNode model = taskModel(humanTask);
            model.path("schema").path("properties").properties().forEach(p -> {
                JsonNode property = p.getValue();
                if (!property.hasNonNull("x-store") || forms.isTaskRow(property) || properties.has(p.getKey())) {
                    return; // the record shows the round, not one task's row
                }
                ObjectNode copy = ((ObjectNode) property.deepCopy()).put("readOnly", true);
                copy.remove("x-on-complete");
                withoutRequired(copy); // nothing is filled in here
                properties.set(p.getKey(), copy);
                // One card per property; an object's fields are laid out in it directly, lists as tables.
                ObjectNode group = groups.addObject().put("type", "Group").put("label", property.path("title").asText(p.getKey()));
                group.putObject("options").put("readonly", true);
                ArrayNode elements = group.putArray("elements");
                String scope = "#/properties/" + p.getKey();
                if ("object".equals(property.path("type").asText())) {
                    property.path("properties").properties().forEach(f -> elements.add(control(scope + "/properties/" + f.getKey(), f.getValue())));
                } else {
                    elements.add(control(scope, property).put("label", false));
                }
            });
        }
        ObjectNode recordModel = json.createObjectNode();
        recordModel.set("schema", schema);
        recordModel.putObject("uiSchema").put("type", "VerticalLayout").set("elements", groups);

        String employeeId = engineCase.path("file").path("Employee").path("employeeId").asText(null);
        List<Map<String, Object>> rounds = new ArrayList<>();
        db.sql("SELECT id, round_no, status, period, created_at FROM dev_round WHERE case_id = ? ORDER BY round_no DESC")
                .param(caseId).query().listOfRows().forEach(row -> {
                    ObjectNode data = json.createObjectNode();
                    FormContext ctx = new FormContext(caseId, employeeId, CurrentUser.get(), null, false,
                            ((Number) row.get("id")).longValue());
                    forms.merge(recordModel, data, ctx);
                    Map<String, Object> r = new LinkedHashMap<>(row);
                    r.put("data", data);
                    rounds.add(r);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("employee", engineCase.path("file").path("Employee"));
        result.put("phase", phase(engineCase));
        result.put("round", round(engineCase, phase(engineCase)));
        result.put("steps", steps);
        result.put("record", Map.of("taskModel", recordModel, "rounds", rounds));
        return result;
    }

    private ObjectNode control(String scope, JsonNode property) {
        ObjectNode control = json.createObjectNode().put("type", "Control").put("scope", scope);
        if ("array".equals(property.path("type").asText())) {
            control.putObject("options").put("table", true);
        } else if ("string".equals(property.path("type").asText()) && !property.has("oneOf") && !property.has("format")) {
            control.putObject("options").put("multi", true);
        }
        return control;
    }

    private static void withoutRequired(JsonNode schema) {
        if (schema instanceof ObjectNode object) {
            object.remove(List.of("required", "minItems"));
            object.elements().forEachRemaining(CaseController::withoutRequired);
        }
    }

    /** The plan items and discretionary items directly in a stage definition, in model order. */
    private static List<Element> childPlanItems(Element stage) {
        List<Element> result = new ArrayList<>();
        if (stage == null) {
            return result;
        }
        for (org.w3c.dom.Node n = stage.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "planItem".equals(e.getTagName())) {
                result.add(e);
            }
            if (n instanceof Element e && "planningTable".equals(e.getTagName())) {
                for (org.w3c.dom.Node d = e.getFirstChild(); d != null; d = d.getNextSibling()) {
                    if (d instanceof Element item && "discretionaryItem".equals(item.getTagName())) {
                        result.add(item);
                    }
                }
            }
        }
        return result;
    }

    /** The human task plan items in a stage and its sub-stages, in model order. */
    private static void collectHumanTasks(Element stage, Map<String, Element> byId, List<Element> result) {
        for (Element item : childPlanItems(stage)) {
            Element def = byId.get(item.getAttribute("definitionRef"));
            if (def == null) {
                continue;
            }
            if ("humanTask".equals(def.getTagName())) {
                result.add(item);
            } else if ("stage".equals(def.getTagName())) {
                collectHumanTasks(def, byId, result);
            }
        }
    }

    private JsonNode taskModel(Element humanTask) {
        NodeList models = humanTask == null ? null : humanTask.getElementsByTagName("task-model");
        if (models == null || models.getLength() == 0) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(models.item(0).getTextContent());
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** True when the plan item sits (at any depth) in the stage instance with this id. */
    private static boolean within(JsonNode item, String stageId, Map<String, JsonNode> instances) {
        if (item.path("id").asText().equals(stageId)) {
            return true;
        }
        String parent = item.path("stageId").asText(null);
        while (parent != null && !parent.isEmpty()) {
            if (parent.equals(stageId)) {
                return true;
            }
            JsonNode p = instances.get(parent);
            parent = p == null ? null : p.path("stageId").asText(null);
        }
        return false;
    }

    /** The plan round number from the devplan DB; a plan that runs but has no round saved yet is the next one. */
    private int round(JsonNode engineCase, String phase) {
        List<Map<String, Object>> latest = db.sql("SELECT round_no, status FROM dev_round WHERE case_id = ? "
                + "ORDER BY round_no DESC LIMIT 1").param(engineCase.path("id").asText()).query().listOfRows();
        int number = latest.isEmpty() ? 0 : ((Number) latest.get(0).get("round_no")).intValue();
        boolean closed = latest.isEmpty() || "CLOSED".equals(latest.get(0).get("status"));
        return RUNNING.contains(phase) && closed ? number + 1 : number;
    }

    /** Where the current plan round is: plan, perform or evaluate; onboarding while a new joiner is onboarded;
     * waiting when no round runs; else the case state. */
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
        if (activeStages.contains(OnboardingController.STAGE)) {
            return "onboarding";
        }
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

    static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Case definition is not readable");
        }
    }

    /** Raises a user event. While onboarding runs nothing else can be started (development plan, appraisal, ...),
     * and Complete onboarding goes through the onboarding checklist, which checks the mandatory items (ONB-05). */
    @PostMapping("/{caseId}/events/{planItemId}")
    public void raiseEvent(@PathVariable String caseId, @PathVariable String planItemId) {
        JsonNode engineCase = engine.get("/cases/" + caseId);
        Map<String, JsonNode> instances = new HashMap<>();
        engineCase.path("planitems").forEach(p -> instances.put(p.path("id").asText(), p));
        JsonNode event = instances.get(planItemId);
        if (event != null && OnboardingController.COMPLETE_EVENT.equals(event.path("definitionId").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use Complete onboarding on the onboarding checklist");
        }
        if ("onboarding".equals(phase(engineCase))) {
            JsonNode onboardingStage = instances.values().stream()
                    .filter(p -> OnboardingController.STAGE.equals(p.path("definitionId").asText())).findFirst().orElse(null);
            if (event == null || onboardingStage == null || !insideActive(event, onboardingStage, instances)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Onboarding is not complete yet");
            }
        }
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
