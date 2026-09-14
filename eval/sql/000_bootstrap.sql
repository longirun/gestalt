-- Bootstrap eval-прибора с нуля (README «С нуля»): роль-владелец и БД.
-- Креды = target.db.* из eval/local.properties; psql properties не читает, поэтому
-- они передаются psql-переменными. Дефолты совпадают с local.properties.example
-- и unit-тестами (gestalt_eval/gestalt_eval) — при дев-дефолтах переменные не нужны:
--   psql -h localhost -p 5433 -U postgres -f eval/sql/000_bootstrap.sql
-- Свои креды (psql-переменная USER read-only, потому eval_*):
--   psql -h localhost -p 5433 -U postgres \
--     -v eval_user=myuser -v eval_password=mypass [-v eval_db=mydb] \
--     -f eval/sql/000_bootstrap.sql
-- Схему внутри БД (sql/001..006) применять не нужно — SchemaMigrator поднимает её
-- автоматически при первом прогоне любой стадии.
-- gestalt_ro (read-only живой лог Honcho) нужен только для живой разметки — блок опционален.

\if :{?eval_user}
\else
\set eval_user gestalt_eval
\endif
\if :{?eval_password}
\else
\set eval_password gestalt_eval
\endif
\if :{?eval_db}
\else
\set eval_db gestalt_eval
\endif

-- psql не интерполирует переменные внутри строковых литералов (в т.ч. dollar-quoted DO),
-- потому роли/БД создаются парами format()+\gexec: %I — идентификатор, %L — литерал.

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'eval_user', :'eval_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'eval_user')\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'eval_db', :'eval_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'eval_db')\gexec

GRANT ALL PRIVILEGES ON DATABASE :"eval_db" TO :"eval_user";

-- Опционально (живая разметка): read-only роль источника Honcho (source.db.*)
-- DO $$
-- BEGIN
--     IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gestalt_ro') THEN
--         CREATE ROLE gestalt_ro LOGIN PASSWORD 'gestalt_ro';
--     END IF;
-- END
-- $$;
-- GRANT CONNECT ON DATABASE honcho_memory TO gestalt_ro;
-- GRANT USAGE ON SCHEMA public TO gestalt_ro;
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO gestalt_ro;
