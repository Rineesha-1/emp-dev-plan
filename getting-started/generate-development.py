"""Generates the development (Development plan cycle) CMMN source files for the CaseFabric Case Designer:
case, type, caseteam, dimensions and humantasks. Usage: python generate-development.py <definitions-dir>
Removes dv_*.humantask files in <definitions-dir> that this generator no longer produces.

The case engine is the system of record: the case file holds the whole development plan (Employee, Plan, Approval,
Activities, ProgressReviews, SelfAssessment, Evaluation, PreviousRounds). Every human task gets its data through
input mappings from the case file and writes its form back through output mappings; the devplan app is a thin
layer that shows the task's input in a JSONForms form and completes the task with the output.

Engine behaviour this model relies on (engine 1.1.35):
- A task output bound to a ZeroOrMore item appends an element (Activities, ProgressReviews, PreviousRounds). An
  output bound to an existing ZeroOrOne item is merged into it (lists element by element), so those outputs carry
  the binding refinement "replace": a submitted form is the whole item.
- An input bound to a ZeroOrMore item only passes its latest element, so list inputs use the transformation
  case.file.<Item> to pass the whole list. Inputs are taken when the task becomes active.
- In an ifPart, a ZeroOrMore context item is its latest element (ProgressReviews?.planCompleted).
- Case-file onParts are only evaluated on the first update of an item, so every sentry uses plan-item onParts plus
  ifParts. OnParts cannot connect to discretionary items, so no sentry listens to the activity tasks."""
import json
import os
import sys
from xml.sax.saxutils import escape

OUT = sys.argv[1]
CASE = 'development'
NS = 'xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:cafienne="org.cafienne"'

ROLES = ['Employee', 'Manager', 'HR']
R = {r: 'cr_dv_' + r for r in ROLES}

# ---------------------------------------------------------------- case file
# field = (name, kind[, children/enum]); kind: string | text (multi-line) | boolean | date | enum | object | array
TRIGGERS = ['ANNUAL_REVIEW', 'NEW_ROLE', 'CAREER_GROWTH', 'PERFORMANCE_IMPROVEMENT', 'ONBOARDING']
RESULTS = ['EXCEEDED', 'MET', 'PARTIALLY_MET', 'NOT_MET']
EMPLOYEE = [('employeeId', 'string'), ('name', 'string'), ('jobTitle', 'string'), ('managerId', 'string'),
            ('managerName', 'string'), ('trigger', 'enum', TRIGGERS)]
GOAL = [('title', 'string'), ('type', 'enum', ['SKILL', 'KNOWLEDGE', 'BEHAVIOUR', 'CAREER']),
        ('description', 'text'), ('successCriteria', 'text'), ('targetDate', 'date')]
PLAN = [('period', 'string'), ('goals', 'array', GOAL)]
APPROVAL = [('decision', 'enum', ['APPROVED', 'REJECTED']), ('comments', 'text')]
ACTIVITY = [('type', 'enum', ['PROJECT', 'TRAINING', 'COACHING']), ('description', 'text'),
            ('relatedGoal', 'string'), ('dueDate', 'date'), ('status', 'enum', ['PLANNED', 'IN_PROGRESS', 'DONE']),
            ('outcome', 'text')]
PROGRESS_REVIEW = [('date', 'date'), ('onTrack', 'boolean'), ('notes', 'text'), ('adjustments', 'text'),
                   ('planCompleted', 'boolean')]
GOAL_RESULT = [('goalTitle', 'string'), ('result', 'enum', RESULTS), ('comments', 'text')]
SELF_ASSESSMENT = [('goalResults', 'array', GOAL_RESULT), ('summary', 'text')]
EVALUATION = [('outcome', 'enum', RESULTS[1:]), ('summary', 'text'), ('goalResults', 'array', GOAL_RESULT),
              ('furtherDevelopment', 'boolean')]
ROUND = [('period', 'string'), ('plan', 'object', PLAN), ('approval', 'object', APPROVAL),
         ('selfAssessment', 'object', SELF_ASSESSMENT), ('evaluation', 'object', EVALUATION),
         ('activities', 'array', ACTIVITY), ('progressReviews', 'array', PROGRESS_REVIEW)]
CASE_FILE = [  # (item, multiplicity, fields)
    ('Employee', 'ExactlyOne', EMPLOYEE),
    ('Plan', 'ZeroOrOne', PLAN),
    ('Approval', 'ZeroOrOne', APPROVAL),
    ('Activities', 'ZeroOrMore', ACTIVITY),
    ('ProgressReviews', 'ZeroOrMore', PROGRESS_REVIEW),
    ('SelfAssessment', 'ZeroOrOne', SELF_ASSESSMENT),
    ('Evaluation', 'ZeroOrOne', EVALUATION),
    ('PreviousRounds', 'ZeroOrMore', ROUND),
]
ITEM_KIND = {item: ('array' if mult == 'ZeroOrMore' else 'object', fields) for item, mult, fields in CASE_FILE}
# Form parameters: the case file items, plus the single element a task appends to a list.
PARAM = dict(ITEM_KIND, Activity=('object', ACTIVITY), ProgressReview=('object', PROGRESS_REVIEW))

