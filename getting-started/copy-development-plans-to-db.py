"""One-off: copies the development plans of the running development cases from their case files into the devplan
database (dev_round, dev_goal, dev_activity, dev_progress_review). Until the move of the business data to the
database (Flyway V8) the case file held the whole plan, with a copy of each finished round in PreviousRounds.

Run it once after the backend with V8 is up and BEFORE migrate-development-cases.py moves the cases to the new
definition. Cases that already have rounds in the database are skipped, so running it again is safe.

Usage: python copy-development-plans-to-db.py [--dry-run] [user] [password]   (default sridhar_hr: HR sees every case)
Needs docker (psql in the case-engine-db-postgres container)."""
import json
import subprocess
import sys
import urllib.parse
import urllib.request

KEYCLOAK = 'http://localhost:8280/realms/devplan/protocol/openid-connect/token'
ENGINE = 'http://localhost:2027'
TENANT = 'devplan'

args = [a for a in sys.argv[1:] if a != '--dry-run']
dry_run = '--dry-run' in sys.argv
user = args[0] if args else 'sridhar_hr'
password = args[1] if len(args) > 1 else user
form = urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'devplan-web',
                               'username': user, 'password': password}).encode()
token = json.load(urllib.request.urlopen(KEYCLOAK, form))['access_token']


def engine(path):
    request = urllib.request.Request(ENGINE + path, headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode())


def psql(sql):
    return subprocess.run(['docker', 'exec', '-i', 'case-engine-db-postgres', 'psql', '-U', 'postgres', '-At',
                           '-v', 'ON_ERROR_STOP=1', '-c', 'SET search_path TO devplan;\n' + sql],
                          capture_output=True, text=True, check=True).stdout.strip().splitlines()[-1]


def lit(value):
    if value is None or value == '':
        return 'NULL'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return "'" + str(value).replace("'", "''") + "'"


def rounds_of(file):
    """The rounds in the case file: each finished one (PreviousRounds) and the running one, if its plan was sent."""
    previous = file.get('PreviousRounds') or []
    result, activities_seen, reviews_seen = [], 0, 0
    for r in previous:
        activities = (r.get('activities') or [])[activities_seen:]
        reviews = (r.get('progressReviews') or [])[reviews_seen:]
        activities_seen += len(activities)
        reviews_seen += len(reviews)
        result.append({'plan': r.get('plan') or {}, 'approval': r.get('approval') or {},
                       'self': r.get('selfAssessment') or {}, 'evaluation': r.get('evaluation') or {},
                       'activities': activities, 'reviews': reviews, 'status': 'CLOSED'})
    last = previous[-1] if previous else None
    plan = file.get('Plan')
    if plan and (last is None or plan != last.get('plan')):
        approval = file.get('Approval') or {}
        if last is not None and approval == last.get('approval'):
            approval = {}
        reviews = (file.get('ProgressReviews') or [])[reviews_seen:]
        status = {'APPROVED': 'APPROVED', 'REJECTED': 'REJECTED'}.get(approval.get('decision'), 'SUBMITTED')
        if any(r.get('planCompleted') for r in reviews):
            status = 'EVALUATING'
        self_assessment = file.get('SelfAssessment') or {}
        if last is not None and self_assessment == last.get('selfAssessment'):
            self_assessment = {}
        result.append({'plan': plan, 'approval': approval, 'self': self_assessment, 'evaluation': {},
                       'activities': (file.get('Activities') or [])[activities_seen:], 'reviews': reviews,
                       'status': status})
    return result


def round_sql(case_id, employee_id, number, r):
    plan, approval, self_assessment, evaluation = r['plan'], r['approval'], r['self'], r['evaluation']
    sql = [('INSERT INTO dev_round (case_id, employee_id, round_no, status, period, approval_decision, approval_comments, '
            'self_summary, self_submitted_at, eval_outcome, eval_summary, further_development, evaluated_at) VALUES '
            '(%s, %s, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s);') % (
        lit(case_id), lit(employee_id), number, lit(r['status']), lit(plan.get('period')), lit(approval.get('decision')),
        lit(approval.get('comments')), lit(self_assessment.get('summary')),
        'now()' if self_assessment else 'NULL', lit(evaluation.get('outcome')), lit(evaluation.get('summary')),
        lit(evaluation.get('furtherDevelopment')), 'now()' if r['status'] == 'CLOSED' else 'NULL')]
    round_id = "currval('dev_round_id_seq')"
    self_results = {g.get('goalTitle'): g for g in self_assessment.get('goalResults') or []}
    eval_results = {g.get('goalTitle'): g for g in evaluation.get('goalResults') or []}
    for seq, g in enumerate(plan.get('goals') or []):
        s, e = self_results.get(g.get('title'), {}), eval_results.get(g.get('title'), {})
        sql.append(('INSERT INTO dev_goal (round_id, seq, title, type, description, success_criteria, target_date, '
                    'self_result, self_comments, eval_result, eval_comments) VALUES (%s, %d, %s, %s, %s, %s, %s::date, '
                    '%s, %s, %s, %s);') % (round_id, seq, lit(g.get('title')), lit(g.get('type')),
                    lit(g.get('description')), lit(g.get('successCriteria')), lit(g.get('targetDate')),
                    lit(s.get('result')), lit(s.get('comments')), lit(e.get('result')), lit(e.get('comments'))))
    for a in r['activities']:
        sql.append(('INSERT INTO dev_activity (round_id, type, description, related_goal, due_date, status, outcome) '
                    'VALUES (%s, %s, %s, %s, %s::date, %s, %s);') % (round_id, lit(a.get('type')), lit(a.get('description')),
                    lit(a.get('relatedGoal')), lit(a.get('dueDate')), lit(a.get('status')), lit(a.get('outcome'))))
    for v in r['reviews']:
        sql.append(('INSERT INTO dev_progress_review (round_id, review_date, on_track, notes, adjustments, plan_completed) '
                    'VALUES (%s, %s::date, %s, %s, %s, %s);') % (round_id, lit(v.get('date')), lit(v.get('onTrack')),
                    lit(v.get('notes')), lit(v.get('adjustments')), lit(v.get('planCompleted'))))
    return sql


cases = engine('/cases?tenant=%s&definition=development&state=Active&numberOfResults=10000' % TENANT)
print('%d active development case(s) visible to %s%s' % (len(cases), user, ' (dry run)' if dry_run else ''))
for summary in cases:
    case_id = summary['id']
    file = engine('/cases/%s' % case_id).get('file') or {}
    employee = file.get('Employee') or {}
    if psql("SELECT count(*) FROM dev_round WHERE case_id = %s" % lit(case_id)) != '0':
        print('  skipped   %s (%s): already in the database' % (case_id, employee.get('name')))
        continue
    rounds = rounds_of(file)
    if not rounds:
        print('  nothing   %s (%s): no plan sent yet' % (case_id, employee.get('name')))
        continue
    sql = ['BEGIN;']
    for number, r in enumerate(rounds, start=1):
        sql += round_sql(case_id, employee.get('employeeId'), number, r)
    sql.append('COMMIT;')
    described = ', '.join('round %d %s: %d goals, %d activities, %d reviews' % (
        i, r['status'], len(r['plan'].get('goals') or []), len(r['activities']), len(r['reviews']))
        for i, r in enumerate(rounds, start=1))
    if not dry_run:
        psql('\n'.join(sql))
    print('  %s %s (%s): %s' % ('would copy' if dry_run else 'copied   ', case_id, employee.get('name'), described))
