"""Compiles a Case Designer model (compile-case.py) and deploys it through the case engine's REST API, instead of the
Deploy button in the Case Designer. Usage:

    python deploy-case.py [case] [--validate-only] [-o compiled.xml]

  case             case name in src/definitions (default: development)
  --validate-only  compile and let the engine validate, do not deploy
  -o FILE          also write the compiled XML to FILE

Steps: compile -> POST /repository/validate -> POST /repository/deploy/<case>.xml?tenant=<tenant>.
The engine stores the deployed file in its definitions folder (target/definitions here); new cases use it, running
cases keep their definition until migrated (migrate-development-cases.py).

Authentication: a Keycloak token of the devplan-backend service account (client credentials, an owner of the devplan
tenant). Use --user/--password to deploy as a user instead (password grant on the devplan-web client).
Settings can be overridden with --engine, --keycloak, --tenant, --client-id, --client-secret."""
import argparse
import importlib.util
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def load_compiler():
    spec = importlib.util.spec_from_file_location('compile_case', os.path.join(HERE, 'compile-case.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def token(args):
    if args.user:
        form = {'grant_type': 'password', 'client_id': 'devplan-web', 'username': args.user,
                'password': args.password if args.password is not None else args.user}
    else:
        form = {'grant_type': 'client_credentials', 'client_id': args.client_id, 'client_secret': args.client_secret}
    url = args.keycloak + '/protocol/openid-connect/token'
    try:
        with urllib.request.urlopen(url, urllib.parse.urlencode(form).encode()) as response:
            return json.load(response)['access_token']
    except urllib.error.HTTPError as e:
        raise SystemExit('Keycloak login failed (%s): %s' % (e.code, e.read().decode()[:300]))


def post_xml(url, xml, bearer):
    request = urllib.request.Request(url, data=xml.encode('utf-8'), method='POST', headers={
        'Content-Type': 'application/xml', 'Authorization': 'Bearer ' + bearer})
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    parser = argparse.ArgumentParser(description='Compile and deploy a case model through the engine REST API.')
    parser.add_argument('case', nargs='?', default='development')
    parser.add_argument('--definitions', default=os.path.join(HERE, 'src', 'definitions'))
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('-o', '--output')
    parser.add_argument('--engine', default='http://localhost:2027')
    parser.add_argument('--keycloak', default='http://localhost:8280/realms/devplan')
    parser.add_argument('--tenant', default='devplan')
    parser.add_argument('--client-id', default='devplan-backend')
    parser.add_argument('--client-secret', default=os.environ.get('DEVPLAN_BACKEND_CLIENT_SECRET', 'devplan-backend-secret'))
    parser.add_argument('--user')
    parser.add_argument('--password')
    args = parser.parse_args()

    xml = load_compiler().compile_case(args.definitions, args.case)
    print('compiled %s (%d bytes)' % (args.case, len(xml)))
    if args.output:
        with open(args.output, 'w', encoding='utf-8', newline='\n') as f:
            f.write(xml)
        print('written to %s' % args.output)

    bearer = token(args)
    status, body = post_xml(args.engine + '/repository/validate', xml, bearer)
    if status != 200:
        raise SystemExit('engine validation failed (%s):\n%s' % (status, body))
    print('engine validation: %s' % (body.strip() or 'ok'))
    if args.validate_only:
        return

    file_name = args.case + '.xml'
    status, body = post_xml('%s/repository/deploy/%s?tenant=%s' % (args.engine, urllib.parse.quote(file_name),
                                                                    urllib.parse.quote(args.tenant)), xml, bearer)
    if status not in (200, 201, 204):
        raise SystemExit('deploy failed (%s):\n%s' % (status, body))
    print('deployed %s to %s (tenant %s)' % (file_name, args.engine, args.tenant))


if __name__ == '__main__':
    sys.exit(main())
