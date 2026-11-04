package com.devplan.forms;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Backend actions a task-model names with "x-on-complete": what happens besides saving the DB data when a task is
 * completed. An action returns case-file outputs (ids, flags), which the form layer merges into what it sends to the
 * engine.
 */
public interface FormActions {
    boolean handles(String action);

    ObjectNode run(String action, FormContext ctx);
}
