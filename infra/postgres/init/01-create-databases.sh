#!/bin/sh
# Runs once, on the first start of an empty data volume (docker-entrypoint-initdb.d).
# Database per service: booking, payment, notification, all owned by the application user.
set -eu

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$ROOMLOCK_DB_USER" -v app_password="$ROOMLOCK_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
CREATE DATABASE booking OWNER :"app_user";
CREATE DATABASE payment OWNER :"app_user";
CREATE DATABASE notification OWNER :"app_user";
SQL
