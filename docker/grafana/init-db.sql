-- First initialization only; Grafana never receives the administrator login.
\set ON_ERROR_STOP on
\getenv grafana_password GRAFANA_DB_PASSWORD
CREATE ROLE grafana LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'grafana_password';
CREATE DATABASE grafana OWNER grafana;
REVOKE ALL ON DATABASE grafana FROM PUBLIC;
\connect grafana
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO grafana;
