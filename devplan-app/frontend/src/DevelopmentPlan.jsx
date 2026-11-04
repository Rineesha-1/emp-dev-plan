import React, { useEffect, useMemo, useState } from 'react';
import { JsonForms } from '@jsonforms/react';
import { materialCells } from '@jsonforms/material-renderers';
import { ThemeProvider } from '@mui/material/styles';
import { api } from './api.js';
import { Badge, OPEN_TASK, Problem, StateBadge, TASK_STATE, ago, caseRolesOf, day, renderers, setFlash, theme, useLoad } from './shared.jsx';
import TaskForm from './TaskForm.jsx';

const ROUND_STATUS = {
  DRAFT: ['warning', 'Draft'], SUBMITTED: ['info', 'Waiting for approval'], REJECTED: ['error', 'Sent back'],
  APPROVED: ['info', 'Approved'], EVALUATING: ['info', 'Evaluating'], CLOSED: ['success', 'Closed'],
};

/**
 * One employee's development plan, in the onboarding wizard's look. The step tabs are the stages inside the case
 * model's "Development plan" stage (1 · Plan, 2 · Perform plan, 3 · Evaluate plan), each with the tasks of its latest
 * run rendered as their engine forms. The last tab is the plan record: every round from the devplan database, shown
 * in a read-only form built from the development task forms. Nothing here names a task, stage or field.
 */
export default function DevelopmentPlan({ caseId, me }) {
  const [data, error, reload] = useLoad(`/cases/${caseId}/plan`);
  const [tab, setTab] = useState(null);
  const [taskId, setTaskId] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [busy, setBusy] = useState(false);
  const roles = caseRolesOf(me);

  // Open on the running step; the record when no step runs.
  useEffect(() => {
    if (!data || tab !== null) return;
    const running = data.steps.findIndex((s) => s.state === 'Active');
    setTab(running >= 0 ? running : data.steps.length);
  }, [data, tab]);
  // In a step, open the first task waiting for this user.
  useEffect(() => {
    if (!data || tab === null || tab >= data.steps.length) { setTaskId(null); return; }
    const mine = data.steps[tab].tasks.find((t) => OPEN_TASK.includes(t.taskState) && roles.includes(t.role));
    setTaskId(mine ? mine.id : null);
  }, [data, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  if (error) return <Problem message={error} />;
  if (!data || tab === null) return <p className="muted">Loading the development plan…</p>;
  const { steps, record, employee } = data;
  const recordTab = steps.length;
  const step = steps[tab];

  const addActivity = (item) => {
    setActionError(null);
    setBusy(true);
    api('POST', `/cases/${caseId}/plan`, item)
      .then(() => { setFlash(`${item.name}: added. Fill it in below.`); reload(); })
      .catch((e) => setActionError(e.message)).finally(() => setBusy(false));
  };
  const taskDone = (task) => { setFlash(`${task.taskName}: done.`); setTaskId(null); setTab(null); reload(); };

  return (
    <section>
      <a className="back-link" href={`#/cases/${caseId}`}>← {employee?.name ? `${employee.name}'s case` : 'Back to the case'}</a>
      <div className="page-header">
        <div>
          <div className="page-title">Development plan</div>
          <div className="page-subtitle">{[employee?.name, data.round > 0 && `Round ${data.round}`].filter(Boolean).join(' · ')}</div>
        </div>
      </div>

      <div className="bcrumb steptabs" role="tablist">
        {steps.map((s, i) => (
          <React.Fragment key={s.planItemId}>
            <button type="button" role="tab" aria-selected={tab === i}
                    className={`step${tab === i ? ' cur' : s.state === 'Completed' ? ' done' : ''}`}
                    onClick={() => setTab(i)}>{s.name}</button>
            <span className="arrow">→</span>
          </React.Fragment>
        ))}
        <button type="button" role="tab" aria-selected={tab === recordTab}
                className={`step${tab === recordTab ? ' cur' : ''}`} onClick={() => setTab(recordTab)}>Plan record</button>
      </div>
      <Problem message={actionError} />

      {step && (
        <div>
          <div className="card">
            <div className="card-title card-title-row"><span>{step.name}</span><StateBadge state={step.state} /></div>
            {step.tasks.length === 0 && (
              <p className="muted">{step.state === 'Available' ? 'This step has not started yet.' : 'No tasks in this step yet.'}</p>
            )}
            {step.tasks.length > 0 && (
              <div className="task-list">
                {step.tasks.map((t) => {
                  const open = OPEN_TASK.includes(t.taskState);
                  return (
                    <button key={t.id} type="button" className={`task-row${taskId === t.id ? ' selected' : ''}`} onClick={() => setTaskId(t.id)}>
                      <span><Badge kind={open ? 'info' : 'success'}>{open ? 'Open' : 'Done'}</Badge></span>
                      <span><div className="task-title">{t.taskName}</div>
                        <div className="task-sub">{t.role} · {TASK_STATE[t.taskState] || t.taskState}</div></span>
                      <span className="task-due">{ago(t.lastModified || t.createdOn)}</span>
                      <span>→</span>
                    </button>
                  );
                })}
              </div>
            )}
            {step.discretionaryItems.length > 0 && (
              <>
                <div className="section-h">Add an activity</div>
                <div className="chips">
                  {step.discretionaryItems.map((d) => (
                    <button key={d.definitionId} className="btn btn-secondary btn-sm" disabled={busy} onClick={() => addActivity(d)}>+ {d.name}</button>
                  ))}
                </div>
              </>
            )}
          </div>
          {taskId && <TaskForm key={taskId} taskId={taskId} me={me} embedded onDone={taskDone} onCancel={() => setTaskId(null)} />}
        </div>
      )}

      {tab === recordTab && <PlanRecord record={record} />}
    </section>
  );
}

/** Every plan round from the devplan database, newest first, in the read-only form the backend builds from the model. */
function PlanRecord({ record }) {
  const [index, setIndex] = useState(0);
  const schema = useMemo(() => record.taskModel.schema, [record]);
  const rounds = record.rounds;
  if (rounds.length === 0) return <div className="card empty">No plan has been sent yet.</div>;
  const round = rounds[Math.min(index, rounds.length - 1)];
  const [kind, text] = ROUND_STATUS[round.status] || ['neutral', round.status];
  return (
    <div>
      <div className="chips round-chips">
        {rounds.map((r, i) => (
          <button key={r.id} type="button" className={`btn btn-sm ${i === index ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setIndex(i)}>
            Round {r.round_no}{r.period ? ` · ${r.period}` : ''}
          </button>
        ))}
      </div>
      <div className="status-line">
        <span className="sl-label">Round {round.round_no}</span>
        <Badge kind={kind}>{text}</Badge>
        <span className="sl-meta">started {day(round.created_at)}</span>
      </div>
      <ThemeProvider theme={theme}>
        <div className="wf-form">
          <JsonForms key={round.id} schema={schema} uischema={record.taskModel.uiSchema} data={round.data}
                     renderers={renderers} cells={materialCells} readonly />
        </div>
      </ThemeProvider>
    </div>
  );
}
