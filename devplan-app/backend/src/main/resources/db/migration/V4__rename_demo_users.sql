-- Demo users renamed in the Keycloak realm (getting-started/src/keycloak/devplan-realm.json).
UPDATE employee SET name = 'Sridhar HR',        username = 'sridhar_hr'   WHERE employee_id = 'H001';
UPDATE employee SET name = 'Rahul Manager',     username = 'rahul_mngr'   WHERE employee_id = 'M001';
UPDATE employee SET name = 'Shanmuk Employee',  username = 'shanmuk_emp'  WHERE employee_id = 'E001';
UPDATE employee SET name = 'Kaustubh Employee', username = 'kaustubh_emp' WHERE employee_id = 'E002';
