-- The orientation items of the manager (Team introduction, First-week plan agreed) and HR (Welcome session) are no
-- longer part of onboarding: removed from the default checklist and from the checklists still open. Ticked items of
-- finished onboardings stay as a record (no longer linked to a default item).
DELETE FROM checklist_item
 WHERE status = 'OPEN'
   AND default_item_id IN (SELECT id FROM checklist_default_item
                            WHERE (owner_role = 'MANAGER' AND name IN ('Team introduction', 'First-week plan agreed'))
                               OR (owner_role = 'HR' AND name = 'Welcome session'));

UPDATE checklist_item SET default_item_id = NULL
 WHERE default_item_id IN (SELECT id FROM checklist_default_item
                            WHERE (owner_role = 'MANAGER' AND name IN ('Team introduction', 'First-week plan agreed'))
                               OR (owner_role = 'HR' AND name = 'Welcome session'));

DELETE FROM checklist_default_item
 WHERE (owner_role = 'MANAGER' AND name IN ('Team introduction', 'First-week plan agreed'))
    OR (owner_role = 'HR' AND name = 'Welcome session');
