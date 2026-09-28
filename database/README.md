# Database setup (MySQL 8.4, local)

Run these from the project root, in order.

## 1. Create the database and tables

```bash
sudo mysql < database/schema.sql
```

## 2. Create the application user (least privilege)

Choose your own strong password and replace `CHOOSE_A_STRONG_PASSWORD`.
Do not commit it anywhere.

```bash
sudo mysql -e "CREATE USER 'gap_app'@'localhost' IDENTIFIED BY 'CHOOSE_A_STRONG_PASSWORD'; GRANT SELECT, INSERT, UPDATE, DELETE ON smart_study_gap_analyzer.* TO 'gap_app'@'localhost';"
```

The app user can read and write rows but cannot create or drop tables.

## 3. (Optional) Load development seed data

```bash
sudo mysql smart_study_gap_analyzer < database/seed.sql
```

## 4. Give the application its credentials

The app reads environment variables (see `.env.example`):

```bash
export DB_PASSWORD='the password from step 2'
```

Then verify connectivity from the `backend/` directory:

```bash
mvn test -Dtest=DatabaseConnectionTest
```

This test is **skipped** when `DB_PASSWORD` is not set.

## Reset

`schema.sql` and `seed.sql` are not idempotent. To start over, drop the
database yourself (`sudo mysql -e "DROP DATABASE smart_study_gap_analyzer"`)
and repeat steps 1 and 3. The app user from step 2 survives a database drop.