REQUIRED = {'Plan': ['period', 'goals'], 'goals': ['title', 'type', 'successCriteria', 'targetDate'],
            'Approval': ['decision'], 'Activity': ['type', 'description', 'status'],
            'ProgressReview': ['date', 'onTrack', 'planCompleted'], 'goalResults': ['goalTitle', 'result'],
            'SelfAssessment': ['summary'], 'Evaluation': ['outcome', 'summary', 'furtherDevelopment']}
DEFAULTS = {'onTrack': True, 'planCompleted': False, 'furtherDevelopment': False}
# Lists whose rows are prefilled from the plan goals: rows cannot be added or removed, the goal title is fixed.
FIXED_ROWS = {'goalResults': {'goalTitle'}}

# ---------------------------------------------------------------- DSL
def on(src, event='complete'):
    return (src, event)


def crit(*onparts, cond=None):
    """cond = (contextItem, spel)"""
    return {'on': list(onparts), 'if': cond}


def task(pid, name, role, output, inputs=(), prefill=None, archive=None, entry=(), repeat=None, required=False,
         doc='', plan_roles=None, fixed=None):
    """output: (form parameter, case file item it is written to). inputs: case file items shown read-only (an
    item that is also the output starts the form with its current value). prefill: SpEL that fills a new output.
    archive: (case file item, SpEL) - a second write of the output, transformed. plan_roles makes it discretionary;
    fixed: {field: value} form constants."""
    return dict(kind='humanTask', id=pid, name=name, role=role, output=output, inputs=list(inputs), prefill=prefill,
                archive=archive, entry=list(entry), exit=[], repeat=repeat, required=required, doc=doc,
                plan_roles=plan_roles, fixed=fixed or {})


def milestone(pid, name, entry=(), required=False, doc=''):
    return dict(kind='milestone', id=pid, name=name, entry=list(entry), exit=[], required=required, doc=doc)


def timer(pid, name, duration, doc=''):
    return dict(kind='timerEvent', id=pid, name=name, duration=duration, entry=[], exit=[], doc=doc)


def user_event(pid, name, roles, doc=''):
    return dict(kind='userEvent', id=pid, name=name, roles=roles, entry=[], exit=[], doc=doc)


def stage(pid, name, children, entry=(), exit=(), repeat=None, required=False, auto=False, table=(), doc='',
          maxw=1000):
    return dict(kind='stage', id=pid, name=name, children=children, entry=list(entry), exit=list(exit),
                repeat=repeat, required=required, auto=auto, table=list(table), doc=doc, maxw=maxw)


ALWAYS = ('Employee', 'true')
GOAL_RESULTS = "{'goalResults': case.file.Plan.goals.![{'goalTitle': title}]}"  # one row per goal, to rate
REJECTED = ('Approval', "Approval?.decision == 'REJECTED'")
APPROVED = ('Approval', "Approval?.decision == 'APPROVED'")
PLAN_COMPLETED = ('ProgressReviews', 'ProgressReviews?.planCompleted == true')
PLAN_NOT_COMPLETED = ('ProgressReviews', 'ProgressReviews?.planCompleted != true')
FURTHER = ('Evaluation', 'Evaluation?.furtherDevelopment == true')
NO_FURTHER = ('Evaluation', 'Evaluation?.furtherDevelopment != true')
ARCHIVE_ROUND = ("{'period': case.file.Plan.period, 'plan': case.file.Plan, 'approval': case.file.Approval, "
                 "'selfAssessment': case.file.SelfAssessment, 'evaluation': Evaluation, "
                 "'activities': case.file.Activities, 'progressReviews': case.file.ProgressReviews}")
# Activities and ProgressReviews are lists that cannot be emptied, so they keep growing over the rounds; the copy in
# PreviousRounds tells which elements belong to finished rounds.

# ---------------------------------------------------------------- model
plan_stage = stage('pi_plan', '1 · Plan', [
    task('pi_define_plan', 'Define goals & plan', 'Employee', ('Plan', 'Plan'), inputs=['Employee', 'Plan'],
         doc='Starts from the previous plan, if any. The manager may also fill it in (delegation).'),
    task('pi_approve_plan', 'Approve plan', 'Manager', ('Approval', 'Approval'), inputs=['Employee', 'Plan'],
         entry=[crit(on('pi_define_plan')), crit(on('pi_revise_plan'))], repeat=ALWAYS),
    task('pi_revise_plan', 'Revise plan', 'Employee', ('Plan', 'Plan'), inputs=['Approval', 'Plan'], repeat=ALWAYS,
         entry=[crit(on('pi_approve_plan'), cond=REJECTED)]),
    milestone('pi_plan_approved', 'Plan approved', required=True, entry=[crit(on('pi_approve_plan'), cond=APPROVED)],
              doc='Required, so the Plan stage cannot auto-complete before approval.'),
], entry=[crit(on('pi_start_plan', 'occur')), crit(on('pi_evaluate_results'), cond=FURTHER)],
    auto=True, repeat=ALWAYS, maxw=700,
    doc='Starts on Start development plan, or on Further development from the evaluation (a new round).')

