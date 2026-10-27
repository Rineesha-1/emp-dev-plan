# Development plan app

Browser app + thin Spring Boot backend for the CaseFabric `development` case.

```
Browser ──► frontend (nginx :8090) ──/api──► backend (Spring Boot) ──same user token──► CaseFabric engine (:2027)
   │        React + JSONForms build           │                                         │
   └── login: Keycloak (:8280) ───────────────┴── employee directory only (Postgres)    └── case file = the whole plan
```

- **Keycloak** (realm `devplan`) is the only login: for this app, the engine, the engine's Swagger UI and the CaseFabric Case UI.
- **The case engine is the system of record.** The case file holds the whole development plan:

  | Case file item | What | Written by |
  |---|---|---|
  | `Employee` | name, job title, manager, reason for the plan | case start |
  | `Plan` | period and goals (title, type, description, success criteria, target date) | Define / Revise plan |
  | `Approval` | decision and comments | Approve plan |
  | `Activities` (list) | project, training or coaching: description, goal, due date, status, outcome | the three activity tasks (appended) |
  | `ProgressReviews` (list) | date, on track, notes, adjustments, plan completed | Review progress (appended) |
  | `SelfAssessment` | result per goal, summary | Self-assessment |
  | `Evaluation` | outcome, summary, result per goal, further development | Evaluate & document results |
  | `PreviousRounds` (list) | a copy of each finished round | Evaluate & document results |

  - Every task gets its data from the case file through **input mappings** and writes its form back through **output mappings**. Single items are replaced (binding refinement `replace`), lists are appended to.
  - The engine also pre-fills forms: one row per goal in the self-assessment and evaluation, and the last plan when a new round starts. List inputs show only the current round's activities and reviews.
- **Thin backend**: no business data and no code per task.
  - Task form = the task-model's `jsonforms` part; form data = the engine task's `input`; *Submit* sends the task-model's `output` field back to the engine.
  - Case pages show the engine case (case file, plan items, tasks) as it is.
  - The only table left is the employee directory (`devplan.employee`), used to start a case (employee, manager, job title, Keycloak ids).
