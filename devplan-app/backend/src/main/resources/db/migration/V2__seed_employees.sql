-- Demo employees matching the Keycloak realm users (getting-started/src/keycloak/devplan-realm.json).
INSERT INTO employee (employee_id, name, username, user_id, manager_id) VALUES
    ('H001', 'Hannah HR',     'hr1',       'd0000000-0000-4000-8000-000000000004', NULL),
    ('M001', 'Mark Manager',  'manager1',  'd0000000-0000-4000-8000-000000000003', NULL),
    ('E001', 'Emma Employee', 'employee1', 'd0000000-0000-4000-8000-000000000001', 'M001'),
    ('E002', 'Eric Employee', 'employee2', 'd0000000-0000-4000-8000-000000000002', 'M001');