progress_check = stage('pi_progress_check', 'Progress check', [
    timer('pi_progress_due', 'Progress check due', 'P14D'),
    user_event('pi_request_early_review', 'Request early review', ['Manager'],
               doc='Starts Review progress now instead of waiting for the timer.'),
    task('pi_review_progress', 'Review progress', 'Manager', ('ProgressReview', 'ProgressReviews'),
         inputs=['Plan', 'Activities', 'ProgressReviews'],
         entry=[crit(on('pi_progress_due', 'occur')), crit(on('pi_request_early_review', 'occur'))], required=True,
         doc='Starts on the 14-day timer or on Request early review, whichever comes first.'),
], auto=True, repeat=PLAN_NOT_COMPLETED, maxw=600,
    doc='Repeats ("continue") until a progress review marks the plan completed.')

# No "/" in these names: the engine cuts a planned discretionary item's name at the slash.
ACTIVITY_TASKS = [('pi_projects_assignments', 'Projects and assignments', 'PROJECT'),
                  ('pi_training_learning', 'Training and learning', 'TRAINING'),
                  ('pi_coaching_mentoring', 'Coaching and mentoring', 'COACHING')]
perform_stage = stage('pi_perform', '2 · Perform plan', [
    progress_check,
    milestone('pi_plan_performed', 'Plan performed', entry=[crit(on('pi_review_progress'), cond=PLAN_COMPLETED)]),
], entry=[crit(on('pi_plan_approved', 'occur'))], exit=[crit(on('pi_plan_performed', 'occur'))], repeat=ALWAYS,
    table=[task(pid, name, 'Employee', ('Activity', 'Activities'), inputs=['Plan'], repeat=ALWAYS,
                plan_roles=['Employee', 'Manager'], fixed={'type': atype},
                doc='Adds a %s activity to Activities.' % atype) for pid, name, atype in ACTIVITY_TASKS],
    maxw=800, doc='Exits on Plan performed, which also closes activities still open.')

evaluate_stage = stage('pi_evaluate', '3 · Evaluate plan', [
    task('pi_self_assessment', 'Self-assessment', 'Employee', ('SelfAssessment', 'SelfAssessment'),
         inputs=['Plan', 'Activities', 'ProgressReviews'], prefill=GOAL_RESULTS,
         doc='The employee rates their own progress first, goal by goal.'),
    task('pi_evaluate_results', 'Evaluate & document results', 'Manager', ('Evaluation', 'Evaluation'),
         inputs=['Plan', 'Activities', 'ProgressReviews', 'SelfAssessment'], prefill=GOAL_RESULTS,
         archive=('PreviousRounds', ARCHIVE_ROUND), entry=[crit(on('pi_self_assessment'))],
         doc='Also copies the finished round (plan, approval, activities, reviews, self-assessment, evaluation) into PreviousRounds.'),
], entry=[crit(on('pi_plan_performed', 'occur'))], auto=True, repeat=ALWAYS, required=True, maxw=500)

development_plan = stage('pi_development_plan', 'Development plan', [
    user_event('pi_start_plan', 'Start development plan', ['Manager', 'HR']),
    plan_stage,
    perform_stage,
    evaluate_stage,
    milestone('pi_plan_closed', 'Plan closed', entry=[crit(on('pi_evaluate_results'), cond=NO_FURTHER)],
              doc='At this level, not inside Evaluate plan, which could auto-complete before it occurs.'),
], exit=[crit(on('pi_plan_closed', 'occur'))], repeat=ALWAYS, maxw=1500,
    doc='Repeating. Further development starts a new round inside this instance; Plan closed ends it, a new '
        'instance then waits for the next start.')

case_level = [stage('pi_development_cycle', 'Development plan cycle', [development_plan], maxw=1600,
                    doc='Continuous: runs for the life of the case.')]
CPM_ID = 'cm_%s.case_%s_0' % (CASE, CASE)

# ---------------------------------------------------------------- XML writer
human_tasks = []  # (filename, xml)


def cdata(text):
    return '<![CDATA[%s]]>' % text


def attr(text):
    return escape(text, {'"': '&quot;'})


def doc_xml(text, ind):
    if not text:
        return ''
    return '%s<documentation textFormat="text/plain">\n%s    <text>%s</text>\n%s</documentation>\n' % (
        ind, ind, cdata(text), ind)


def rule_xml(tag, rid, ctx, body, ind):
    return ('%s<%s id="%s" contextRef="%s">\n%s    <condition id="%s_cond">\n%s        <body>%s</body>\n'
            '%s    </condition>\n%s</%s>\n' % (ind, tag, rid, ctx, ind, rid, ind, cdata(body), ind, ind, tag))


def criteria_xml(node, ind):
    s = ''
    for kind, prefix in (('entry', 'ec'), ('exit', 'xc')):
        for n, c in enumerate(node[kind]):
            cid = '%s_%s_%d' % (prefix, node['id'][3:], n)
            c['cid'] = cid
            tag = 'entryCriterion' if kind == 'entry' else 'exitCriterion'
            s += '%s<%s id="%s">\n' % (ind, tag, cid)
            if c['if']:
                ctx, body = c['if']
                s += ('%s    <ifPart contextRef="%s">\n%s        <condition>\n%s            <body>%s</body>\n'
                      '%s        </condition>\n%s    </ifPart>\n' % (ind, ctx, ind, ind, cdata(body), ind, ind))
            for m, (src, ev) in enumerate(c['on']):
                s += ('%s    <planItemOnPart id="op_%s_%d" sourceRef="%s">\n%s        <standardEvent>%s</standardEvent>\n'
                      '%s    </planItemOnPart>\n' % (ind, cid[3:], m, src, ind, ev, ind))
            s += '%s</%s>\n' % (ind, tag)
    return s


