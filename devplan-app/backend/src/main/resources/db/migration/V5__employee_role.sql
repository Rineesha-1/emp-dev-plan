-- The employee's role (employee, manager or hr), the same as their Keycloak realm role. Kept here so the app can
-- offer only managers as someone's manager without asking Keycloak.
ALTER TABLE employee ADD COLUMN role TEXT;
UPDATE employee SET role = 'hr'       WHERE employee_id = 'H001';
UPDATE employee SET role = 'manager'  WHERE employee_id = 'M001';
UPDATE employee SET role = 'employee' WHERE employee_id IN ('E001', 'E002');
