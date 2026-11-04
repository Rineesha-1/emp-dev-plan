package com.devplan.forms;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generic mapping between form data and app tables: a schema property "firstName" is the column "first_name", or the
 * column its "x-column" names. Only properties declared in the task-model schema AND present as a column are read or
 * written, so a new DB field needs a column (Flyway) and a field in the task-model, no Java. System columns (keys,
 * statuses, who/when) are never written from a form.
 * <ul>
 * <li>An object is one row, found by a key (employee_id, task_id or the round id; see FormLayer).</li>
 * <li>A list is the rows of a parent (round_id = the round), in "seq" order when the table has that column, else in
 * insert order. Saving a list with seq replaces the parent's rows (element i = seq i); "x-rows": "update" only
 * updates the existing rows (e.g. per-goal results next to the goals).</li>
 * <li>A property inside a stored object that has its own "x-store" is a child list of the same parent key.</li>
 * </ul>
 */
@Component
public class TableStore {
    private static final Set<String> SYSTEM = Set.of("id", "employee_id", "employee_code", "user_id", "username",
            "stage", "case_id", "it_status", "it_sent_at", "it_completed_at",
            "equipment_confirmed_at", "completed_at", "completed_by", "name",
            "round_id", "round_no", "task_id", "seq", "created_by", "created_at", "approved_by", "approved_at",
            "self_submitted_at", "evaluated_by", "evaluated_at");

    /** Status columns the backend moves on (onboarding, round); an activity's own status is a form field. */
    private static final Set<String> SYSTEM_IN_TABLE = Set.of("onboarding.status", "dev_round.status");

    /** Which row(s): column = value. */
    public record Key(String column, Object value) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Map<String, Map<String, String>> columnTypes = new ConcurrentHashMap<>();

    public TableStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** column name -> SQL data type of a devplan table, read once; an unknown table is refused. */
    Map<String, String> columns(String table) {
        if (!table.matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-store " + table + " in the form");
        }
        Map<String, String> result = columnTypes.computeIfAbsent(table, t -> {
            Map<String, String> found = new HashMap<>();
            db.sql("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'devplan' "
                    + "AND table_name = ?").param(t)
                    .query((rs, n) -> found.put(rs.getString(1), rs.getString(2))).list();
            return found;
        });
        if (result.isEmpty()) {
            columnTypes.remove(table);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-store " + table + " in the form");
        }
        return result;
    }

    public boolean has(String table, String column) {
        return columns(table).containsKey(column);
    }