def item_control_xml(node, ind):
    if not node.get('repeat') and not node.get('required'):
        return ''
    slug = node['id'][3:]
    s = '%s<itemControl id="ic_%s">\n' % (ind, slug)
    if node.get('repeat'):
        s += rule_xml('repetitionRule', 'rr_' + slug, node['repeat'][0], node['repeat'][1], ind + '    ')
    if node.get('required'):
        s += rule_xml('requiredRule', 'rq_' + slug, ALWAYS[0], 'true', ind + '    ')
    return s + '%s</itemControl>\n' % ind


# ---------------------------------------------------------------- types and JSON schema
def type_props(fields, ind, path):
    s = ''
    for f in fields:
        name, kind = f[0], f[1]
        pid = 'sp_%s_%s' % (path, name)
        if kind in ('object', 'array'):
            mult = 'ZeroOrMore' if kind == 'array' else 'ZeroOrOne'
            s += '%s<property id="%s" name="%s" type="object" multiplicity="%s">\n' % (ind, pid, name, mult)
            s += '%s    <schema id="sc_%s_%s" name="%s">\n' % (ind, path, name, name)
            s += type_props(f[2], ind + '        ', path + '_' + name)
            s += '%s    </schema>\n%s</property>\n' % (ind, ind)
        else:
            typ = {'enum': 'string', 'date': 'string', 'text': 'string'}.get(kind, kind)
            fmt = ' format="date"' if kind == 'date' else ''
            s += '%s<property id="%s" name="%s" type="%s" multiplicity="ZeroOrOne"%s/>\n' % (ind, pid, name, typ, fmt)
    return s


# ---------------------------------------------------------------- forms (rjsf in the Case UI, JSONForms in the devplan app)
LABELS = {  # field or parameter name -> form label
    'Employee': 'Employee', 'employeeId': 'Employee number', 'name': 'Name', 'jobTitle': 'Job title',
    'managerId': 'Manager number', 'managerName': 'Manager', 'trigger': 'Reason for the plan',
    'Plan': 'Development plan', 'period': 'Plan period', 'goals': 'Goals', 'title': 'Goal',
    'description': 'Description', 'successCriteria': 'How we know it is achieved', 'targetDate': 'Target date',
    'Approval': "Manager's decision", 'decision': 'Decision', 'comments': 'Comments',
    'Activities': 'Activities in this round', 'Activity': 'Activity', 'type': 'Type', 'relatedGoal': 'Supports goal',
    'dueDate': 'Due date', 'status': 'Status', 'outcome': 'Outcome',
    'ProgressReviews': 'Progress reviews in this round', 'ProgressReview': 'Progress review', 'date': 'Review date',
    'onTrack': 'On track', 'notes': 'Notes', 'adjustments': 'Adjustments to the plan',
    'planCompleted': 'The plan has been carried out: start the evaluation',
    'SelfAssessment': 'Self-assessment', 'goalResults': 'Results per goal', 'goalTitle': 'Goal',
    'result': 'Result', 'summary': 'Summary', 'Evaluation': 'Evaluation',
    'furtherDevelopment': 'Further development needed: start a new plan round'}
ITEM_LABELS = {'goals': 'Goal', 'Activities': 'Activity', 'ProgressReviews': 'Review', 'goalResults': 'Goal result'}
ROW_TITLE = {'goals': 'title', 'Activities': 'description', 'ProgressReviews': 'date', 'goalResults': 'goalTitle'}
ENUM_LABELS = {'IN_PROGRESS': 'In progress', 'PARTIALLY_MET': 'Partially met', 'NOT_MET': 'Not met',
               'ANNUAL_REVIEW': 'Annual review', 'NEW_ROLE': 'New role', 'CAREER_GROWTH': 'Career growth',
               'PERFORMANCE_IMPROVEMENT': 'Performance improvement', 'BEHAVIOUR': 'Behaviour'}
MIN_ITEMS = {'goals': 1}
REQUIRED_IF = {  # form object -> (field, value that makes 'comments' required)
    'Approval': ('decision', 'REJECTED')}
FORM_HELP = {
    'define_plan': 'Set the plan period and add your goals: what you want to develop, how you will know it is '
                   'achieved and by when. The plan then goes to your manager for approval.',
    'approve_plan': 'Approve the plan, or reject it with comments to send it back to the employee.',
    'revise_plan': "Change the plan based on your manager's comments, then send it for approval again.",
    'projects_assignments': 'Describe the project or assignment, the goal it supports and its status.',
    'training_learning': 'Describe the training or course, the goal it supports and its status.',
    'coaching_mentoring': 'Describe the coaching or mentoring, the goal it supports and its status.',
    'review_progress': 'Record how the plan is going. Tick "The plan has been carried out" to start the evaluation; '
                       'otherwise the next check is in 14 days.',
    'self_assessment': 'Rate your own result for each goal before your manager evaluates the plan.',
    'evaluate_results': 'Record the result per goal and overall. Tick "Further development needed" to start a new '
                        'plan round; otherwise the plan is closed.'}
