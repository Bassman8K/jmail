-- Extensions JMail relies on. Flyway owns the schema; this file only installs extensions,
-- which require superuser and therefore cannot live in an application migration.

-- Trigram indexes power the "search as you type" subject/sender/body search.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Deterministic UUID generation on the database side for seed data.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
