package com.devplan.onboarding;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;

/** A new joiner's onboarding checklist (table checklist_item), created from the default list. */
@Repository
public class Checklists {

    public record Item(long id, String employeeId, String employeeName, String section, String name, String ownerRole,
                       String itemKind, String assetType, boolean mandatory, int sortOrder, String status,
                       OffsetDateTime doneAt, String doneBy) {
        public boolean done() {
            return "DONE".equals(status);
        }
    }

    /** Sections in checklist order, with their WF-02 headings. */
    public static final Map<String, String> SECTIONS = Map.of(
            "DOCUMENTS_ADMIN", "Documents & admin",
            "EQUIPMENT_ISSUED", "Equipment issued",
            "ACCOUNTS_INSTALLATIONS", "Accounts & installations",
            "ORIENTATION", "Orientation");
    public static final List<String> SECTION_ORDER =
            List.of("DOCUMENTS_ADMIN", "EQUIPMENT_ISSUED", "ACCOUNTS_INSTALLATIONS", "ORIENTATION");

    /** Realm role -> the checklist owner role it may tick. */
    public static String ownerRole(CurrentUser user) {
        if (user.has("hr")) return "HR";
        if (user.has("it_admin")) return "IT_ADMIN";
        if (user.has("manager")) return "MANAGER";
        return "EMPLOYEE";
    }

    /** Engine case role (task role) -> checklist owner role. */
    public static String ownerRoleOfCaseRole(String caseRole) {
        return switch (caseRole == null ? "" : caseRole) {
            case "HR" -> "HR";
            case "ITAdmin" -> "IT_ADMIN";
            case "Manager" -> "MANAGER";
            case "Employee" -> "EMPLOYEE";
            default -> null;
        };
    }

    private static final String SELECT = "SELECT c.id, c.employee_id, e.name AS employee_name, c.section, c.name, "
            + "c.owner_role, c.item_kind, c.asset_type, c.mandatory, c.sort_order, c.status, c.done_at, c.done_by "
            + "FROM checklist_item c JOIN employee e ON e.employee_id = c.employee_id ";

    private final JdbcClient db;

    public Checklists(JdbcClient db) {
        this.db = db;
    }

    /** Copies the default items once (ONB-02, without templates and due dates). */
    public void createDefaults(String employeeId) {
        Integer existing = db.sql("SELECT count(*) FROM checklist_item WHERE employee_id = ?").param(employeeId)
                .query(Integer.class).single();
        if (existing > 0) {
            return;
        }
        db.sql("INSERT INTO checklist_item (employee_id, default_item_id, section, name, owner_role, item_kind, "
                        + "asset_type, mandatory, sort_order) SELECT ?, id, section, name, owner_role, item_kind, "
                        + "asset_type, mandatory, sort_order FROM checklist_default_item")
                .param(employeeId).update();
    }

    /** Request names of the IT form's accessories -> asset type, so issuing such an asset ticks its item (ONB-06). */
    private static final Map<String, String> ACCESSORY_ASSET_TYPE = Map.of("Monitor", "MONITOR",
            "Keyboard & mouse", "KEYBOARD_MOUSE", "Headset", "HEADSET", "Docking station", "DOCKING_STATION",
            "Phone", "PHONE", "Access badge", "ACCESS_BADGE");

    /**
     * After Send to IT: the IT admin's open items become exactly what HR requested - the laptop, each requested
     * accessory and each requested account, all mandatory. Default IT items that were not requested are removed;
     * items already ticked stay.
     */
    public void syncItItems(String employeeId, List<String> accessories, List<String> accounts) {
        List<String> wanted = new ArrayList<>(List.of("Laptop"));
        wanted.addAll(accessories);
        wanted.addAll(accounts);
        List<String> existing = db.sql("SELECT name FROM checklist_item WHERE employee_id = ? AND owner_role = 'IT_ADMIN'")
                .param(employeeId).query(String.class).list();
        db.sql("DELETE FROM checklist_item WHERE employee_id = ? AND owner_role = 'IT_ADMIN' AND status = 'OPEN' "
                + "AND NOT (name = ANY (?))").params(employeeId, wanted.toArray(new String[0])).update();
        db.sql("UPDATE checklist_item SET mandatory = true WHERE employee_id = ? AND owner_role = 'IT_ADMIN' "
                + "AND name = ANY (?)").params(employeeId, wanted.toArray(new String[0])).update();
        for (String name : wanted) {
            if (existing.contains(name)) {
                continue;
            }
            boolean equipment = "Laptop".equals(name) || ACCESSORY_ASSET_TYPE.containsKey(name);
            String section = equipment ? "EQUIPMENT_ISSUED" : "ACCOUNTS_INSTALLATIONS";
            db.sql("INSERT INTO checklist_item (employee_id, section, name, owner_role, item_kind, asset_type, mandatory, "
                            + "sort_order) VALUES (?, ?, ?, 'IT_ADMIN', ?, ?, true, (SELECT coalesce(max(sort_order), 0) + 1 "
                            + "FROM checklist_item WHERE employee_id = ? AND section = ?))")
                    .params(employeeId, section, name, equipment ? "EQUIPMENT" : "ACCOUNT",
                            "Laptop".equals(name) ? "LAPTOP" : ACCESSORY_ASSET_TYPE.get(name), employeeId, section)
                    .update();
        }
    }