SUBMIT_LABELS = {  # what the devplan app's submit button says, per form
    'define_plan': 'Send for approval', 'approve_plan': 'Save decision', 'revise_plan': 'Send for approval',
    'projects_assignments': 'Save activity', 'training_learning': 'Save activity', 'coaching_mentoring': 'Save activity',
    'review_progress': 'Save review', 'self_assessment': 'Send to manager', 'evaluate_results': 'Save evaluation'}


def enum_label(value):
    return ENUM_LABELS.get(value, value.capitalize().replace('_', ' '))


def enum_schema(values, jsonforms):
    """rjsf (Case UI) labels enums with enumNames; JSONForms (devplan app) with oneOf const/title."""
    if jsonforms:
        return {'oneOf': [{'const': v, 'title': enum_label(v)} for v in values]}
    return {'enum': values, 'enumNames': [enum_label(v) for v in values]}


def json_schema(fields, title, required_key=None, fixed=None, jsonforms=False):
    props = {}
    for f in fields:
        name, kind = f[0], f[1]
        label = LABELS.get(name, name)
        if kind == 'object':
            p = json_schema(f[2], label, name, jsonforms=jsonforms)
        elif kind == 'array':
            p = {'type': 'array', 'title': label,
                 'items': json_schema(f[2], ITEM_LABELS.get(name, label), name, jsonforms=jsonforms)}
            if name in MIN_ITEMS:
                p['minItems'] = MIN_ITEMS[name]
        elif kind == 'enum':
            p = {'type': 'string', 'title': label, **enum_schema(f[2], jsonforms)}
        elif kind == 'date':
            p = {'type': 'string', 'title': label, 'format': 'date'}
        else:
            p = {'type': 'string' if kind == 'text' else kind, 'title': label}
        if name in DEFAULTS:
            p['default'] = DEFAULTS[name]
        if fixed and name in fixed:
            p = {'type': 'string', 'title': label, **enum_schema([fixed[name]], jsonforms), 'default': fixed[name]}
        props[name] = p
    s = {'type': 'object', 'title': title, 'properties': props}
    if required_key in REQUIRED:
        s['required'] = REQUIRED[required_key]
    if required_key in REQUIRED_IF:  # validated by ajv; 'required' in the if-part keeps an empty form quiet
        field, value = REQUIRED_IF[required_key]
        s['if'] = {'properties': {field: {'const': value}}, 'required': [field]}
        s['then'] = {'required': ['comments']}
    return s


def param_schema(param, fixed=None, jsonforms=False):
    kind, fields = PARAM[param]
    label = LABELS.get(param, param)
    if kind == 'array':
        return {'type': 'array', 'title': label,
                'items': json_schema(fields, ITEM_LABELS.get(param, label), param, jsonforms=jsonforms)}
    return json_schema(fields, label, param, fixed, jsonforms)


def field_ui(fields, readonly):
    """uiSchema for one form object: multi-line texts, fixed field order, locked lists when read-only."""
    ui = {'ui:order': [f[0] for f in fields]}
    for f in fields:
        if f[1] == 'text':
            ui[f[0]] = {'ui:widget': 'textarea', 'ui:options': {'rows': 3}}
        elif f[1] in ('object', 'array'):
            ui[f[0]] = param_ui(f[1], f[2], readonly)
            if f[0] in FIXED_ROWS and not readonly:
                ui[f[0]]['ui:options'] = {'addable': False, 'removable': False, 'orderable': False}
                ui[f[0]]['items'].update({k: dict(ui[f[0]]['items'].get(k, {}), **{'ui:readonly': True})
                                          for k in FIXED_ROWS[f[0]]})
    return ui


def param_ui(kind, fields, readonly):
    ui = field_ui(fields, readonly)
    if kind == 'array':
        ui = {'items': ui}
        if readonly:
            ui['ui:options'] = {'addable': False, 'removable': False, 'orderable': False}
    if readonly:
        ui['ui:readonly'] = True
    return ui


def jf_control(scope, readonly, label=None, **options):
    if readonly:
        options['readonly'] = True
    c = {'type': 'Control', 'scope': scope}
    if label is not None:
        c['label'] = label
    if options:
        c['options'] = options
    return c


def jf_fields(fields, readonly, fixed, scope='#'):
    """JSONForms controls for the fields of one object: texts multi-line, lists of objects with a detail layout
    (scopes inside a list's detail are relative to one element, hence the default '#')."""
    controls = []
    for f in fields:
        name, kind = f[0], f[1]
        path = '%s/properties/%s' % (scope, name)
        if kind == 'array':
            lock = {'disableAdd': True, 'disableRemove': True} if readonly or name in FIXED_ROWS else {}
            detail = jf_fields(f[2], readonly, FIXED_ROWS.get(name, {}))
            controls.append(jf_control(path, readonly, showSortButtons=False, elementLabelProp=ROW_TITLE.get(name),
                                       detail={'type': 'VerticalLayout', 'elements': detail},
                                       **lock))
        elif kind == 'text':
            controls.append(jf_control(path, readonly, multi=True))
        else:
            controls.append(jf_control(path, readonly or name in fixed))
    return controls