- **JSON forms**: each task's form is the `task-model` in its `.humantask` file (`getting-started/src/definitions/dv_*.humantask`).
  - `jsonforms` (`schema` + `uischema`) is rendered by [JSONForms](https://jsonforms.io) with the material renderers in this app.
  - The top-level `schema` + `uiSchema` stay in rjsf format for the CaseFabric Case UI.
- **Engine permissions**: the engine checks case roles itself, because Spring forwards the user's token. Spring adds new Keycloak users to the engine tenant on their first call, using the `devplan-backend` service account.

## Run

Two compose projects: the CaseFabric stack (Keycloak, case engine, Postgres, Case UI, Designer) and this app.

```bash
cd getting-started && docker compose up -d           # 1. CaseFabric stack; creates the network casefabric_network
cd ../devplan-app  && docker compose up -d --build    # 2. this app: frontend + backend images
```

`docker-compose.yml` (this folder) builds two images:

| Image / container | Built from | What |
|---|---|---|
| `devplan-frontend` | `frontend/Dockerfile` | Vite builds the React app; nginx serves it on http://localhost:8090 and forwards `/api` to the backend (`frontend/nginx.conf`), so the browser uses one origin (no CORS). |
| `devplan-backend` | `backend/Dockerfile` | The Spring Boot API. Not published on the host (uncomment `ports` in the compose file to call it directly). |

- The frontend and backend share the network `devplan`. The backend also joins the external network `casefabric_network` to reach `keycloak`, `case-engine` and `case-engine-db-postgres` by name. Start the CaseFabric stack first; otherwise compose stops with "network casefabric_network declared as external, but could not be found".
- The frontend starts once the backend is healthy (it serves `/api/config`). The backend restarts until Postgres, Keycloak and the engine answer (`restart: on-failure`).
- Settings (port, database, Keycloak and engine URLs, service account secret) have defaults in the compose file and can be overridden in a `.env` file here, e.g. `DEVPLAN_PORT=9090` (then also add that address to the redirect URIs of Keycloak client `devplan-web`).
- Rebuild one part: `docker compose up -d --build frontend` or `... backend`. Stop the app: `docker compose down` (the CaseFabric stack keeps running).
- Frontend development without Docker: `cd frontend && npm install && npm run dev` (http://localhost:5173, `/api` proxied to :8090).

| What | URL | Login |
|---|---|---|
| Development plan app | http://localhost:8090 | shanmuk_emp / shanmuk_emp, kaustubh_emp / kaustubh_emp, rahul_mngr / rahul_mngr, sridhar_hr / sridhar_hr |
| Keycloak admin | http://localhost:8280 | admin / admin |
| CaseFabric Case UI | http://localhost:3317 | same users |
| Case Designer | http://localhost:2081 | – |
| Engine API (Swagger) | http://localhost:2027 | – |

The same realm also holds `lana`, `suzy`, `hank` (password = user name) for the `world` tenant demos (travelrequest, helloworld), and `admin` / `admin`, the engine platform owner. Their Keycloak ids are the ids Dex used to issue, so existing `world` cases still belong to them.

These are demo passwords for local use only.

## Test the engine API with Keycloak

**Swagger UI** (http://localhost:2027):

1. Click **Authorize**.
2. Client id `swagger-ui`, tick the `openid` scope, click **Authorize**.
3. Log in on the Keycloak page as any user above.
4. Try the endpoints, e.g. `GET /platform/user` or `GET /cases`.

**curl**, with a token from Keycloak (password grant on the `devplan-web` client):

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=devplan-web -d username=rahul_mngr -d password=rahul_mngr \n  http://localhost:8280/realms/devplan/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:2027/platform/user
curl -H "Authorization: Bearer $TOKEN" "http://localhost:2027/cases?tenant=devplan"
```

After changing `src/keycloak/devplan-realm.json`, run `docker compose up -d --force-recreate keycloak`. Keycloak keeps no volume, so it imports the realm again.

## Flow in the app

1. **rahul_mngr** (or **sridhar_hr**): *Plans* → choose Shanmuk and a reason → **Set up**.
2. **rahul_mngr** (or **sridhar_hr**): open Shanmuk's plan → *You can* → **Start development plan**.
3. **shanmuk_emp**: *My tasks* → **Define goals & plan**: plan period plus goals (title, type, success criteria, target date).
4. **rahul_mngr**: **Approve plan**. *Rejected* needs comments, and sends the plan back to *Revise plan*.
5. **Perform plan**:
   - While *2 · Perform plan* is active, the employee or manager adds activities on the plan page: *You can* → *Add an activity to the plan* (hidden in the other phases).
   - Every 14 days the manager gets **Review progress**. The manager can also start it at once with **Request early review** (plan page, *You can*; only users with the Manager case role see it); that also restarts the 14-day clock.
   - Ticking *The plan has been carried out* starts the evaluation; otherwise the next check follows.
6. **shanmuk_emp**: **Self-assessment**: a result per goal (rows pre-filled) and a summary.
7. **rahul_mngr**: **Evaluate & document results**.
   - *Further development needed* starts a new round, which starts from the last plan; the finished round is kept under *Earlier rounds*.
   - Otherwise the plan is closed, and a new one can be started.

## Change the model or the forms

```bash
cd getting-started
python generate-development.py src/definitions   # 1. write the Case Designer source files
python deploy-case.py development                # 2. compile, validate and deploy through the engine API
python migrate-development-cases.py              # 3. optional: move running cases onto the new definition
```

- `compile-case.py` compiles the source files into the single definitions XML that the engine deploys (what the Designer's Deploy button does). It runs on its own too: `python compile-case.py src/definitions development -o development.xml`. It supports stages, human tasks, milestones, timers, user events and planning tables; a model with process or case tasks (travelrequest) still has to be deployed from the Designer.
- `deploy-case.py` posts the XML to the engine: `POST /repository/validate`, then `POST /repository/deploy/development.xml?tenant=devplan`. The engine writes it to `target/definitions/development.xml`. It logs in as the `devplan-backend` service account; `--user sridhar_hr` deploys as a user. Only owners of the devplan tenant may deploy (sridhar_hr, the service account); other users get 401. `--validate-only` only validates.
- New cases use the new definition; `migrate-development-cases.py` moves running ones. Check that on a test case first when the case file changed.
- The Case Designer (http://localhost:2081) still opens the same source files, for viewing or editing the diagram.

## Code

| Part | Path |
|---|---|
| API | `backend/src/main/java/com/devplan/api`: `UserController` (config, me, employees), `CaseController`, `TaskController` |
| Engine calls | `backend/.../engine/EngineClient.java` (token forwarding, read-your-writes header), `TenantRegistrar.java` |
| Database | `backend/src/main/resources/db/migration` (Flyway; V3 drops the old form tables, the employee directory stays) |
| Containers | `docker-compose.yml`, `frontend/Dockerfile` + `frontend/nginx.conf`, `backend/Dockerfile` |
| Browser app | `frontend/src/App.jsx` (pages: My tasks, Plans, plan page with the Plan → Perform → Evaluate strip, task form), `styles.css`, `api.js`, `main.jsx` (Keycloak login) |
