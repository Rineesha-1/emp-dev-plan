"""Moves the running development cases onto the latest deployed development.xml, so they get model changes such as
Request early review. Run it after deploying development.case from the Case Designer.

Usage: python migrate-development-cases.py [user] [password]   (default rahul_mngr / rahul_mngr)
The engine only lets a case owner migrate a case; the manager is the owner of the development cases they started for
their employees. Cases this user does not own are reported and skipped."""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = 'http://localhost:8280/realms/devplan/protocol/openid-connect/token'
ENGINE = 'http://localhost:2027'
TENANT, DEFINITION = 'devplan', 'development.xml'

user = sys.argv[1] if len(sys.argv) > 1 else 'rahul_mngr'
password = sys.argv[2] if len(sys.argv) > 2 else user
form = urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'devplan-web',
                               'username': user, 'password': password}).encode()
token = json.load(urllib.request.urlopen(KEYCLOAK, form))['access_token']


def engine(method, path, body=None):
    request = urllib.request.Request(ENGINE + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request) as response:
        text = response.read().decode()
        return json.loads(text) if text else None


cases = engine('GET', '/cases?tenant=%s&definition=development&state=Active&numberOfResults=10000' % TENANT)
print('%d active development case(s) visible to %s' % (len(cases), user))
failed = 0
for case in cases:
    try:
        engine('POST', '/cases/%s/migrate-definition' % case['id'], {'newDefinition': DEFINITION})
        print('  migrated  %s' % case['id'])
    except urllib.error.HTTPError as e:
        failed += 1
        print('  skipped   %s: %s %s' % (case['id'], e.code, e.read().decode()[:200]))
sys.exit(1 if failed else 0)
