# Development plan on CaseFabric

- `getting-started/`: the CaseFabric stack (Keycloak, case engine, Postgres, Case Designer, Case UI).
- `devplan-app/`: the Development plan app. Details and the full flow: [devplan-app/README.md](devplan-app/README.md).

## Prerequisites

Docker Desktop, running. Free ports: 8090, 8280, 2027, 2081, 3317, 5431, 1080.

## Start

Start the CaseFabric stack first; the app joins its network.

```bash
cd getting-started
docker compose up -d

cd ../devplan-app
docker compose up -d --build
```

## Open

| URL | What |
|---|---|
| http://localhost:8090 | Development plan app |
| http://localhost:2027 | Case engine API (Swagger) |
| http://localhost:2081 | Case Designer |
| http://localhost:3317 | Case UI |
| http://localhost:8280 | Keycloak admin (`admin` / `admin`) |

## Login details

Use these in the Development plan app, the Case UI and Swagger.

| Username | Password | Role |
|---|---|---|
| `rahul_mngr` | `rahul_mngr` | Manager |
| `sridhar_hr` | `sridhar_hr` | HR |
| `shanmuk_emp` | `shanmuk_emp` | Employee |
| `kaustubh_emp` | `kaustubh_emp` | Employee |

- Keycloak admin console (http://localhost:8280): `admin` / `admin`.
- Swagger (http://localhost:2027): click **Authorize**, client id `swagger-ui`, scope `openid`, then log in with a user above.

## Stop

```bash
cd devplan-app && docker compose down
cd ../getting-started && docker compose down
```