CONTEXT_FIELDS = {'Employee': ['name', 'jobTitle', 'managerName', 'trigger']}  # read-only parts worth showing


def jf_ui(param, readonly, fixed):
    """JSONForms uischema for one form parameter: a group with its fields, or a read-only list."""
    kind, fields = PARAM[param]
    scope = '#/properties/' + param
    if kind == 'array':
        return {'type': 'Group', 'label': LABELS.get(param, param), 'elements': [jf_control(
            scope, True, label=False, disableAdd=True, disableRemove=True, showSortButtons=False,
            elementLabelProp=ROW_TITLE.get(param),
            detail={'type': 'VerticalLayout', 'elements': jf_fields(fields, True, {})})]}
    shown = [f for f in fields if not readonly or f[0] in CONTEXT_FIELDS.get(param, [f[0]])]
    return {'type': 'Group', 'label': LABELS.get(param, param), 'elements': jf_fields(shown, readonly, fixed, scope)}


def human_task_file(node):
    """The task-model is the form. "schema"/"uiSchema" are for rjsf in the CaseFabric Case UI; "jsonforms" holds the
    same form for JSONForms in the devplan app. The form's data is the task input; "output" names the input field
    whose value completes the task (the engine writes it to the case file)."""
    slug = node['id'][3:]
    fname = 'dv_%s.humantask' % slug
    out = node['output'][0]
    context = [i for i in node['inputs'] if i != out]  # read-only; an input that is also the output is editable
    props = {i: param_schema(i) for i in context}
    props[out] = param_schema(out, node['fixed'])
    ui = {i: param_ui(PARAM[i][0], PARAM[i][1], True) for i in context}
    ui[out] = param_ui(PARAM[out][0], PARAM[out][1], False)
    ui['ui:order'] = context + [out]  # read the context first, then fill in the output
    jf_props = {i: param_schema(i, jsonforms=True) for i in context}
    jf_props[out] = param_schema(out, node['fixed'], jsonforms=True)
    jsonforms = {'schema': {'title': node['name'], 'description': FORM_HELP.get(slug, ''), 'type': 'object',
                            'required': [out], 'properties': jf_props},
                 'submitLabel': SUBMIT_LABELS.get(slug, 'Complete task'),
                 'uischema': {'type': 'VerticalLayout',  # read the context first, then fill in the output
                              'elements': [jf_ui(i, True, {}) for i in context] + [jf_ui(out, False, node['fixed'])]}}
    params = ''.join('        <input id="i_%s__%s" name="%s"/>\n' % (i, slug, i) for i in node['inputs'])
    if node['prefill'] and out not in node['inputs']:
        params += '        <input id="i_%s__%s" name="%s"/>\n' % (out, slug, out)
    params += '        <output id="o_%s__%s" name="%s"/>\n' % (out, slug, out)
    model = json.dumps({'schema': {'title': node['name'], 'description': FORM_HELP.get(slug, ''), 'type': 'object',
                                   'required': [out], 'properties': props}, 'uiSchema': ui, 'jsonforms': jsonforms,
                        'output': out}, indent=2, ensure_ascii=False)
    name = ''.join(w.capitalize() for w in slug.split('_'))
    xml = ('<humanTask %s>\n    <cafienne:implementation name="%s" class="org.cafienne.cmmn.definition.task.WorkflowTaskDefinition">\n'
           '        <documentation textFormat="text/plain">\n            <text>%s</text>\n'
           '        </documentation>\n%s        <task-model>%s</task-model>\n    </cafienne:implementation>\n</humanTask>\n'
           % (NS, name, cdata('%s. Owner: %s. %s' % (node['name'], node['role'], node['doc'])), params, cdata(model)))
    human_tasks.append((fname, xml))
    return fname


def mapping_xml(mid, src, tgt, transformation, ind):
    if not transformation:
        return '%s<parameterMapping id="%s" sourceRef="%s" targetRef="%s"/>\n' % (ind, mid, src, tgt)
    return ('%s<parameterMapping id="%s" sourceRef="%s" targetRef="%s">\n%s    <transformation id="%s_t">\n'
            '%s        <body>%s</body>\n%s    </transformation>\n%s</parameterMapping>\n'
            % (ind, mid, src, tgt, ind, mid, ind, cdata(transformation), ind, ind))


ARCHIVED_AS = {'Activities': 'activities', 'ProgressReviews': 'progressReviews'}  # key in a PreviousRounds element


def list_input(item):
    """SpEL for a list input: the whole list, or for Activities / ProgressReviews only the current round's elements
    (those after the ones copied into the last PreviousRounds element)."""
    whole = 'case.file.%s' % item
    if item not in ARCHIVED_AS:
        return whole
    rounds = 'case.file.PreviousRounds'
    done = '(%s.size() == 0 ? 0 : (%s[%s.size() - 1].%s?.size() ?: 0))' % (rounds, rounds, rounds, ARCHIVED_AS[item])
    return '%s.subList(%s, %s.size())' % (whole, done, whole)


