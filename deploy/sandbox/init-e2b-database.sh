#!/bin/sh

set -eu

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

db_name="${SANDBOX_DB_NAME:-}"
db_user="${SANDBOX_DB_USER:-}"
db_password="${SANDBOX_DB_PASSWORD:-}"

case "$db_name" in
  ''|*[!A-Za-z0-9_]*) fail "SANDBOX_DB_NAME is invalid" ;;
esac
case "$db_user" in
  ''|*[!A-Za-z0-9_]*) fail "SANDBOX_DB_USER is invalid" ;;
esac
case "$db_password" in
  *[!A-Za-z0-9._~-]*) fail "SANDBOX_DB_PASSWORD contains unsupported characters" ;;
esac
password_length="${#db_password}"
if [ "$password_length" -lt 16 ] || [ "$password_length" -gt 128 ]; then
  fail "SANDBOX_DB_PASSWORD must contain 16 to 128 characters"
fi

attempt=0
until mysqladmin ping --host=mysql --user=root --silent >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 60 ] || fail "MySQL did not become ready"
  sleep 2
done

mysql --host=mysql --user=root --batch <<SQL
CREATE DATABASE IF NOT EXISTS \`${db_name}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${db_user}'@'%' IDENTIFIED BY '${db_password}';
ALTER USER '${db_user}'@'%' IDENTIFIED BY '${db_password}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${db_user}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON \`${db_name}\`.* TO '${db_user}'@'%';
FLUSH PRIVILEGES;
SQL

echo "Sandbox Broker database account is ready."
