-- "I received all the equipment listed above" on the employee's Confirm equipment received form
-- (task-model property onboarding.equipmentReceived).
ALTER TABLE onboarding ADD COLUMN equipment_received BOOLEAN;