def node_xml(node, ind):
    k, pid = node['kind'], node['id']
    slug, name = pid[3:], attr(node['name'])
    i4 = ind + '    '
    if k == 'timerEvent':
        s = '%s<timerEvent id="%s" name="%s">\n' % (ind, pid, name) + doc_xml(node['doc'], i4)
        s += '%s<timerExpression id="te_%s">\n%s    <body>%s</body>\n%s</timerExpression>\n' % (
            i4, slug, i4, cdata(node['duration']), i4)
        return s + '%s<planItemStartTrigger id="tr_%s"/>\n%s</timerEvent>\n' % (i4, slug, ind)
    if k == 'userEvent':
        s = '%s<userEvent id="%s" name="%s" authorizedRoleRefs="%s">\n' % (
            ind, pid, name, ' '.join(R[r] for r in node['roles']))
        return s + doc_xml(node['doc'], i4) + '%s</userEvent>\n' % ind
    if k == 'milestone':
        s = '%s<milestone id="%s" name="%s">\n' % (ind, pid, name)
        s += doc_xml(node['doc'], i4) + criteria_xml(node, i4) + item_control_xml(node, i4)
        return s + '%s</milestone>\n' % ind
    if k == 'stage':
        s = '%s<stage id="%s" name="%s" autoComplete="%s">\n' % (ind, pid, name, str(node['auto']).lower())
        s += doc_xml(node['doc'], i4) + criteria_xml(node, i4) + item_control_xml(node, i4)
        for ch in node['children']:
            s += node_xml(ch, i4)
        if node['table']:
            s += '%s<planningTable id="pt_%s">\n' % (i4, slug)
            for ch in node['table']:
                s += node_xml(ch, i4 + '    ')
            s += '%s</planningTable>\n' % i4
        return s + '%s</stage>\n' % ind
    # humanTask: inputs read from the case file, the output written back (plus an optional archive copy)
    fname = human_task_file(node)
    plan = ' authorizedRoleRefs="%s"' % ' '.join(R[r] for r in node['plan_roles']) if node['plan_roles'] else ''
    s = '%s<humanTask id="%s" name="%s" isBlocking="true" performerRef="%s"%s>\n' % (
        ind, pid, name, R[node['role']], plan)
    s += doc_xml('Owner: %s. %s' % (node['role'], node['doc']), i4) + criteria_xml(node, i4) + item_control_xml(node, i4)
    out, out_item = node['output']
    mappings = []  # (id, source, target, transformation)
    for i in node['inputs']:  # lists: the whole list, not just its latest element
        s += '%s<inputs id="in_%s_%s" name="%s" bindingRef="%s"/>\n' % (i4, slug, i, i, i)
        mappings.append(('pm_%s_in_%s' % (slug, i), 'in_%s_%s' % (slug, i), 'i_%s__%s' % (i, slug),
                         list_input(i) if ITEM_KIND[i][0] == 'array' else None))
    if node['prefill'] and out not in node['inputs']:
        s += '%s<inputs id="in_%s_%s" name="%s" bindingRef="%s"/>\n' % (i4, slug, out, out, out_item)
        mappings.append(('pm_%s_in_%s' % (slug, out), 'in_%s_%s' % (slug, out), 'i_%s__%s' % (out, slug),
                         node['prefill']))
    if ITEM_KIND[out_item][0] == 'object':  # outputs merge into an existing item by default; a form is the whole item
        s += ('%s<outputs id="out_%s_%s" name="%s" bindingRef="%s">\n%s    <bindingRefinement>\n%s        <body>%s</body>\n'
              '%s    </bindingRefinement>\n%s</outputs>\n' % (i4, slug, out, out, out_item, i4, i4, cdata('replace'), i4, i4))
    else:  # a list: the output is appended
        s += '%s<outputs id="out_%s_%s" name="%s" bindingRef="%s"/>\n' % (i4, slug, out, out, out_item)
    mappings.append(('pm_%s_out_%s' % (slug, out), 'o_%s__%s' % (out, slug), 'out_%s_%s' % (slug, out), None))
    if node['archive']:
        item, spel = node['archive']
        s += '%s<outputs id="out_%s_%s" name="%s" bindingRef="%s"/>\n' % (i4, slug, item, item, item)
        mappings.append(('pm_%s_out_%s' % (slug, item), 'o_%s__%s' % (out, slug), 'out_%s_%s' % (slug, item), spel))
    s += '%s<extensionElements mustUnderstand="false">\n' % i4
    s += '%s    <cafienne:implementation xmlns:cafienne="org.cafienne" humanTaskRef="%s">\n' % (i4, fname)
    for mid, src, tgt, tr in mappings:
        s += mapping_xml(mid, src, tgt, tr, i4 + '        ')
    s += '%s    </cafienne:implementation>\n%s</extensionElements>\n' % (i4, i4)
    return s + '%s</humanTask>\n' % ind


CASE_DOC = ('Development plan cycle as its own case, one per employee. The case file is the system of record for '
            'the whole plan; the devplan app only shows task inputs as forms and completes tasks with their output. '
            'The case is continuous: it has no completion milestone.')

start_schema = {'schema': {'type': 'object', 'required': ['Employee'],
                           'properties': {'Employee': json_schema(EMPLOYEE, 'Employee')}}}
