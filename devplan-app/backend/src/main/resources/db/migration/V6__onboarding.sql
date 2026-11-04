-- Onboarding (FRD-02, reduced). Business data of a new joiner lives here; the case file only holds flags.
-- Task forms map their "x-store" objects onto these tables by name: camelCase property = snake_case column.

-- The employee record gets the personal details and the role of Step 1. A new joiner starts as a DRAFT row without
-- a Keycloak account, so name and username become optional until Step 1 is completed.
CREATE SEQUENCE employee_code_seq START 31;

ALTER TABLE employee
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN username DROP NOT NULL,
    ADD COLUMN employee_code TEXT UNIQUE,
    ADD COLUMN first_name    TEXT,
    ADD COLUMN last_name     TEXT,
    ADD COLUMN private_email TEXT UNIQUE,      -- ONB-09
    ADD COLUMN mobile_phone  TEXT,
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN nationality   TEXT,
    ADD COLUMN street        TEXT,
    ADD COLUMN postcode      TEXT,
    ADD COLUMN city          TEXT,
    ADD COLUMN department    TEXT,
    ADD COLUMN grade         TEXT,
    ADD COLUMN buddy_id      TEXT REFERENCES employee (employee_id),
    ADD COLUMN stage         TEXT NOT NULL DEFAULT 'ACTIVE',   -- DRAFT, ONBOARDING, ACTIVE
    ADD COLUMN case_id       TEXT;

-- Lookups for the Step 1 dropdowns (task-model "x-options").
CREATE TABLE department (name TEXT PRIMARY KEY);
INSERT INTO department (name) VALUES ('Development'), ('Design'), ('QA'), ('HR'), ('IT');

CREATE TABLE job_title (name TEXT PRIMARY KEY);
INSERT INTO job_title (name) VALUES ('Developer'), ('Senior Developer'), ('UX Designer'), ('QA Engineer'), ('Tech Lead'),
    ('Software Developer'), ('Engineering Manager'), ('HR Business Partner'), ('IT Administrator');

-- One onboarding per new joiner: Step 2's IT request and what the employee reports on their equipment.
CREATE TABLE onboarding (
    employee_id            TEXT PRIMARY KEY REFERENCES employee (employee_id) ON DELETE CASCADE,
    status                 TEXT NOT NULL DEFAULT 'DRAFT',      -- DRAFT, IN_PROGRESS, COMPLETED
    it_laptop_model        TEXT,
    it_keyboard_layout     TEXT,
    it_ready_by            DATE,
    it_delivery_method     TEXT,                               -- OFFICE_DAY1, SHIP_HOME
    it_accessories         JSONB,
    it_accounts            JSONB,
    it_notes               TEXT,
    it_status              TEXT NOT NULL DEFAULT 'NOT_SENT',   -- NOT_SENT, SENT, COMPLETED
    it_sent_at             TIMESTAMPTZ,
    it_completed_at        TIMESTAMPTZ,
    equipment_note         TEXT,
    equipment_confirmed_at TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    completed_by           TEXT
);

-- The one default checklist every new joiner gets (no templates).
CREATE TABLE checklist_default_item (
    id          BIGSERIAL PRIMARY KEY,
    section     TEXT NOT NULL,      -- DOCUMENTS_ADMIN, EQUIPMENT_ISSUED, ACCOUNTS_INSTALLATIONS, ORIENTATION
    name        TEXT NOT NULL,
    owner_role  TEXT NOT NULL,      -- HR, IT_ADMIN, MANAGER, EMPLOYEE
    item_kind   TEXT NOT NULL DEFAULT 'GENERIC',  -- GENERIC, POLICY_ACK, PROFILE, EQUIPMENT, EQUIPMENT_CONFIRM, ACCOUNT
    asset_type  TEXT,               -- EQUIPMENT items: ticked when IT issues an asset of this type (ONB-06)
    mandatory   BOOLEAN NOT NULL DEFAULT true,
    sort_order  INT NOT NULL
);

