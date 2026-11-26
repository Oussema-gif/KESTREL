\getenv maintenance_password MAINTENANCE_DB_PASSWORD
\getenv inventory_password INVENTORY_DB_PASSWORD

BEGIN;
SET password_encryption = 'scram-sha-256';

CREATE ROLE maintenance_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
    PASSWORD :'maintenance_password';
CREATE ROLE inventory_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
    PASSWORD :'inventory_password';

REVOKE ALL ON DATABASE kestrel FROM PUBLIC;
GRANT CONNECT ON DATABASE kestrel TO maintenance_app, inventory_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA maintenance AUTHORIZATION maintenance_app;
CREATE SCHEMA inventory AUTHORIZATION inventory_app;

ALTER ROLE maintenance_app IN DATABASE kestrel SET search_path = maintenance;
ALTER ROLE inventory_app IN DATABASE kestrel SET search_path = inventory;
COMMIT;