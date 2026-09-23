#!/usr/bin/env sh
# Isolated offline stack. Slots 1..9 have distinct ports; project names isolate
# containers, Redis data, PostgreSQL pgdata and attachment-files volumes.
set -eu

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <unique-project> <slot:1..9> <config|deps|up|ps|down>" >&2
  exit 2
fi
project=$1
slot=$2
action=$3
case "$project" in
  ''|hackalem|*[!a-z0-9_-]*) echo "Use a distinct lowercase project name, not hackalem" >&2; exit 2 ;;
esac
case "$slot" in
  [1-9]) ;;
  *) echo "Slot must be 1..9" >&2; exit 2 ;;
esac
case "$action" in
  config|deps|up|ps|down) ;;
  *) echo "Action must be config, deps, up, ps or down" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME="$project"
export POSTGRES_PORT=$((55440 + slot))
export REDIS_PORT=$((56380 + slot))
export BACKEND_PORT=$((18090 + slot))
export FRONTEND_PORT=$((15190 + slot))
export VITE_API_URL="http://localhost:$BACKEND_PORT"
export CORS_ALLOWED_ORIGINS="http://localhost:$FRONTEND_PORT"
# The test profile supplies offline gateways. A local .env cannot turn on live AI
# or partner-cart writes for this command.
export OPENAI_API_KEY=
export SPRING_PROFILES_ACTIVE=test
export LLM_ADAPTER_MODE=mock
export CATALOG_STOCK_ADAPTER_MODE=sample
export CART_MODE=sample
export PARTNER_CART_VERIFIED=false

echo "$project: db=$POSTGRES_PORT redis=$REDIS_PORT api=$BACKEND_PORT ui=$FRONTEND_PORT" >&2
case "$action" in
  config) exec docker compose -f docker-compose.yml -f docker-compose.test.yml --profile test config --quiet ;;
  deps) exec docker compose -f docker-compose.yml -f docker-compose.test.yml --profile test up -d --wait db redis ;;
  up) exec docker compose -f docker-compose.yml -f docker-compose.test.yml --profile test up -d --build --wait ;;
  ps) exec docker compose -f docker-compose.yml -f docker-compose.test.yml --profile test ps ;;
  down) exec docker compose -f docker-compose.yml -f docker-compose.test.yml --profile test down ;;
esac