start_schema['schema']['properties']['Employee']['required'] = ['employeeId', 'name', 'managerId', 'trigger']

body = ''.join(node_xml(n, '        ') for n in case_level)
case_xml = ('<case %s id="%s.case" name="%s" expressionLanguage="spel">\n' % (NS, CASE, CASE) +
            doc_xml(CASE_DOC, '    ') +
            '    <caseFileModel typeRef="%s.type"/>\n' % CASE +
            '    <casePlanModel id="%s" name="Development" autoComplete="false">\n' % CPM_ID +
            body + '    </casePlanModel>\n    <caseRoles caseTeamRef="%s.caseteam"/>\n' % CASE +
            '    <input id="in_Employee" name="Employee" bindingRef="Employee"/>\n'
            '    <extensionElements mustUnderstand="false">\n'
            '        <cafienne:start-case-model xmlns:cafienne="org.cafienne">%s</cafienne:start-case-model>\n'
            '    </extensionElements>\n</case>\n' % json.dumps(start_schema, indent=2))

# ---------------------------------------------------------------- type and caseteam
t = '<type %s id="%s.type" name="%s">\n    <schema>\n' % (NS, CASE, CASE)
for item, mult, fields in CASE_FILE:
    t += '        <property id="sp_%s" name="%s" type="object" multiplicity="%s">\n' % (item, item, mult)
    t += '            <schema id="sc_%s" name="%s">\n' % (item, item)
    t += type_props(fields, '                ', item)
    t += '            </schema>\n        </property>\n'
t += '    </schema>\n</type>\n'

team = ('<caseteam %s id="%s.caseteam" name="%s">\n    <documentation textFormat="text/plain">\n'
        '        <text><![CDATA[Case team of the development plan case.]]></text>\n'
        '    </documentation>\n' % (NS, CASE, CASE))
team += ''.join('    <role id="%s" name="%s"/>\n' % (R[r], r) for r in ROLES) + '</caseteam>\n'

# ---------------------------------------------------------------- layout
LEAF = {'humanTask': (140, 80), 'milestone': (120, 40), 'timerEvent': (32, 32), 'userEvent': (32, 32)}
GAPX, GAPY, PADL, PADT, PADR, PADB = 70, 50, 40, 45, 40, 30


def measure(node):
    if node['kind'] != 'stage':
        node['w'], node['h'] = LEAF[node['kind']]
        return
    x, y, rowh, maxx = PADL, PADT, 0, 0
    for ch in node['children'] + node.get('table', []):
        measure(ch)
        if x > PADL and x + ch['w'] > node['maxw']:
            x, y = PADL, y + rowh + GAPY
            rowh = 0
        ch['rx'], ch['ry'] = x, y
        event = ch['kind'] in ('timerEvent', 'userEvent')
        x += ch['w'] + GAPX + (60 if event else 0)  # room for the event's label, drawn below it
        rowh = max(rowh, ch['h'] + (20 if event else 0))
        maxx = max(maxx, x - GAPX)
    node['w'] = max(maxx + PADR, 220)
    node['h'] = y + rowh + PADB


shapes, edges = [], []


def place(node, ax, ay):
    shapes.append((node['id'], ax, ay, node['w'], node['h']))
    for kind, side in (('entry', 0), ('exit', 1)):
        for n, c in enumerate(node.get(kind, [])):
            cx = ax - 6 if side == 0 else ax + node['w'] - 6
            shapes.append((c['cid'], cx, ay + 10 + 24 * n, 12, 20))
            for src, _ in c['on']:
                edges.append((src, c['cid']))
    if node.get('table'):
        shapes.append(('pt_' + node['id'][3:], ax + node['w'] - 60, ay - 12, 24, 16))
    for ch in node.get('children', []) + node.get('table', []):
        place(ch, ax + ch['rx'], ay + ch['ry'])


root = {'kind': 'stage', 'id': CPM_ID, 'children': case_level, 'maxw': 1700, 'entry': [], 'exit': []}
measure(root)
place(root, 20, 10)
dims = '<CMMNDI %s>\n    <CMMNDiagram>\n' % NS
for sid, x, y, w, h in shapes:
    dims += ('        <CMMNShape cmmnElementRef="%s">\n            <Bounds x="%d" y="%d" width="%d" height="%d"/>\n'
             '        </CMMNShape>\n' % (sid, x, y, w, h))
for src, tgt in edges:
    dims += '        <CMMNEdge sourceCMMNElementRef="%s" targetCMMNElementRef="%s"/>\n' % (src, tgt)
dims += '    </CMMNDiagram>\n</CMMNDI>\n'

# ---------------------------------------------------------------- write
files = {CASE + '.case': case_xml, CASE + '.type': t, CASE + '.caseteam': team, CASE + '.dimensions': dims}
files.update(dict(human_tasks))
for stale in sorted(os.listdir(OUT)):
    if stale.startswith('dv_') and stale.endswith(('.humantask', '.process')) and stale not in files:
        os.remove(os.path.join(OUT, stale))
        print('removed stale ' + stale)
for name, content in files.items():
    with open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(content)
print('wrote %d files' % len(files))
