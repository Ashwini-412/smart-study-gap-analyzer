# Smart Study Gap Analyzer

A web application where students take multiple-choice quizzes and see, topic by topic, where their
learning gaps are. Answers are graded on the server. Each topic is classified from the student's
cumulative accuracy (correct answers / questions × 100):

| Accuracy (default thresholds) | Label |
|---|---|
| ≥ 75% | Strong |
| 50% – < 75% | Moderate |
| < 50% | Needs Improvement |
| no attempts in the topic | No Data |

## Architecture

```
Browser (HTML/CSS/vanilla JS, served by the same Java server)
  → Controller (JDK HttpServer, no framework) → Service → Repository (JDBC, prepared statements) → MySQL
```

- `backend/`: Java 21, Maven. Dependencies: Jackson and MySQL Connector/J only.
- `frontend/`: static pages (no build step). The Java server serves them from `/`.
- `database/`: `schema.sql`, development `seed.sql`, and setup notes.
- `docs/ARCHITECTURE.md`: design, API reference, security rules, milestone history.

## Prerequisites

- JDK 21 or newer (the build targets Java 21)
- Maven 3.9+
- MySQL 8.4 running locally
- A modern browser

## Database setup

Follow [`database/README.md`](database/README.md). In short, from the project root:

1. `sudo mysql < database/schema.sql` creates the `smart_study_gap_analyzer` database and tables.
2. Create the least-privilege `gap_app` user with a password of your choice (exact command in
   `database/README.md`).
3. Optional but recommended for a demo: `sudo mysql smart_study_gap_analyzer < database/seed.sql`
   loads 5 topics and 3 quizzes with 26 questions. The seed students' password is not in the
   repository, so for a demo register a new student.

Both scripts are not idempotent. To start over, drop the database and run them again.

## Configuration

Defaults live in `backend/src/main/resources/application.properties`. Values written as
`${ENV_VAR:default}` can be overridden with environment variables (see `.env.example`):

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `3306`, `smart_study_gap_analyzer` | MySQL location |
| `DB_USERNAME` | `gap_app` | MySQL user |
| `DB_PASSWORD` | *(none)* | **Required** for anything that touches the database; never committed |
| `SERVER_HOST`, `SERVER_PORT` | `127.0.0.1`, `8080` | Listen address |
| `FRONTEND_DIR` | `../frontend` | Static frontend directory, relative to `backend/` |
| `AUTH_SESSION_HOURS` | `24` | Login session lifetime (1–720) |

Gap thresholds are set by `gap.threshold.strong` (75) and `gap.threshold.moderate` (50) in
`application.properties`. Startup fails if they are not `0 <= moderate < strong <= 100`.

To use a local `.env` file (git-ignored):

```bash
cp .env.example .env        # then fill in DB_PASSWORD
set -a; source .env; set +a
```

## Run

```bash
cd backend
mvn compile exec:java
```

The server logs `Smart Study Gap Analyzer listening on http://127.0.0.1:8080`. Open
**http://127.0.0.1:8080/** in a browser. Signed-out visitors are sent to the sign-in page.

If `DB_PASSWORD` is not set, the server still starts and logs a warning. The pages load, but every
database-backed request fails with a generic "server had a problem" message.

## Demo walkthrough

1. Open http://127.0.0.1:8080/ and choose **Create an account**. The password needs 8–128
   characters with at least one letter and one digit. Then sign in.
2. **Dashboard:** every topic shows *No Data* until you take a quiz.
3. **Quizzes → Open quiz:** answer the questions (unanswered ones count as incorrect) and choose
   **Submit answers**.
4. **View result:** score, correct/answered counts and per-question Correct/Incorrect, all as
   graded by the server. The correct option is not revealed.
5. **History:** all attempts, newest first, each linking to its result.
6. **Dashboard:** topic gap table and summary, cumulative across all attempts.
7. **Log out:** the session is revoked on the server.

There is no page for creating quizzes. Load `seed.sql`, or use the API with a signed-in token:

```bash
TOKEN=...   # from POST /api/auth/login
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"name":"Probability"}' http://127.0.0.1:8080/api/topics
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"title":"Probability Basics","description":"Intro"}' http://127.0.0.1:8080/api/quizzes
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"topicId":1,"questionText":"P(heads) for a fair coin?","options":[{"text":"0.5","correct":true},{"text":"1","correct":false}]}' \
     http://127.0.0.1:8080/api/quizzes/1/questions
```

Use the ids returned by the first two calls in the third.

## Main API areas

All under `/api`, JSON in and out, `Authorization: Bearer <token>` except where noted. Full
reference: `docs/ARCHITECTURE.md` section 8.

- **Auth:** `POST /auth/register`, `POST /auth/login` (no token needed), `GET /auth/me`,
  `POST /auth/logout`
- **Content:** `GET|POST /topics`, `GET|POST /quizzes`, `GET /quizzes/{id}`,
  `GET|POST /quizzes/{id}/questions` (the GET never includes the answer key)
- **Attempts:** `POST /quizzes/{id}/attempts`, `GET /attempts`, `GET /attempts/{id}`
- **Gaps:** `GET /performance/gaps`
- **Health:** `GET /health` (no token needed)

## Tests

```bash
cd backend
mvn clean test
```

Service, controller, security and static-file tests run against in-memory repositories. The JDBC
tests run against the configured MySQL database only when `DB_PASSWORD` is set; otherwise they
are reported as skipped. They clean up the rows they create. One more test runs only with
`SEED_TEST_PASSWORD`.

## Security notes

- Passwords: PBKDF2-HMAC-SHA256 with a per-user salt. Plaintext is never stored or logged.
- Sessions: random tokens, stored only as SHA-256 hashes, with expiry and server-side logout. The
  browser keeps the token in `sessionStorage` only.
- The student is always identified from the token. A client-supplied student id is rejected or
  ignored.
- Grading: the client sends only `{questionId, selectedOptionId}`. Correctness and scores are
  computed and stored on the server, and later quiz edits do not change stored results.
- Input: strict JSON (unknown fields and wrong types give 400) and prepared statements
  everywhere.
- Frontend: pages are built with `textContent` only. A strict Content-Security-Policy,
  `nosniff` and `Referrer-Policy` are sent, and static serving blocks traversal, hidden files and
  symlink escapes.
- Credentials come only from environment variables. `.env` is git-ignored.

## Current limitations

- There is no admin role or quiz-authoring page. Any signed-in user can create topics, quizzes and
  questions through the API, and nothing can be edited or deleted through it.
- `/api/dashboard` and `/api/performance/topics` are planned, not implemented. The dashboard page
  combines `/api/attempts`, `/api/performance/gaps` and `/api/quizzes`.
- History and result pages fetch `/api/quizzes` to show quiz titles (one extra request).
- There is no database connection pool; each query opens a connection.
- There is no favicon (browsers log a harmless 404).
- No HTTPS or deployment setup: this is a local development server bound to 127.0.0.1 by default.
