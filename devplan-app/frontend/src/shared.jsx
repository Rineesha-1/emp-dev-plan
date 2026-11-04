import React, { useCallback, useEffect, useState } from 'react';
import { MaterialArrayControlRenderer, MaterialArrayLayout, materialRenderers } from '@jsonforms/material-renderers';
import { and, isObjectArrayControl, optionIs, rankWith } from '@jsonforms/core';
import { createTheme } from '@mui/material/styles';
import { api } from './api.js';

export const OPEN_TASK = ['Unassigned', 'Assigned', 'Delegated'];
export const TASK_STATE = { Unassigned: 'To do', Assigned: 'Picked up', Delegated: 'Delegated', Completed: 'Done' };
export const ROLE_LABEL = { employee: 'Employee', manager: 'Manager', hr: 'HR', it_admin: 'IT admin' };
export const caseRolesOf = (me) => (me?.roles || []).map((r) => ({ it_admin: 'ITAdmin' }[r] || ROLE_LABEL[r])).filter(Boolean);

// Lists of objects (goals, activities) as expandable rows instead of a cramped table; a list whose uiSchema control
// has the option "table": true (short rows, e.g. issued equipment) stays an editable table.
export const renderers = [
  ...materialRenderers,
  { tester: rankWith(10, isObjectArrayControl), renderer: MaterialArrayLayout },
  { tester: rankWith(11, and(isObjectArrayControl, optionIs('table', true))), renderer: MaterialArrayControlRenderer },
];

// MUI theme for the JSONForms and RJSF material renderers, in the WF-02 look: outlined fields with the label on the
// border (floating label), blue focus, Plus Jakarta Sans.
export const theme = createTheme({
  palette: {
    primary: { main: '#2563eb' }, success: { main: '#16a34a' }, error: { main: '#dc2626' },
    text: { primary: '#111827', secondary: '#4b5563' },
  },
  typography: { fontFamily: "'Plus Jakarta Sans', system-ui, sans-serif", fontSize: 13 },
  shape: { borderRadius: 8 },
  components: {
    MuiTextField: { defaultProps: { variant: 'outlined', size: 'medium' } },
    MuiFormControl: { defaultProps: { variant: 'outlined' } },
    MuiSelect: { defaultProps: { variant: 'outlined' } },
  },
});

export function useLoad(path) {
  const [state, setState] = useState({ data: null, error: null });
  const reload = useCallback(() => {
    if (!path) return;
    api('GET', path)
      .then((data) => setState({ data, error: null }))
      .catch((error) => setState({ data: null, error: error.message }));
  }, [path]);
  useEffect(reload, [reload]);
  return [state.data, state.error, reload];
}

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
export function ago(value) {
  if (!value) return '';
  const minutes = Math.round((new Date(value) - Date.now()) / 60000);
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute');
  if (Math.abs(minutes) < 60 * 24) return relative.format(Math.round(minutes / 60), 'hour');
  return relative.format(Math.round(minutes / (60 * 24)), 'day');
}
export const day = (value) => (value ? new Date(value).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' }) : '');
export const shortDay = (value) => (value ? new Date(value).toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) : '');

export function Problem({ message }) {
  return message ? <div className="alert danger" role="alert">{message}</div> : null;
}

// A one-line confirmation shown on the next page (after a task is saved, an item ticked, ...).
let flashMessage = null;
export const setFlash = (message) => { flashMessage = message; };
export function Flash() {
  const [message] = useState(() => { const m = flashMessage; flashMessage = null; return m; });
  return message ? <div className="alert success" role="status">{message}</div> : null;
}

export function Badge({ kind = 'neutral', children }) {
  return <span className={`badge badge-${kind}`}>{children}</span>;
}

const STATE_BADGE = {
  Active: ['info', 'Active'], Available: ['neutral', 'Not started'], Completed: ['success', 'Done'],
  Terminated: ['success', 'Done'], Suspended: ['warning', 'Suspended'], Failed: ['error', 'Failed'],
};
export function StateBadge({ state }) {
  const [kind, text] = STATE_BADGE[state] || ['neutral', state];
  return <Badge kind={kind}>{text}</Badge>;
}

export const STAGE_BADGE = { DRAFT: ['warning', 'Draft'], ONBOARDING: ['info', 'Onboarding'], ACTIVE: ['success', 'Active'] };

export function Initials({ name, large }) {
  const initials = (name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
  return <span className={large ? 'avatar-lg' : 'avatar-sm'}>{initials}</span>;
}
