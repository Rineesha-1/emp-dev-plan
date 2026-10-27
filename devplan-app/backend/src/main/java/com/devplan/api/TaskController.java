package com.devplan.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

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
import com.devplan.engine.EngineClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Human tasks with their JSON forms; a thin layer over the engine. The form (task-model "jsonforms") and its data (the
 * task input, mapped from the case file by the engine) both come from the engine task; completing sends the form's
 * output field back, and the engine's output mappings write it to the case file. Nothing here is task-specific.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private static final Set<String> OPEN = Set.of("Unassigned", "Assigned", "Delegated");

    private final DevplanProperties props;
    private final EngineClient engine;
    private final ObjectMapper json;

    public TaskController(DevplanProperties props, EngineClient engine, ObjectMapper json) {
        this.props = props;
        this.engine = engine;
        this.json = json;
    }

    /** Open tasks for one of the user's case roles, each with the name of the employee whose plan it is. */
    @GetMapping
    public List<JsonNode> myTasks() {
        Set<String> roles = CurrentUser.get().caseRoles();
        String tenant = "?tenant=" + props.engine().tenant();
        Map<String, String> employeeNames = new HashMap<>(); // per case, read once
        JsonNode tasks = engine.get("/tasks" + tenant + "&numberOfResults=200");
        return StreamSupport.stream(tasks.spliterator(), false)
                .filter(t -> OPEN.contains(t.path("taskState").asText()))
                .filter(t -> roles.contains(t.path("role").asText()))
                .map(t -> (JsonNode) ((ObjectNode) t).put("employeeName", employeeNames.computeIfAbsent(
                        t.path("caseInstanceId").asText(), this::employeeName)))
                .toList();
    }

    /** The form and its data: the task input, with the submitted output for a completed task. */
    @GetMapping("/{taskId}")
    public Map<String, Object> form(@PathVariable String taskId) {
        JsonNode task = engine.get("/tasks/" + taskId);
        JsonNode model = taskModel(task);
        JsonNode form = model.path("jsonforms");
        String output = model.path("output").asText();

        ObjectNode formData = json.createObjectNode();
        task.path("input").properties().forEach(p -> {
            if (!p.getValue().isNull()) {
                formData.set(p.getKey(), p.getValue());
            }
        });
        JsonNode submitted = task.path("output").path(output);
        if (!submitted.isMissingNode() && !submitted.isNull()) {
            formData.set(output, submitted);
        }
        formData.set(output, withDefaults(formData.path(output), form.path("schema").path("properties").path(output)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", ((ObjectNode) task.deepCopy()).without(List.of("taskModel", "input", "output")));
        result.put("employeeName", formData.has("Employee") ? formData.path("Employee").path("name").asText(null)
                : employeeName(task.path("caseInstanceId").asText()));
        result.put("schema", form.path("schema"));
        result.put("uischema", form.path("uischema"));
        result.put("submitLabel", form.path("submitLabel").asText("Complete task"));
        result.put("formData", formData);
        return result;
    }

    private String employeeName(String caseId) {
        return engine.get("/cases/" + caseId).path("file").path("Employee").path("name").asText(null);
    }

    /** The output value for JSONForms: an empty list, or the object with the schema's defaults for missing fields. */
    private JsonNode withDefaults(JsonNode value, JsonNode schema) {
        if ("array".equals(schema.path("type").asText())) {
            return value.isArray() ? value : json.createArrayNode();
        }
        ObjectNode data = value.isObject() ? ((ObjectNode) value).deepCopy() : json.createObjectNode();
        schema.path("properties").properties().forEach(p -> {
            if (p.getValue().has("default") && !data.has(p.getKey())) {
                data.set(p.getKey(), p.getValue().get("default"));
            }
        });
        return data;
    }

    /** Completes the engine task with the form's output field; the engine writes it to the case file. */
    @PostMapping("/{taskId}/complete")
    public void complete(@PathVariable String taskId, @RequestBody JsonNode formData) {
        JsonNode task = engine.get("/tasks/" + taskId);
        String output = taskModel(task).path("output").asText();
        JsonNode data = formData.path(output);
        if (!data.isObject() && !data.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form field '" + output + "' is missing");
        }
        if ("Unassigned".equals(task.path("taskState").asText())) {
            engine.put("/tasks/" + taskId + "/claim", null);
        }
        engine.post("/tasks/" + taskId + "/complete", json.createObjectNode().set(output, data));
    }

    private JsonNode taskModel(JsonNode task) {
        JsonNode model = task.path("taskModel");
        if (model.isTextual()) {
            try {
                return json.readTree(model.asText());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Task has no valid form");
            }
        }
        return model;
    }
}
