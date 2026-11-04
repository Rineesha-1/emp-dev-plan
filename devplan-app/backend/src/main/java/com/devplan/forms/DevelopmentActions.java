package com.devplan.forms;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The "x-on-complete" actions of the development plan forms. Each moves the round (dev_round.status) on and returns
 * the case-file fields the backend owns: the round id and number on Plan, who decided on Approval, the activity's id,
 * the self-assessment's submitted flag. The status flow: DRAFT -> SUBMITTED -> APPROVED | REJECTED (-> SUBMITTED
 * again after Revise plan) -> EVALUATING (a review with "plan completed") -> CLOSED (evaluation done).
 */
@Component
public class DevelopmentActions implements FormActions {
    private static final Set<String> ACTIONS =
            Set.of("submitPlan", "decidePlan", "addActivity", "addReview", "submitSelfAssessment", "closeRound");

    private final JdbcClient db;
    private final ObjectMapper json;

    public DevelopmentActions(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    @Override
    public ObjectNode run(String action, FormContext ctx) {
        if (ctx.roundId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This form has no plan round");
        }
        long round = ctx.roundId();
        ObjectNode output = json.createObjectNode();
        switch (action) {
            case "submitPlan" -> {
                status(round, "SUBMITTED");
                int number = db.sql("SELECT round_no FROM dev_round WHERE id = ?").param(round).query(Integer.class).single();
                output.putObject("Plan").put("roundId", String.valueOf(round)).put("roundNo", number);
            }
            case "decidePlan" -> {
                String decision = db.sql("SELECT coalesce(approval_decision, '') FROM dev_round WHERE id = ?").param(round)
                        .query(String.class).single();
                db.sql("UPDATE dev_round SET status = ?, approved_by = ?, approved_at = now() WHERE id = ?")
                        .params("APPROVED".equals(decision) ? "APPROVED" : "REJECTED", ctx.user().name(), round).update();
                output.putObject("Approval").put("decidedBy", ctx.user().name());
            }
            case "addActivity" -> {
                db.sql("UPDATE dev_activity SET created_by = coalesce(created_by, ?) WHERE task_id = ?")
                        .params(ctx.user().name(), ctx.taskId()).update();
                Long id = db.sql("SELECT id FROM dev_activity WHERE task_id = ?").param(ctx.taskId()).query(Long.class)
                        .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "The activity is empty"));
                output.putObject("Activity").put("activityId", String.valueOf(id));
            }
            case "addReview" -> {
                db.sql("UPDATE dev_progress_review SET created_by = coalesce(created_by, ?) WHERE task_id = ?")
                        .params(ctx.user().name(), ctx.taskId()).update();
                boolean completed = db.sql("SELECT coalesce(plan_completed, false) FROM dev_progress_review WHERE task_id = ?")
                        .param(ctx.taskId()).query(Boolean.class).optional().orElse(false);
                if (completed) {
                    status(round, "EVALUATING");
                }
            }
            case "submitSelfAssessment" -> {
                db.sql("UPDATE dev_round SET self_submitted_at = now() WHERE id = ?").param(round).update();
                output.putObject("SelfAssessment").put("submitted", true);
            }
            case "closeRound" -> db.sql("UPDATE dev_round SET status = 'CLOSED', evaluated_by = ?, evaluated_at = now() "
                    + "WHERE id = ?").params(ctx.user().name(), round).update();
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown form action " + action);
        }
        return output;
    }

    private void status(long round, String status) {
        db.sql("UPDATE dev_round SET status = ? WHERE id = ?").params(status, round).update();
    }
}