INSERT INTO checklist_default_item (section, name, owner_role, item_kind, asset_type, mandatory, sort_order) VALUES
    ('DOCUMENTS_ADMIN',        'Acknowledge employee handbook',          'EMPLOYEE', 'POLICY_ACK',        NULL,           true,  10),
    ('DOCUMENTS_ADMIN',        'Acknowledge IT & GDPR policy',           'EMPLOYEE', 'POLICY_ACK',        NULL,           true,  20),
    ('DOCUMENTS_ADMIN',        'Complete profile & emergency contact',   'EMPLOYEE', 'PROFILE',           NULL,           true,  30),
    ('EQUIPMENT_ISSUED',       'Laptop',                                 'IT_ADMIN', 'EQUIPMENT',         'LAPTOP',       true,  40),
    ('EQUIPMENT_ISSUED',       'Monitor',                                'IT_ADMIN', 'EQUIPMENT',         'MONITOR',      false, 50),
    ('EQUIPMENT_ISSUED',       'Access badge',                           'IT_ADMIN', 'EQUIPMENT',         'ACCESS_BADGE', true,  60),
    ('EQUIPMENT_ISSUED',       'Confirm equipment received',             'EMPLOYEE', 'EQUIPMENT_CONFIRM', NULL,           true,  70),
    ('ACCOUNTS_INSTALLATIONS', 'Microsoft 365',                          'IT_ADMIN', 'ACCOUNT',           NULL,           true,  80),
    ('ACCOUNTS_INSTALLATIONS', 'Slack',                                  'IT_ADMIN', 'ACCOUNT',           NULL,           true,  90),
    ('ACCOUNTS_INSTALLATIONS', 'VPN',                                    'IT_ADMIN', 'ACCOUNT',           NULL,           true,  100),
    ('ACCOUNTS_INSTALLATIONS', 'Jira / Confluence',                      'IT_ADMIN', 'ACCOUNT',           NULL,           true,  110),
    ('ACCOUNTS_INSTALLATIONS', 'GitHub',                                 'IT_ADMIN', 'ACCOUNT',           NULL,           false, 120),
    ('ORIENTATION',            'Welcome session',                        'HR',       'GENERIC',           NULL,           true,  130),
    ('ORIENTATION',            'Buddy introduced',                       'HR',       'GENERIC',           NULL,           false, 140),
    ('ORIENTATION',            'Team introduction',                      'MANAGER',  'GENERIC',           NULL,           true,  150),
    ('ORIENTATION',            'First-week plan agreed',                 'MANAGER',  'GENERIC',           NULL,           true,  160);

-- Equipment IT issued (the asset register, ONB-06).
CREATE TABLE asset (
    id            BIGSERIAL PRIMARY KEY,
    asset_number  TEXT NOT NULL UNIQUE,
    asset_type    TEXT NOT NULL,
    model         TEXT NOT NULL,
    serial_number TEXT,
    employee_id   TEXT REFERENCES employee (employee_id) ON DELETE SET NULL,
    issued_on     DATE,
    status        TEXT NOT NULL DEFAULT 'ISSUED',
    received      BOOLEAN NOT NULL DEFAULT false
);

-- A new joiner's checklist: a copy of the default items (snapshot) plus items HR adds with "+ Add item".
CREATE TABLE checklist_item (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     TEXT NOT NULL REFERENCES employee (employee_id) ON DELETE CASCADE,
    default_item_id BIGINT REFERENCES checklist_default_item (id),   -- NULL = "+ Add item"
    section         TEXT NOT NULL,
    name            TEXT NOT NULL,
    owner_role      TEXT NOT NULL,
    item_kind       TEXT NOT NULL DEFAULT 'GENERIC',
    asset_type      TEXT,
    mandatory       BOOLEAN NOT NULL,
    sort_order      INT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN',    -- OPEN, DONE
    done_at         TIMESTAMPTZ,
    done_by         TEXT,
    asset_id        BIGINT REFERENCES asset (id) ON DELETE SET NULL
);
CREATE INDEX ix_checklist_open ON checklist_item (employee_id, owner_role) WHERE status = 'OPEN';

-- The IT admin of the demo realm (getting-started/src/keycloak/devplan-realm.json).
INSERT INTO employee (employee_id, name, username, user_id, manager_id, job_title, role, department)
VALUES ('I001', 'Kevin IT', 'kevin_it', 'd0000000-0000-4000-8000-000000000006', NULL, 'IT Administrator', 'it_admin', 'IT');