    public List<Item> of(String employeeId) {
        return db.sql(SELECT + "WHERE c.employee_id = ? ORDER BY c.sort_order, c.id").param(employeeId)
                .query(Item.class).list();
    }

    public List<Item> of(String employeeId, String ownerRole) {
        return db.sql(SELECT + "WHERE c.employee_id = ? AND c.owner_role = ? ORDER BY c.sort_order, c.id")
                .param(employeeId).param(ownerRole).query(Item.class).list();
    }

    /** ONB-03: the open items this user owns, over all employees in onboarding. A manager owns the MANAGER items of
     * their direct reports; an employee their own EMPLOYEE items; HR and the IT admin all items of their role. */
    public List<Item> openFor(CurrentUser user, String userEmployeeId) {
        String owner = ownerRole(user);
        String where = "WHERE c.status = 'OPEN' AND e.stage = 'ONBOARDING' AND c.owner_role = ? ";
        var query = switch (owner) {
            case "MANAGER" -> db.sql(SELECT + where + "AND e.manager_id = ? ORDER BY e.name, c.sort_order")
                    .param(owner).param(userEmployeeId);
            case "EMPLOYEE" -> db.sql(SELECT + where + "AND e.employee_id = ? ORDER BY c.sort_order")
                    .param(owner).param(userEmployeeId);
            default -> db.sql(SELECT + where + "ORDER BY e.name, c.sort_order").param(owner);
        };
        return query.query(Item.class).list();
    }

    public Item get(long id) {
        return db.sql(SELECT + "WHERE c.id = ?").param(id).query(Item.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found"));
    }

    /** ONB-04: marks the item Done at once. */
    public void markDone(long id, CurrentUser user) {
        db.sql("UPDATE checklist_item SET status = 'DONE', done_at = now(), done_by = ? WHERE id = ? AND status = 'OPEN'")
                .param(doneBy(user)).param(id).update();
    }

    /** Ticks the open items of a kind, e.g. EQUIPMENT_CONFIRM when the employee confirms their equipment. */
    public void markKindDone(String employeeId, String itemKind, CurrentUser user) {
        db.sql("UPDATE checklist_item SET status = 'DONE', done_at = now(), done_by = ? "
                        + "WHERE employee_id = ? AND item_kind = ? AND status = 'OPEN'")
                .param(doneBy(user)).param(employeeId).param(itemKind).update();
    }

    /** ONB-06: issuing an asset ticks the open EQUIPMENT item for its type and links the asset. */
    public void markAssetIssued(String employeeId, String assetType, long assetId, CurrentUser user) {
        db.sql("UPDATE checklist_item SET status = 'DONE', done_at = now(), done_by = ?, asset_id = ? "
                        + "WHERE employee_id = ? AND item_kind = 'EQUIPMENT' AND asset_type = ? AND status = 'OPEN'")
                .param(doneBy(user)).param(assetId).param(employeeId).param(assetType).update();
    }

    public void add(String employeeId, String section, String name, String ownerRole, boolean mandatory) {
        db.sql("INSERT INTO checklist_item (employee_id, section, name, owner_role, mandatory, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, (SELECT coalesce(max(sort_order), 0) + 1 FROM checklist_item "
                        + "WHERE employee_id = ? AND section = ?))")
                .params(employeeId, section, name, ownerRole, mandatory, employeeId, section).update();
    }

    public int openMandatory(String employeeId) {
        return db.sql("SELECT count(*) FROM checklist_item WHERE employee_id = ? AND mandatory AND status = 'OPEN'")
                .param(employeeId).query(Integer.class).single();
    }

    private static String doneBy(CurrentUser user) {
        return user.name() == null ? user.username() : user.name();
    }
}
