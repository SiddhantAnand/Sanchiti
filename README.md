# Sanchiti

Personal finance tracker: bank SMS in (e.g. from an iPhone Shortcut), categorised spending, per-bank totals and
weekly Gemini insights out. Multi-user (invite-only); every user sees only their own data.

| Part | Stack | Folder |
|---|---|---|
| API | Java 21, Spring Boot 4, PostgreSQL, Flyway | `backend/` |
| Web | React + Vite + TypeScript, served by nginx | `web/` |

## Run everything locally (Docker)

```sh
cp backend/.env.example backend/.env   # set APP_LOGIN, APP_USER, APP_PASSWORD (+ optional GEMINI_API_KEY)
docker compose up -d --build           # http://localhost:8787
```

The web container serves the UI and forwards `/api` to the API container, so only one port is exposed.

## Develop

```sh
cd backend && ./mvnw spring-boot:run   # API on :8787 (needs a local Postgres, see .env.example)
cd web && npm install && npm run dev   # UI on :5173, proxies /api to :8787
cd backend && ./mvnw test              # needs a local database named ExpenseTracker_test
```

## Deploy

Two containers plus Postgres. Images are published to GHCR on every push to `main`
(`ghcr.io/<owner>/sanchiti-api`, `ghcr.io/<owner>/sanchiti-web`).

- **API** env: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_LOGIN`, `APP_USER`, `APP_PASSWORD`, `SECURE_COOKIES=true`
  (all options: [backend/.env.example](backend/.env.example)).
- **Web** env: `API_URL` = the API's URL, without a trailing slash. Most hosts set `PORT` themselves.
- Use the web URL everywhere, including the Shortcut: `https://<web>/api/transactions/sms`.

## CI/CD

[.github/workflows/ci.yml](.github/workflows/ci.yml): tests on every push/PR; on `main` it publishes both images and
calls the host's deploy hooks if the repository secrets `DEPLOY_HOOK_API` / `DEPLOY_HOOK_WEB` are set.

## Backups

Settings → **Download my data** gives each user a JSON file with all their own rows (no passwords or keys).
