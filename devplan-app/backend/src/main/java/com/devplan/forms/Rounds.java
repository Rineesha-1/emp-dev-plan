package com.devplan.forms;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The development plan round a form's DB rows belong to (table dev_round). A round is opened by the first save of a
 * development form when the case has no open round (normally "Define goals & plan"), and closed by "Evaluate &
 * document results". Each open task is tied to the round it was filled in for (dev_round_task), so a finished task
 * keeps showing its own round.
 */
@Component
public class Rounds {
    private final JdbcClient db;

    public Rounds(JdbcClient db) {
        this.db = db;
    }

    /**
     * Read: the task's own round; for an open task the case's open round (tying the task to it). A new round's first
     * form (no open round yet) gets null, so it starts empty: nothing from earlier rounds is shown in it. A finished
     * task that was never tied (copied from before rounds existed) shows the latest round.
     */
    public Long forRead(FormContext ctx) {
        Long tied = tied(ctx.taskId());
        if (tied != null) {
            return tied;
        }
        if (ctx.taskOpen()) {
            Long open = open(ctx.caseId());
            if (open != null) {
                tie(ctx.taskId(), open);
            }
            return open;
        }
        return latest(ctx.caseId());
    }

    /** Write: the task's own round, else the case's open round, else a new one. */
    @Transactional
    public long forWrite(FormContext ctx) {
        Long tied = tied(ctx.taskId());
        if (tied != null) {
            return tied;
        }
        if (!ctx.taskOpen()) {
            Long latest = latest(ctx.caseId());
            if (latest == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This task has no plan round");
            }
            return latest;
        }
        Long round = open(ctx.caseId());
        if (round == null) {
            if (ctx.employeeId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This case has no Employee.employeeId");
            }
            round = db.sql("INSERT INTO dev_round (case_id, employee_id, round_no) VALUES (?, ?, "
                            + "(SELECT coalesce(max(round_no), 0) + 1 FROM dev_round WHERE case_id = ?)) RETURNING id")
                    .params(ctx.caseId(), ctx.employeeId(), ctx.caseId()).query(Long.class).single();
        }
        tie(ctx.taskId(), round);
        return round;
    }

    private Long tied(String taskId) {
        if (taskId == null) {
            return null;
        }
        List<Long> found = db.sql("SELECT round_id FROM dev_round_task WHERE task_id = ?").param(taskId)
                .query(Long.class).list();
        return found.isEmpty() ? null : found.get(0);
    }

    private void tie(String taskId, long round) {
        if (taskId != null) {
            db.sql("INSERT INTO dev_round_task (task_id, round_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(taskId, round).update();
        }
    }

    public Long open(String caseId) {
        List<Long> found = db.sql("SELECT id FROM dev_round WHERE case_id = ? AND status <> 'CLOSED' "
                + "ORDER BY round_no DESC LIMIT 1").param(caseId).query(Long.class).list();
        return found.isEmpty() ? null : found.get(0);
    }

    private Long latest(String caseId) {
        List<Long> found = db.sql("SELECT id FROM dev_round WHERE case_id = ? ORDER BY round_no DESC LIMIT 1")
                .param(caseId).query(Long.class).list();
        return found.isEmpty() ? null : found.get(0);
    }
}
