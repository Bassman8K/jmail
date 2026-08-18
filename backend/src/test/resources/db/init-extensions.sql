-- Extensions the schema depends on, installed before Flyway runs in integration tests.
-- Mirrors docker/postgres/init/01-extensions.sql, which does the same for the local stack.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