    public static String column(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** The column of a schema property: its "x-column", else its name in snake_case. */
    static String columnOf(String property, JsonNode propertySchema) {
        return propertySchema.path("x-column").asText(column(property));
    }

    /** The form object of the row with this key: the schema's properties filled from it (missing values left out). */
    public ObjectNode load(String table, Key key, JsonNode objectSchema) {
        List<Map<String, Object>> rows = db.sql("SELECT * FROM " + table + " WHERE " + key.column() + " = ?")
                .param(key.value()).query((rs, n) -> row(rs)).list();
        return rows.isEmpty() ? json.createObjectNode() : toObject(table, rows.get(0), objectSchema, parentKey(table, rows.get(0)));
    }

    /** The rows of a parent as a form list. */
    public ArrayNode loadList(String table, Key parent, JsonNode arraySchema) {
        String order = has(table, "seq") ? "seq" : "id";
        ArrayNode result = json.createArrayNode();
        db.sql("SELECT * FROM " + table + " WHERE " + parent.column() + " = ? ORDER BY " + order).param(parent.value())
                .query((rs, n) -> row(rs)).list()
                .forEach(row -> result.add(toObject(table, row, arraySchema.path("items"), parent)));
        return result;
    }

    /** The key children of this row hang off: round rows are parents of their goals by round_id. */
    private static Key parentKey(String table, Map<String, Object> row) {
        if (row.containsKey("round_no")) {
            return new Key("round_id", row.get("id"));
        }
        return row.containsKey("round_id") ? new Key("round_id", row.get("round_id")) : null;
    }

    private ObjectNode toObject(String table, Map<String, Object> row, JsonNode objectSchema, Key parent) {
        Map<String, String> columns = columns(table);
        ObjectNode result = json.createObjectNode();
        objectSchema.path("properties").properties().forEach(p -> {
            JsonNode schema = p.getValue();
            if (schema.hasNonNull("x-store")) { // a child list, e.g. Plan.goals
                if (parent != null) {
                    result.set(p.getKey(), loadList(schema.path("x-store").asText(), parent, schema));
                }
                return;
            }
            String column = columnOf(p.getKey(), schema);
            Object value = row.get(column);
            if (columns.containsKey(column) && value != null) {
                result.set(p.getKey(), toJson(value, columns.get(column)));
            }
        });
        return result;
    }

    /**
     * Writes the declared, writable properties of value to the row with this key. When there is no such row and
     * insertColumns is given, the row is inserted with them; otherwise a missing row is an error. Child lists are
     * saved under childParent (the round).
     */
    public void save(String table, Key key, ObjectNode value, JsonNode objectSchema, Map<String, Object> insertColumns,
                     Key childParent) {
        Map<String, Object> values = writable(table, value, objectSchema);
        if (!values.isEmpty() || insertColumns != null) {
            int updated = values.isEmpty() ? count(table, key) : update(table, key, null, values);
            if (updated == 0) {
                if (insertColumns == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + table + " record for this form");
                }
                Map<String, Object> all = new LinkedHashMap<>(insertColumns);
                all.put(key.column(), key.value());
                all.putAll(values);
                insert(table, all);
            }
        }
        children(objectSchema, value, (name, schema) -> {
            if (childParent == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, table + " has no child lists");
            }
            saveList(schema.path("x-store").asText(), childParent, value.get(name), schema);
        });
    }

    /** Saves a list under its parent: see the class comment. */
    public void saveList(String table, Key parent, JsonNode value, JsonNode arraySchema) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, arraySchema.path("title").asText("The list") + " must be a list");
        }
        if (!has(table, "seq")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, table + " lists are read-only in forms");
        }
        boolean updateOnly = "update".equals(arraySchema.path("x-rows").asText());
        JsonNode items = arraySchema.path("items");
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            Map<String, Object> values = item.isObject() ? writable(table, (ObjectNode) item, items) : Map.of();
            int updated = values.isEmpty() ? count(table, parent, i) : update(table, parent, i, values);
            if (updated == 0 && !updateOnly) {
                Map<String, Object> all = new LinkedHashMap<>();
                all.put(parent.column(), parent.value());
                all.put("seq", i);
                all.putAll(values);
                insert(table, all);
            }
        }
        if (!updateOnly) { // elements removed from the list
            db.sql("DELETE FROM " + table + " WHERE " + parent.column() + " = ? AND seq >= ?")
                    .params(parent.value(), value.size()).update();
        }
    }

    /** Calls back for each property of an object schema that is a child list with its own x-store and a value. */
    static void children(JsonNode objectSchema, JsonNode value, BiConsumer<String, JsonNode> each) {
        objectSchema.path("properties").properties().forEach(p -> {
            if (p.getValue().hasNonNull("x-store") && !p.getValue().path("readOnly").asBoolean(false)
                    && value.has(p.getKey())) {
                each.accept(p.getKey(), p.getValue());
            }
        });
    }

    /** column -> SQL parameter of the declared, writable, non-system properties present in value. */
    private Map<String, Object> writable(String table, ObjectNode value, JsonNode objectSchema) {
        Map<String, String> columns = columns(table);
        Map<String, Object> result = new LinkedHashMap<>();
        objectSchema.path("properties").properties().forEach(p -> {
            JsonNode schema = p.getValue();
            String column = columnOf(p.getKey(), schema);
            if (value.has(p.getKey()) && !schema.hasNonNull("x-store") && !schema.path("readOnly").asBoolean(false)
                    && columns.containsKey(column) && !SYSTEM.contains(column)
                    && !SYSTEM_IN_TABLE.contains(table + "." + column)) {
                result.put(column, toSql(value.get(p.getKey())));
            }
        });
        return result;
    }

    private int update(String table, Key key, Integer seq, Map<String, Object> values) {
        Map<String, String> columns = columns(table);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        values.forEach((column, param) -> {
            sets.add(column + " = " + cast(columns.get(column)));
            params.add(param);
        });
        params.add(key.value());
        String where = " WHERE " + key.column() + " = ?";
        if (seq != null) {
            where += " AND seq = ?";
            params.add(seq);
        }
        return db.sql("UPDATE " + table + " SET " + String.join(", ", sets) + where).params(params).update();
    }

    private int count(String table, Key key) {
        return db.sql("SELECT count(*) FROM " + table + " WHERE " + key.column() + " = ?").param(key.value())
                .query(Integer.class).single();
    }

    private int count(String table, Key parent, int seq) {
        return db.sql("SELECT count(*) FROM " + table + " WHERE " + parent.column() + " = ? AND seq = ?")
                .params(parent.value(), seq).query(Integer.class).single();
    }

    private void insert(String table, Map<String, Object> values) {
        Map<String, String> columns = columns(table);
        List<String> names = new ArrayList<>();
        List<String> casts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        values.forEach((column, param) -> {
            if (columns.containsKey(column)) {
                names.add(column);
                casts.add(param instanceof String ? cast(columns.get(column)) : "?");
                params.add(param);
            }
        });
        db.sql("INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES (" + String.join(", ", casts) + ")")
                .params(params).update();
    }

    static String cast(String dataType) {
        return switch (dataType) {
            case "date", "jsonb", "boolean", "integer", "bigint", "smallint", "numeric" -> "CAST(? AS " + dataType + ")";
            case "timestamp with time zone" -> "CAST(? AS timestamptz)";
            default -> "?";
        };
    }

    /** JSON value -> SQL parameter (as text; the CAST in the statement gives it its type). Blank text is NULL. */
    Object toSql(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isContainerNode()) {
            return value.toString();
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** DB value -> JSON; jsonb arrives as the driver's PGobject, whose toString() is the JSON text. */
    JsonNode toJson(Object value, String dataType) {
        if ("jsonb".equals(dataType) || "json".equals(dataType)) {
            try {
                return json.readTree(value.toString());
            } catch (Exception e) {
                return json.getNodeFactory().textNode(value.toString());
            }
        }
        if (value instanceof Boolean b) {
            return json.getNodeFactory().booleanNode(b);
        }
        if (value instanceof Number n) {
            return json.getNodeFactory().numberNode(n.longValue());
        }
        return json.getNodeFactory().textNode(value.toString()); // java.sql.Date prints as yyyy-MM-dd
    }

    static Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
