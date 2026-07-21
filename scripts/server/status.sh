#!/usr/bin/env bash

set -Eeuo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_app_files
validate_deployment_env
compose ps

port="$(app_http_port)"

echo
echo "Health check: http://127.0.0.1:${port}/actuator/health"
curl --fail --silent --show-error "http://127.0.0.1:${port}/actuator/health"
echo

if sandbox_enabled; then
  echo
  echo "Sandbox Broker health check (private Compose network)"
  compose exec -T sandbox-broker sh -c \
    'curl --fail --silent --show-error -H "Authorization: Bearer $YANBAN_SANDBOX_BROKER_TOKEN" http://127.0.0.1:8091/internal/v1/health'
  echo
fi
