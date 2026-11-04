package com.devplan.forms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.onboarding.Checklists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * x-store "assets": an array of the employee's issued assets, one row of table asset per element, matched on the
 * asset number. Item properties map to asset columns by name, like TableStore. Saving the list replaces the
 * employee's assets (removed rows are deleted) and ticks the EQUIPMENT checklist item of each asset's type (ONB-06).
 * Item properties marked "readOnly" in the schema (e.g. in the employee's confirm form) are never written.
 */
@Component
public class AssetStore {
    public static final String NAME = "assets";
    private static final Set<String> SYSTEM = Set.of("id", "employee_id", "status");

    private final JdbcClient db;
    private final ObjectMapper json;
    private final TableStore tables;
    private final Checklists checklists;

    public AssetStore(JdbcClient db, ObjectMapper json, TableStore tables, Checklists checklists) {
        this.db = db;
        this.json = json;
        this.tables = tables;
        this.checklists = checklists;
    }

    public ArrayNode load(String employeeId, JsonNode arraySchema) {
        Map<String, String> columns = tables.columns("asset");
        JsonNode itemProperties = arraySchema.path("items").path("properties");
        ArrayNode result = json.createArrayNode();
        db.sql("SELECT * FROM asset WHERE employee_id = ? ORDER BY id").param(employeeId)
                .query((rs, n) -> TableStore.row(rs)).list().forEach(row -> {
                    ObjectNode item = result.addObject();
                    itemProperties.fieldNames().forEachRemaining(property -> {
                        String column = TableStore.column(property);
                        Object value = row.get(column);
                        if (columns.containsKey(column) && value != null) {
                            item.set(property, tables.toJson(value, columns.get(column)));
                        }
                    });
                });
        return result;
    }

    public void save(String employeeId, JsonNode value, JsonNode arraySchema, CurrentUser user) {
        if (!value.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The equipment list must be a list");
        }
        Map<String, String> columns = tables.columns("asset");
        JsonNode itemProperties = arraySchema.path("items").path("properties");
        Set<String> kept = new HashSet<>();
        for (JsonNode item : value) {
            String number = item.path("assetNumber").asText("").trim();
            if (number.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Asset no.: This field is required.");
            }
            if (!kept.add(number)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset number is already in use.");
            }
            // An asset number held by another employee is taken; an unassigned one (in storage) can be issued.
            List<String> holders = db.sql("SELECT coalesce(employee_id, '') FROM asset WHERE asset_number = ?")
                    .param(number).query(String.class).list();
            boolean exists = !holders.isEmpty();
            if (exists && !holders.get(0).isEmpty() && !holders.get(0).equals(employeeId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset number is already in use.");
            }
            List<String> names = new ArrayList<>(List.of("employee_id"));
            List<String> casts = new ArrayList<>(List.of("?"));
            List<Object> params = new ArrayList<>(List.of(employeeId));
            itemProperties.fieldNames().forEachRemaining(property -> {
                String column = TableStore.column(property);
                boolean readOnly = itemProperties.path(property).path("readOnly").asBoolean(false);
                if (item.has(property) && columns.containsKey(column) && !SYSTEM.contains(column)
                        && !"asset_number".equals(column) && !readOnly) {
                    names.add(column);
                    casts.add(TableStore.cast(columns.get(column)));
                    params.add(tables.toSql(item.get(property)));
                }
            });
            if (exists) {
                List<String> sets = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    sets.add(names.get(i) + " = " + casts.get(i));
                }
                params.add(number);
                db.sql("UPDATE asset SET " + String.join(", ", sets) + " WHERE asset_number = ?").params(params).update();
            } else {
                params.add(number);
                db.sql("INSERT INTO asset (" + String.join(", ", names) + ", asset_number) VALUES ("
                        + String.join(", ", casts) + ", ?)").params(params).update();
            }
        }
        // Assets removed from the list are no longer issued to this employee.
        List<String> current = db.sql("SELECT asset_number FROM asset WHERE employee_id = ?").param(employeeId)
                .query(String.class).list();
        current.stream().filter(n -> !kept.contains(n)).forEach(n ->
                db.sql("DELETE FROM asset WHERE asset_number = ?").param(n).update());
        // ONB-06: each issued type ticks its equipment item.
        db.sql("SELECT id, asset_type FROM asset WHERE employee_id = ?").param(employeeId)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2))).list()
                .forEach(a -> checklists.markAssetIssued(employeeId, a.getValue(), a.getKey(), user));
    }
}
