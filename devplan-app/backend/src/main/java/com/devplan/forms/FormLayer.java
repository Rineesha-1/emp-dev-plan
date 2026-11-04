package com.devplan.forms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.forms.FormContext.Mode;
import com.devplan.forms.TableStore.Key;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The layer between a task form and where its data lives. The form (task-model) comes from the engine; it marks the
 * business data that belongs in the app database, everything else is case data for the engine:
 * <ul>
 * <li>{@code "x-store": "<table>"} on a top-level schema property: its value is read from and written to that app
 * table (TableStore; "assets" is the AssetStore). Inside a stored object, a list property with its own x-store is a
 * child table (Plan.goals in dev_goal).</li>
 * <li>{@code "x-case": true} on a field inside a stored property: the field is ALSO sent to the case file (goal
 * titles, decisions, statuses the sentries and the case history need). Without it a stored field stays in the DB.</li>
 * <li>{@code "x-column": "<column>"} on a field: its column, when it is not the field name in snake_case.</li>
 * <li>{@code "x-options": "managers" | "goals" | ...} on a field: its choices come from the app DB (Lookups).</li>
 * <li>{@code "x-on-complete": "<action>"} on a stored property: runs that backend action when the task is completed
 * (OnboardingActions, DevelopmentActions); it may return case outputs.</li>
 * </ul>
 * On read the DB values are merged into the engine's form data; on write they are saved and split off, and only the
 * case part goes to the engine. Development plan tables belong to a plan round (Rounds). A task-model without these
 * keys passes through unchanged.
 */
@Component
public class FormLayer {
    private final TableStore tables;
    private final AssetStore assets;
    private final Lookups lookups;
    private final StoreRules rules;
    private final Rounds rounds;
    private final List<FormActions> actions;

    public FormLayer(TableStore tables, AssetStore assets, Lookups lookups, StoreRules rules, Rounds rounds,
                     List<FormActions> actions) {
        this.tables = tables;
        this.assets = assets;
        this.lookups = lookups;
        this.rules = rules;
        this.rounds = rounds;
        this.actions = actions;
    }

    /** The context with the plan round its rows belong to, when the form stores round data. */
    public FormContext forRead(JsonNode taskModel, FormContext ctx) {
        return ctx.roundId() == null && usesRounds(taskModel) ? ctx.withRound(rounds.forRead(ctx)) : ctx;
    }

    private FormContext forWrite(JsonNode taskModel, FormContext ctx) {
        return ctx.roundId() == null && usesRounds(taskModel) ? ctx.withRound(rounds.forWrite(ctx)) : ctx;
    }

    /** A copy of the task-model with every "x-options" field given its "oneOf" choices from the DB. */
    public JsonNode prepareModel(JsonNode taskModel, FormContext ctx) {
        JsonNode copy = taskModel.deepCopy();
        resolveOptions(copy.path("schema"), ctx);
        return copy;
    }

