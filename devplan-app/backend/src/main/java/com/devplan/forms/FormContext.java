package com.devplan.forms;

import com.devplan.config.CurrentUser;

/**
 * What a form is about: the case, the employee whose record its DB fields belong to (Employee.employeeId of the task
 * input), the user filling it in, the task (open or done), and - for development plan forms - the plan round its DB
 * rows belong to (resolved by the form layer, see Rounds).
 */
public record FormContext(String caseId, String employeeId, CurrentUser user, String taskId, boolean taskOpen,
                          Long roundId) {

    public FormContext(String caseId, String employeeId, CurrentUser user) {
        this(caseId, employeeId, user, null, true, null);
    }

    public FormContext withRound(Long round) {
        return new FormContext(caseId, employeeId, user, taskId, taskOpen, round);
    }

    /** COMPLETE checks required fields, saves and runs x-on-complete actions; EDIT changes the DB fields of a
     * completed task (required check, no actions, nothing sent to the engine). */
    public enum Mode { COMPLETE, EDIT }
}
