import React, { useCallback, useEffect, useState } from 'react';
import { JsonForms } from '@jsonforms/react';
import { MaterialArrayLayout, materialCells, materialRenderers } from '@jsonforms/material-renderers';
import { isObjectArrayControl, rankWith } from '@jsonforms/core';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import { api } from './api.js';

const OPEN_TASK = ['Unassigned', 'Assigned', 'Delegated'];
const TASK_STATE = { Unassigned: 'To do', Assigned: 'Picked up', Delegated: 'Delegated', Completed: 'Done' };
const ROLE_LABEL = { employee: 'Employee', manager: 'Manager', hr: 'HR' };

// The three steps of one plan round, in order (the case's stages 1 · Plan, 2 · Perform plan, 3 · Evaluate plan).
const PHASES = [
  { key: 'plan', name: 'Plan', hint: 'Goals are written and approved.' },
  { key: 'perform', name: 'Perform', hint: 'Activities are carried out and progress is reviewed.' },
  { key: 'evaluate', name: 'Evaluate', hint: 'The employee and manager look back on the results.' },
];
const PHASE_LABEL = { plan: 'Planning', perform: 'In progress', evaluate: 'Evaluating', waiting: 'Case is closed' };

// Lists of objects (goals, activities, reviews, results) as expandable rows instead of a cramped table.
const renderers = [...materialRenderers, { tester: rankWith(10, isObjectArrayControl), renderer: MaterialArrayLayout }];

const LABEL = {
  ANNUAL_REVIEW: 'Annual review', NEW_ROLE: 'New role', CAREER_GROWTH: 'Career growth',
  PERFORMANCE_IMPROVEMENT: 'Performance improvement', ONBOARDING: 'Onboarding',
  SKILL: 'Skill', KNOWLEDGE: 'Knowledge', BEHAVIOUR: 'Behaviour', CAREER: 'Career',
  PROJECT: 'Project', TRAINING: 'Training', COACHING: 'Coaching',
  PLANNED: 'Planned', IN_PROGRESS: 'In progress', DONE: 'Done',
  EXCEEDED: 'Exceeded', MET: 'Met', PARTIALLY_MET: 'Partially met', NOT_MET: 'Not met',
  APPROVED: 'Approved', REJECTED: 'Rejected',
};
const label = (value) => LABEL[value] || value || '';
const TRIGGERS = ['ANNUAL_REVIEW', 'NEW_ROLE', 'CAREER_GROWTH', 'PERFORMANCE_IMPROVEMENT', 'ONBOARDING'];

const EVENT_HELP = {
  'Start development plan': 'Begin a new plan round. The employee is asked to write their goals.',
  'Request early review': 'Review progress now instead of waiting for the next 14-day check.',
};

// MUI theme for the JSONForms material renderers, in the app's colours and type.
const theme = createTheme({
  palette: { primary: { main: '#2E6B5A' }, error: { main: '#B3402F' }, text: { primary: '#17322A', secondary: '#5A6E66' } },
  typography: { fontFamily: "'Instrument Sans', system-ui, sans-serif" },
  shape: { borderRadius: 8 },
});