    private void resolveOptions(JsonNode node, FormContext ctx) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.has("x-options")) {
                List<Lookups.Option> options = lookups.options(object.path("x-options").asText(), ctx);
                if (!options.isEmpty()) {
                    ArrayNode oneOf = object.putArray("oneOf");
                    options.forEach(o -> oneOf.addObject().put("const", o.value()).put("title", o.title()));
                }
            }
            object.elements().forEachRemaining(child -> resolveOptions(child, ctx));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> resolveOptions(child, ctx));
        }
    }

    public boolean hasStores(JsonNode taskModel) {
        return !stored(taskModel).isEmpty();
    }

    /** True for a stored object that is one row per task (an activity, a progress review), not data of the round. */
    public boolean isTaskRow(JsonNode property) {
        String store = property.path("x-store").asText();
        return "object".equals(property.path("type").asText()) && !store.isEmpty() && !AssetStore.NAME.equals(store)
                && tables.has(store, "task_id");
    }

    /** True when the form has DB data that may be changed after completion: stores without case fields (onboarding).
     * A form that also feeds the case file is final once completed. */
    public boolean editableAfterCompletion(JsonNode taskModel) {
        return hasStores(taskModel) && stored(taskModel).stream().noneMatch(p -> hasCaseFields(p.getValue()));
    }

    /** Read: puts the DB value of every stored property into the form data (ctx from forRead). */
    public void merge(JsonNode taskModel, ObjectNode formData, FormContext ctx) {
        for (var p : stored(taskModel)) {
            String store = p.getValue().path("x-store").asText();
            JsonNode schema = p.getValue();
            if (AssetStore.NAME.equals(store)) {
                requireEmployee(ctx);
                formData.set(p.getKey(), assets.load(ctx.employeeId(), schema));
            } else if ("array".equals(schema.path("type").asText())) {
                Key parent = listKey(store, ctx);
                if (parent != null) {
                    formData.set(p.getKey(), tables.loadList(store, parent, schema));
                }
            } else {
                Key key = objectKey(store, ctx);
                if (key != null) {
                    ObjectNode merged = formData.path(p.getKey()).isObject()
                            ? ((ObjectNode) formData.get(p.getKey())).deepCopy() : formData.objectNode();
                    merged.setAll(tables.load(store, key, schema));
                    formData.set(p.getKey(), merged);
                }
            }
        }
    }

    /**
     * Write: saves the stored properties to the DB and returns the form data with only their case fields left, plus
     * the case outputs of the x-on-complete actions (COMPLETE only). Read-only stored properties are never written.
     */
    public ObjectNode split(JsonNode taskModel, ObjectNode formData, FormContext ctx, Mode mode) {
        List<Map.Entry<String, JsonNode>> stored = stored(taskModel);
        if (stored.isEmpty()) {
            return formData.deepCopy();
        }
        ctx = forWrite(taskModel, ctx);
        ObjectNode caseData = formData.deepCopy();
        List<String> toRun = new ArrayList<>();
        for (var p : stored) {
            String property = p.getKey();
            JsonNode schema = p.getValue();
            String store = schema.path("x-store").asText();
            JsonNode value = caseData.remove(property);
            boolean readOnly = schema.path("readOnly").asBoolean(false);
            if (readOnly || value == null || value.isNull()) {
                continue;
            }
            JsonNode caseValue = caseFields(schema, value);
            if (caseValue != null) {
                caseData.set(property, caseValue);
            }
            if (AssetStore.NAME.equals(store)) {
                requireEmployee(ctx);
                requireItems(schema, value); // a row cannot be stored half-filled
                assets.save(ctx.employeeId(), value, schema, ctx.user());
            } else if ("array".equals(schema.path("type").asText())) {
                requireItems(schema, value);
                tables.saveList(store, listKey(store, ctx), value, schema);
            } else {
                if (!value.isObject()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, property + " must be an object");
                }
                requireFields(schema, value);
                rules.check(store, ctx.employeeId(), value);
                Key key = objectKey(store, ctx);
                Map<String, Object> insert = null;
                if ("task_id".equals(key.column())) { // a row per task (an activity, a review) is made on its first save
                    insert = new LinkedHashMap<>();
                    insert.put("round_id", ctx.roundId());
                    insert.put("created_by", ctx.user().name());
                }
                tables.save(store, key, (ObjectNode) value, schema, insert,
                        ctx.roundId() == null ? null : new Key("round_id", ctx.roundId()));
            }
            if (mode == Mode.COMPLETE && schema.hasNonNull("x-on-complete")) {
                toRun.add(schema.path("x-on-complete").asText());
            }
        }
        for (String action : toRun) { // after every store is saved, so actions see the whole form
            FormContext context = ctx;
            FormActions handler = actions.stream().filter(a -> a.handles(action)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown form action " + action));
            deepMerge(caseData, handler.run(action, context));
        }
        return caseData;
    }

    /** The row of a stored object: per task (task_id), the round (dev_round) or the employee. Null: no round yet. */
    private Key objectKey(String table, FormContext ctx) {
        if (tables.has(table, "task_id")) {
            return new Key("task_id", ctx.taskId());
        }
        if (tables.has(table, "round_no")) {
            return ctx.roundId() == null ? null : new Key("id", ctx.roundId());
        }
        requireEmployee(ctx);
        return new Key("employee_id", ctx.employeeId());
    }

    /** The parent of a stored list: the round, or the employee. Null: no round yet. */
    private Key listKey(String table, FormContext ctx) {
        if (tables.has(table, "round_id")) {
            return ctx.roundId() == null ? null : new Key("round_id", ctx.roundId());
        }
        requireEmployee(ctx);
        return new Key("employee_id", ctx.employeeId());
    }

    private boolean usesRounds(JsonNode taskModel) {
        return stored(taskModel).stream().anyMatch(p -> {
            String store = p.getValue().path("x-store").asText();
            return !AssetStore.NAME.equals(store) && (tables.has(store, "round_id") || tables.has(store, "round_no"));
        });
    }

    /** The part of a stored value that goes to the case file: the fields marked x-case (null: none). */
    private static JsonNode caseFields(JsonNode schema, JsonNode value) {
        if (schema.path("x-case").asBoolean(false)) {
            return value;
        }
        if (!hasCaseFields(schema) || value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            ArrayNode result = ((ArrayNode) value).arrayNode();
            value.forEach(item -> {
                JsonNode part = caseFields(schema.path("items"), item);
                result.add(part == null ? result.objectNode() : part);
            });
            return result;
        }
        if (!value.isObject()) {
            return null;
        }
        ObjectNode result = ((ObjectNode) value).objectNode();
        schema.path("properties").properties().forEach(p -> {
            JsonNode part = caseFields(p.getValue(), value.get(p.getKey()));
            if (part != null) {
                result.set(p.getKey(), part);
            }
        });
        return result;
    }

    private static boolean hasCaseFields(JsonNode schema) {
        if (schema.path("x-case").asBoolean(false)) {
            return true;
        }
        for (JsonNode p : schema.path("properties")) {
            if (hasCaseFields(p)) {
                return true;
            }
        }
        return schema.has("items") && hasCaseFields(schema.path("items"));
    }

    /** Objects are merged field by field (an action adds roundId to Plan), other values replaced. */
    private static void deepMerge(ObjectNode target, ObjectNode source) {
        source.properties().forEach(p -> {
            JsonNode existing = target.get(p.getKey());
            if (existing != null && existing.isObject() && p.getValue().isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) p.getValue());
            } else {
                target.set(p.getKey(), p.getValue());
            }
        });
    }

    private static List<Map.Entry<String, JsonNode>> stored(JsonNode taskModel) {
        List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
        taskModel.path("schema").path("properties").properties().forEach(p -> {
            if (p.getValue().hasNonNull("x-store")) {
                result.add(p);
            }
        });
        return result;
    }

    private static void requireEmployee(FormContext ctx) {
        if (ctx.employeeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This task has no Employee.employeeId input");
        }
    }

    /** The FRD's "This field is required." for every required field of the object that is empty, also in its lists. */
    private static void requireFields(JsonNode objectSchema, JsonNode value) {
        for (JsonNode name : objectSchema.path("required")) {
            JsonNode field = value.path(name.asText());
            boolean empty = field.isMissingNode() || field.isNull() || (field.isTextual() && field.asText().isBlank())
                    || (field.isContainerNode() && field.isEmpty());
            if (empty) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, title(objectSchema, name.asText()) + ": This field is required.");
            }
        }
        objectSchema.path("properties").properties().forEach(p -> {
            if ("array".equals(p.getValue().path("type").asText()) && value.path(p.getKey()).isArray()
                    && !p.getValue().path("readOnly").asBoolean(false)) {
                requireItems(p.getValue(), value.get(p.getKey()));
            }
        });
    }

    private static void requireItems(JsonNode arraySchema, JsonNode value) {
        if (!value.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The list must be a list");
        }
        int min = arraySchema.path("minItems").asInt(0);
        if (value.size() < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    arraySchema.path("title").asText("The list") + ": add at least " + min + ".");
        }
        if (arraySchema.path("items").path("type").asText().equals("object")) {
            for (JsonNode item : value) {
                requireFields(arraySchema.path("items"), item);
            }
        }
    }

    private static String title(JsonNode objectSchema, String name) {
        return objectSchema.path("properties").path(name).path("title").asText(name);
    }
}
