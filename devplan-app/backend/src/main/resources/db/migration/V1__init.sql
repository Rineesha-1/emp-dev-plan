-- Business data of the Development plan app. The case engine keeps only what its sentries need.

CREATE TABLE employee (
    employee_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    username    TEXT NOT NULL UNIQUE,              -- Keycloak username
    user_id     TEXT UNIQUE,                       -- Keycloak user id (token "sub"), also the engine user id
    manager_id  TEXT REFERENCES employee (employee_id)
);

CREATE TABLE development_case (
    case_id     TEXT PRIMARY KEY,                  -- engine case instance id
    employee_id TEXT NOT NULL REFERENCES employee (employee_id),
    started_by  TEXT NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per submitted form: the full business data of that form.
-- kind = the form's output object (goals, approval, activity, progress, selfAssessment, evaluation).
-- round = plan round within the case; "Define goals & plan" starts a new one.
CREATE TABLE form_record (
    id           BIGSERIAL PRIMARY KEY,
    case_id      TEXT NOT NULL REFERENCES development_case (case_id),
    round        INT NOT NULL,
    kind         TEXT NOT NULL,
    task_id      TEXT NOT NULL,
    task_name    TEXT NOT NULL,
    data         JSONB NOT NULL,
    submitted_by TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX form_record_case ON form_record (case_id, round, kind);
