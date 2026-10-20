#!/usr/bin/env bash
set -euo pipefail
cd /home/mcuser/blockommander-fabric-python
set -a
. ./.env
set +a
cd src
exec ../.venv/bin/waitress-serve \
  --listen="${BLOCKCOMMANDER_BIND:-127.0.0.1}:${BLOCKCOMMANDER_PORT:-5001}" \
  --call ck_webhook:create_app
