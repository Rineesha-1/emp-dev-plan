-- The development plan itself now lives in the case engine's case file; this app keeps only the employee directory
-- (who exists, their Keycloak account, job title and manager), which it needs to start a case.
DROP TABLE form_record;
DROP TABLE development_case;

ALTER TABLE employee ADD COLUMN job_title TEXT;
UPDATE employee SET job_title = 'HR Business Partner' WHERE employee_id = 'H001';
UPDATE employee SET job_title = 'Engineering Manager' WHERE employee_id = 'M001';
UPDATE employee SET job_title = 'Software Developer' WHERE employee_id = 'E001';
UPDATE employee SET job_title = 'QA Engineer' WHERE employee_id = 'E002';
