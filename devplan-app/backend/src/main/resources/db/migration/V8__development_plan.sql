-- Development plan business data. The case file keeps what the sentries and the case history need (ids, decisions,
-- status fields, goal titles); the full record of every plan round lives here. Form fields map to columns by name
-- (camelCase -> snake_case) or by the task-model's "x-column"; columns not in a form are set by the backend.

-- One row per plan round of a development case. A round starts when "Define goals & plan" is first saved and ends
-- when "Evaluate & document results" is completed. Earlier rounds are the CLOSED rows.
CREATE TABLE dev_round (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             TEXT NOT NULL,
    employee_id         TEXT NOT NULL REFERENCES employee (employee_id),
    round_no            INT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT',  -- DRAFT, SUBMITTED, REJECTED, APPROVED, EVALUATING, CLOSED
    period              TEXT,
    approval_decision   TEXT,
    approval_comments   TEXT,
    approved_by         TEXT,
    approved_at         TIMESTAMPTZ,
    self_summary        TEXT,
    self_submitted_at   TIMESTAMPTZ,
    eval_outcome        TEXT,
    eval_summary        TEXT,
    further_development BOOLEAN,
    evaluated_by        TEXT,
    evaluated_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, round_no)
);

-- The goals of a round, in form order (seq), with the employee's and the manager's result per goal.
CREATE TABLE dev_goal (
    id               BIGSERIAL PRIMARY KEY,
    round_id         BIGINT NOT NULL REFERENCES dev_round (id) ON DELETE CASCADE,
    seq              INT NOT NULL,
    title            TEXT,
    type             TEXT,
    description      TEXT,
    success_criteria TEXT,
    target_date      DATE,
    self_result      TEXT,
    self_comments    TEXT,
    eval_result      TEXT,
    eval_comments    TEXT,
    UNIQUE (round_id, seq)
);

-- One row per activity task (Projects and assignments, Training and learning, Coaching and mentoring).
CREATE TABLE dev_activity (
    id           BIGSERIAL PRIMARY KEY,
    round_id     BIGINT NOT NULL REFERENCES dev_round (id) ON DELETE CASCADE,
    task_id      TEXT UNIQUE,
    type         TEXT,
    description  TEXT,
    related_goal TEXT,
    due_date     DATE,
    status       TEXT,
    outcome      TEXT,
    created_by   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per Review progress task.
CREATE TABLE dev_progress_review (
    id             BIGSERIAL PRIMARY KEY,
    round_id       BIGINT NOT NULL REFERENCES dev_round (id) ON DELETE CASCADE,
    task_id        TEXT UNIQUE,
    review_date    DATE,
    on_track       BOOLEAN,
    notes          TEXT,
    adjustments    TEXT,
    plan_completed BOOLEAN,
    created_by     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Which round a task belongs to, so a finished task keeps showing its own round.
CREATE TABLE dev_round_task (
    task_id  TEXT PRIMARY KEY,
    round_id BIGINT NOT NULL REFERENCES dev_round (id) ON DELETE CASCADE
);