/** Hash routes: #/tasks, #/tasks/:id, #/cases, #/cases/:id */
function useRoute() {
  const current = () => (window.location.hash.startsWith('#/') ? window.location.hash : '#/tasks');
  const [hash, setHash] = useState(current);
  useEffect(() => {
    const onChange = () => setHash(current());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash.slice(2).split('/');
}

function useLoad(path) {
  const [state, setState] = useState({ data: null, error: null });
  const reload = useCallback(() => {
    api('GET', path)
      .then((data) => setState({ data, error: null }))
      .catch((error) => setState({ data: null, error: error.message }));
  }, [path]);
  useEffect(reload, [reload]);
  return [state.data, state.error, reload];
}

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
function ago(value) {
  if (!value) return '';
  const minutes = Math.round((new Date(value) - Date.now()) / 60000);
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute');
  if (Math.abs(minutes) < 60 * 24) return relative.format(Math.round(minutes / 60), 'hour');
  return relative.format(Math.round(minutes / (60 * 24)), 'day');
}
const day = (value) => (value ? new Date(value).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '');
const caseRolesOf = (me) => (me?.roles || []).map((r) => ROLE_LABEL[r]).filter(Boolean);

// A one-line confirmation shown on the next page after a task is saved.
let flash = null;

export default function App({ keycloak }) {
  const [page, id] = useRoute();
  const [me] = useLoad('/me');
  const roles = caseRolesOf(me);
  const isHr = me && me.roles.includes('hr');
  return (
    <>
      <header className="topbar">
        <a className="brand" href="#/tasks">Development plans</a>
        <nav>
          <a href="#/tasks" className={page === 'tasks' ? 'active' : ''}>My tasks</a>
          <a href="#/cases" className={page === 'cases' ? 'active' : ''}>Plans</a>
          {isHr && <a href="#/employees" className={page === 'employees' ? 'active' : ''}>Employees</a>}
        </nav>
        <span className="who">
          {me && <span>{me.name}<small>{roles.join(', ')}</small></span>}
          <button className="quiet" onClick={() => keycloak.logout({ redirectUri: window.location.origin })}>Sign out</button>
        </span>
      </header>
      <main>
        {page === 'tasks' && !id && <TaskList />}
        {page === 'tasks' && id && <TaskForm taskId={id} />}
        {page === 'cases' && !id && <CaseList me={me} />}
        {page === 'cases' && id && <CaseDetail caseId={id} roles={roles} />}
        {page === 'employees' && isHr && <EmployeeAdmin />}
      </main>
    </>
  );
}

function Problem({ message }) {
  return message ? <p className="problem" role="alert">{message}</p> : null;
}

function Flash() {
  const [message] = useState(() => { const m = flash; flash = null; return m; });
  return message ? <p className="flash" role="status">{message}</p> : null;
}

function TaskList() {
  const [tasks, error, reload] = useLoad('/tasks');
  return (
    <section>
      <Flash />
      <div className="page-head">
        <h1>My tasks</h1>
        <button className="quiet" onClick={reload}>Refresh</button>
      </div>
      <Problem message={error} />
      {!tasks && !error && <p className="muted">Loading your tasks…</p>}
      {tasks && tasks.length === 0 && (
        <div className="empty">
          <p><strong>Nothing to do right now.</strong></p>
          <p className="muted">Tasks show up here when a development plan needs you. Open <a href="#/cases">Plans</a> to see where each plan is.</p>
        </div>
      )}
      {tasks && tasks.length > 0 && (
        <ul className="rows">
          {tasks.map((t) => (
            <li key={t.id}>
              <a className="row-link" href={`#/tasks/${t.id}`}>
                <span className="row-main">
                  <strong>{t.taskName}</strong>
                  <span className="muted">{t.employeeName ? `${t.employeeName}'s plan` : 'Development plan'} · as {t.role}</span>
                </span>
                <span className="row-side">
                  <span className="pill todo">{TASK_STATE[t.taskState] || t.taskState}</span>
                  <small className="muted">{ago(t.createdOn)}</small>
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Renders the task's JSON form (the task-model's "jsonforms" part) with JSONForms and submits it. */
function TaskForm({ taskId }) {
  const [form, loadError] = useLoad(`/tasks/${taskId}`);
  const [data, setData] = useState(null);
  const [errors, setErrors] = useState([]);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => setData(form?.formData ?? null), [form]);
  if (loadError) return <Problem message={loadError} />;
  if (!form || !data) return <p className="muted">Loading the form…</p>;
  const task = form.task;
  const open = OPEN_TASK.includes(task.taskState);

  // Validation messages stay hidden until the first submit attempt.
  const submit = (event) => {
    event.preventDefault();
    setSubmitted(true);
    if (errors.length > 0) return;
    setBusy(true);
    setError(null);
    api('POST', `/tasks/${taskId}/complete`, data)
      .then(() => {
        flash = `${task.taskName}: saved.`;
        window.location.hash = `#/cases/${task.caseInstanceId}`;
      })
      .catch((e) => { setError(e.message); setBusy(false); });
  };

  return (
    <section className="task">
      <a className="back" href={`#/cases/${task.caseInstanceId}`}>← {form.employeeName ? `${form.employeeName}'s plan` : 'Back to the plan'}</a>
      <form onSubmit={submit} noValidate>
        <h1>{form.schema.title}</h1>
        {form.schema.description && <p className="lead">{form.schema.description}</p>}
        {!open && <p className="flash">This task is done. You are looking at what was filled in.</p>}
        <ThemeProvider theme={theme}>
          <JsonForms schema={form.schema} uischema={form.uischema} data={data} renderers={renderers}
                     cells={materialCells} readonly={!open || busy}
                     validationMode={submitted ? 'ValidateAndShow' : 'ValidateAndHide'}
                     onChange={({ data: changed, errors: found }) => { setData(changed); setErrors(found ?? []); }} />
        </ThemeProvider>
        {submitted && errors.length > 0 && <Problem message="Some fields still need an answer. They are marked in red." />}
        <Problem message={error} />
        {open && (
          <div className="form-actions">
            <button type="submit" className="primary" disabled={busy}>{busy ? 'Saving…' : form.submitLabel}</button>
            <a href={`#/cases/${task.caseInstanceId}`}>Cancel</a>
          </div>
        )}
      </form>
    </section>
  );
}

function PhaseDots({ phase }) {
  const index = PHASES.findIndex((p) => p.key === phase);
  return (
    <span className="dots" aria-hidden="true">
      {PHASES.map((p, i) => <span key={p.key} className={i < index ? 'done' : i === index ? 'now' : ''} />)}
    </span>
  );
}

function CaseList({ me }) {
  const [cases, error, reload] = useLoad('/cases');
  const [employees] = useLoad('/employees');
  const [employeeId, setEmployeeId] = useState('');
  const [trigger, setTrigger] = useState('');
  const [startError, setStartError] = useState(null);
  const canStart = me && (me.roles.includes('hr') || me.roles.includes('manager'));
  const candidates = (employees || []).filter((e) => e.managerId);
  // One active case per employee: while its plan runs no new one can be set up; a closed case gets a new round.
  const running = new Set((cases || []).filter((c) => ['plan', 'perform', 'evaluate'].includes(c.phase))
    .map((c) => c.employee?.employeeId));

  const start = () => {
    setStartError(null);
    api('POST', '/cases', { employeeId, trigger })
      .then(({ caseId }) => { window.location.hash = `#/cases/${caseId}`; })
      .catch((e) => setStartError(e.message));
  };

  return (
    <section>
      <div className="page-head">
        <h1>Plans</h1>
        <button className="quiet" onClick={reload}>Refresh</button>
      </div>
      {canStart && (
        <div className="start">
          <label htmlFor="employee">Set up development planning for</label>
          <select id="employee" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
            <option value="">Choose an employee</option>
            {candidates.map((e) => (
              <option key={e.employeeId} value={e.employeeId} disabled={running.has(e.employeeId)}>
                {e.name}{running.has(e.employeeId) ? ' (plan running)' : ''}
              </option>
            ))}
          </select>
          <label htmlFor="trigger">because of</label>
          <select id="trigger" value={trigger} onChange={(e) => setTrigger(e.target.value)}>
            <option value="">Choose a reason</option>
            {TRIGGERS.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
          <button className="primary" disabled={!employeeId || !trigger} onClick={start}>Set up</button>
          <Problem message={startError} />
        </div>
      )}
      <Problem message={error} />
      {!cases && !error && <p className="muted">Loading plans…</p>}
      {cases && cases.length === 0 && (
        <div className="empty">
          <p><strong>No development plans yet.</strong></p>
          <p className="muted">{canStart ? 'Choose an employee above to set one up.' : 'Your manager or HR sets up your development planning.'}</p>
        </div>
      )}
      {cases && cases.length > 0 && (
        <ul className="rows">
          {cases.map((c) => (
            <li key={c.caseId}>
              <a className="row-link" href={`#/cases/${c.caseId}`}>
                <span className="row-main">
                  <strong>{c.employee?.name}</strong>
                  <span className="muted">Since {day(c.startedAt)}{c.round > 0 ? ` · round ${c.round}` : ''}</span>
                </span>
                <span className="row-side">
                  <PhaseDots phase={c.phase} />
                  <span className="phase-name">{c.phase === 'waiting' && c.round === 0 ? 'Not started yet' : PHASE_LABEL[c.phase] || c.state}</span>
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const EMPTY_EMPLOYEE = { name: '', jobTitle: '', username: '', email: '', role: '', managerId: '' };

/** HR-only: creates a Keycloak account and the employee record. Keycloak emails the person a link to set their
 * own password — nobody here ever sees or handles one. */
function EmployeeAdmin() {
  const [employees, error, reload] = useLoad('/employees');
  const [form, setForm] = useState(EMPTY_EMPLOYEE);
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const [sentTo, setSentTo] = useState(null);
  const field = (name) => ({
    value: form[name],
    onChange: (e) => { setSentTo(null); setForm({ ...form, [name]: e.target.value }); },
  });
  const canSubmit = form.name && form.username && form.email && form.role;

  const submit = (event) => {
    event.preventDefault();
    setBusy(true);
    setSubmitError(null);
    api('POST', '/employees', { ...form, managerId: form.managerId || null })
      .then((result) => { setSentTo(result.email); setForm(EMPTY_EMPLOYEE); reload(); })
      .catch((e) => setSubmitError(e.message))
      .finally(() => setBusy(false));
  };

  return (
    <section>
      <div className="page-head">
        <h1>Employees</h1>
        <button className="quiet" onClick={reload}>Refresh</button>
      </div>
      <form className="start" onSubmit={submit}>
        <label htmlFor="emp-name">Name</label>
        <input id="emp-name" {...field('name')} required />
        <label htmlFor="emp-title">Job title</label>
        <input id="emp-title" {...field('jobTitle')} />
        <label htmlFor="emp-username">Username</label>
        <input id="emp-username" {...field('username')} required />
        <label htmlFor="emp-email">Email</label>
        <input id="emp-email" type="email" {...field('email')} required />
        <label htmlFor="emp-role">Role</label>
        <select id="emp-role" {...field('role')}>
          <option value="">Choose a role</option>
          <option value="employee">Employee</option>
          <option value="manager">Manager</option>
          <option value="hr">HR</option>
        </select>
        <label htmlFor="emp-manager">Manager</label>
        <select id="emp-manager" {...field('managerId')}>
          <option value="">No manager</option>
          {(employees || []).map((e) => <option key={e.employeeId} value={e.employeeId}>{e.name}</option>)}
        </select>
        <button className="primary" type="submit" disabled={!canSubmit || busy}>{busy ? 'Adding…' : 'Add employee'}</button>
        <Problem message={submitError} />
      </form>
      {sentTo && <p className="flash" role="status">Invite email sent to {sentTo}. They'll set their own password from the link in it.</p>}
      <Problem message={error} />
      {!employees && !error && <p className="muted">Loading employees…</p>}
      {employees && employees.length > 0 && (
        <ul className="rows">
          {employees.map((e) => (
            <li key={e.employeeId} className="row-link">
              <span className="row-main">
                <strong>{e.name}</strong>
                <span className="muted">{e.username}{e.jobTitle ? ` · ${e.jobTitle}` : ''}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The plan round as three steps; the current one is large. */
function PhaseStrip({ phase, round }) {
  const index = PHASES.findIndex((p) => p.key === phase);
  if (index < 0) {
    const notStarted = phase === 'waiting' && round === 0;
    return (
      <div className="strip idle">
        <p className="strip-title">{notStarted ? 'Not started yet' : 'Case is closed'}</p>
        <p className="muted">{notStarted ? 'Start the development plan to begin.'
          : phase === 'waiting' ? 'The manager or HR can start a new development plan.' : 'This case is closed.'}</p>
      </div>
    );
  }
  return (
    <ol className="strip" aria-label="Plan progress">
      {PHASES.map((p, i) => (
        <li key={p.key} className={i < index ? 'done' : i === index ? 'now' : 'next'} aria-current={i === index ? 'step' : undefined}>
          <span className="step-no">{i < index ? '✓' : i + 1}</span>
          <span className="step-name">{p.name}</span>
          {i === index && <span className="step-hint">{p.hint}</span>}
        </li>
      ))}
    </ol>
  );
}

function CaseDetail({ caseId, roles }) {
  const [data, error, reload] = useLoad(`/cases/${caseId}`);
  const [actionError, setActionError] = useState(null);
  const [busy, setBusy] = useState(false);
  if (error) return <Problem message={error} />;
  if (!data) return <p className="muted">Loading the plan…</p>;

  const act = (promise) => {
    setActionError(null);
    setBusy(true);
    promise.then(reload).catch((e) => setActionError(e.message)).finally(() => setBusy(false));
  };
  // A stage that completes leaves its unused events Available; only show those in running stages, and only the
  // events this user's case roles may raise (the backend reads the event roles from the case definition).
  const activeStages = new Set(data.planItems.filter((p) => p.currentState === 'Active').map((p) => p.id));
  const events = data.planItems.filter((p) => p.type === 'UserEvent' && p.currentState === 'Available'
    && (!p.stageId || activeStages.has(p.stageId)) && data.raisableEvents.includes(p.definitionId));
  // Activities can only be added while 2 · Perform plan runs (the planning table's stage).
  const activities = data.discretionaryItems.filter((d) => activeStages.has(d.parentId));
  const file = data.caseFile || {};
  const openTasks = data.tasks.filter((t) => OPEN_TASK.includes(t.taskState));
  const mine = openTasks.filter((t) => roles.includes(t.role));
  const others = openTasks.filter((t) => !roles.includes(t.role));

  return (
    <section>
      <Flash />
      <a className="back" href="#/cases">← All plans</a>
      <div className="page-head">
        <h1>{file.Employee?.name}</h1>
        <span className="muted">{data.round > 0 ? `Round ${data.round}` : 'Not started yet'}</span>
      </div>
      <p className="facts">
        {[file.Employee?.jobTitle, file.Employee?.managerName && `Manager: ${file.Employee.managerName}`,
          file.Employee?.trigger && `Reason: ${label(file.Employee.trigger)}`].filter(Boolean).join(' · ')}
      </p>

      <PhaseStrip phase={data.phase} round={data.round} />
      <Problem message={actionError} />

      <div className="columns">
        <div>
          <h2>For you</h2>
          {mine.length === 0 && <p className="muted">Nothing waits on you in this plan.</p>}
          <ul className="rows">
            {mine.map((t) => (
              <li key={t.id}>
                <a className="row-link attention" href={`#/tasks/${t.id}`}>
                  <span className="row-main"><strong>{t.taskName}</strong><span className="muted">as {t.role} · {ago(t.createdOn)}</span></span>
                  <span className="row-side"><span className="pill todo">Open</span></span>
                </a>
              </li>
            ))}
          </ul>
          {others.length > 0 && (
            <>
              <h3>Waiting on others</h3>
              <ul className="plain">
                {others.map((t) => <li key={t.id}>{t.taskName} <span className="muted">· {t.role}</span></li>)}
              </ul>
            </>
          )}
        </div>

        <div>
          <h2>You can</h2>
          {events.length === 0 && activities.length === 0 && <p className="muted">There is nothing to start right now.</p>}
          {events.map((e) => (
            <div key={e.id} className="action">
              <button className="primary" disabled={busy} onClick={() => act(api('POST', `/cases/${caseId}/events/${e.id}`))}>{e.name}</button>
              {EVENT_HELP[e.name] && <p className="muted">{EVENT_HELP[e.name]}</p>}
            </div>
          ))}
          {activities.length > 0 && (
            <div className="action">
              <p className="action-title">Add an activity to the plan</p>
              <div className="chips">
                {activities.map((d) => (
                  <button key={d.definitionId} className="chip" disabled={busy} onClick={() => act(api('POST', `/cases/${caseId}/plan`, d))}>+ {d.name}</button>
                ))}
              </div>
              <p className="muted">The new activity appears as a task to fill in.</p>
            </div>
          )}
        </div>
      </div>

      <PlanSummary file={file} phase={data.phase} />
    </section>
  );
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * The development plan as the case file holds it, in plain words. Activities and ProgressReviews keep growing over
 * the rounds; the copy of each finished round in PreviousRounds tells which elements belong to the current one.
 */
function PlanSummary({ file, phase }) {
  const rounds = file.PreviousRounds || [];
  const last = rounds[rounds.length - 1];
  const activities = (file.Activities || []).slice(last?.activities?.length || 0);
  const reviews = (file.ProgressReviews || []).slice(last?.progressReviews?.length || 0);
  // Until the new round's plan is sent, Plan and Approval still hold the last round's (shown under Earlier rounds).
  const plan = last && phase === 'plan' && same(file.Plan, last.plan) ? null : file.Plan;
  const approval = last && same(file.Approval, last.approval) ? null : file.Approval;
  if (!plan && rounds.length === 0) return null;
  return (
    <div className="summary">
      {plan && (
        <>
          <h2>The plan{plan.period ? ` for ${plan.period}` : ''}</h2>
          {approval?.decision && (
            <p className="muted">Manager's decision: {label(approval.decision)}{approval.comments ? ` — ${approval.comments}` : ''}</p>
          )}
          <ol className="goals">
            {(plan.goals || []).map((g, i) => (
              <li key={i}>
                <strong>{g.title}</strong> <span className="pill">{label(g.type)}</span>
                <span className="muted"> · by {day(g.targetDate)}</span>
                {g.successCriteria && <p>{g.successCriteria}</p>}
              </li>
            ))}
          </ol>
        </>
      )}
      {activities.length > 0 && (
        <>
          <h3>Activities</h3>
          <ul className="plain">
            {activities.map((a, i) => (
              <li key={i}>{a.description} <span className="muted">· {label(a.type)} · {label(a.status)}{a.relatedGoal ? ` · for “${a.relatedGoal}”` : ''}</span></li>
            ))}
          </ul>
        </>
      )}
      {reviews.length > 0 && (
        <>
          <h3>Progress reviews</h3>
          <ul className="plain">
            {reviews.map((r, i) => (
              <li key={i}>{day(r.date)}: {r.onTrack ? 'on track' : 'not on track'}{r.notes ? ` — ${r.notes}` : ''}</li>
            ))}
          </ul>
        </>
      )}
      {rounds.length > 0 && (
        <>
          <h3>Earlier rounds</h3>
          {rounds.map((r, i) => (
            <details key={i} className="round">
              <summary>{r.period || `Round ${i + 1}`}: {label(r.evaluation?.outcome) || 'evaluated'}</summary>
              {r.evaluation?.summary && <p>{r.evaluation.summary}</p>}
              <p className="muted">{(r.activities?.length || 0) - (rounds[i - 1]?.activities?.length || 0)} activities ·{' '}
                {(r.progressReviews?.length || 0) - (rounds[i - 1]?.progressReviews?.length || 0)} progress reviews</p>
              <ul className="plain">
                {(r.plan?.goals || []).map((g, j) => {
                  const result = (r.evaluation?.goalResults || []).find((x) => x.goalTitle === g.title);
                  return <li key={j}>{g.title} <span className="muted">· {result ? label(result.result) : 'no result recorded'}</span></li>;
                })}
              </ul>
            </details>
          ))}
        </>
      )}
    </div>
  );
}
