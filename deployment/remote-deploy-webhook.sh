#!/usr/bin/env bash
set -Eeuo pipefail
cd /home/mcuser
umask 022

PY=/home/mcuser/blockommander-fabric-python
STAGE=/tmp/blockommander-webhook-stage
ZIP=/tmp/blockommander-fabric-runtime.zip
DB_DUMP=/tmp/blockommander-fabric-predeploy.sql
STATUS=/tmp/blockommander-webhook-deploy.status
LOG=/tmp/blockommander-webhook-deploy.log
PASSWORD_FILE=/home/mcuser/minecraft_server/webhook/.db-password
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="/home/mcuser/minecraft_fabric_26.3/deployment-backups/$STAMP-webhook"
PYTHON_REPLACED=0

rm -f "$STATUS" "$LOG"
exec > >(tee "$LOG") 2>&1

screen_exists() { screen -list 2>/dev/null | grep -q "[.]$1[[:space:]]"; }
stop_python() {
  if screen_exists blockommander-fabric-python; then
    screen -S blockommander-fabric-python -X quit || true
  fi
}
start_python() {
  env -u STY screen -dmS blockommander-fabric-python bash -lc \
    'exec /home/mcuser/blockommander-fabric-python/start-python.sh'
}
rollback() {
  [[ "$PYTHON_REPLACED" == 1 ]] || return 0
  echo 'Webhook-telepítési hiba: a korábbi Python-forrás visszaállítása.'
  stop_python
  if [[ -s "$BACKUP/python-service.tar.gz" ]]; then
    tar -C /home/mcuser -xzf "$BACKUP/python-service.tar.gz"
    start_python
  fi
  PYTHON_REPLACED=0
}
finish() {
  code=$?
  trap - EXIT
  if [[ $code -ne 0 ]]; then rollback || true; fi
  printf '%s\n' "$code" > "$STATUS"
  exit "$code"
}
trap finish EXIT

test -s "$ZIP"
test -s "$DB_DUMP"
test -r "$PASSWORD_FILE"
if [[ -e "$STAGE" ]]; then
  resolved=$(readlink -f "$STAGE")
  [[ "$resolved" == /tmp/blockommander-webhook-stage ]]
  rm -rf -- "$resolved"
fi
mkdir -p "$STAGE" "$PY" "$BACKUP"
python3 -m zipfile -e "$ZIP" "$STAGE"
if [[ -f "$PY/start-python.sh" ]]; then
  tar --exclude='.venv' -C /home/mcuser -czf "$BACKUP/python-service.tar.gz" blockommander-fabric-python
fi
cp -p "$DB_DUMP" "$BACKUP/blockommander-predeploy.sql"

cp -a "$STAGE/python-service/." "$PY/"
cp "$STAGE/deployment/start-python.sh" "$PY/start-python.sh"
chmod 755 "$PY/start-python.sh"
python3.12 -m venv "$PY/.venv"
"$PY/.venv/bin/pip" install -r "$PY/requirements.txt"
if [[ ! -f "$PY/.env" ]]; then
  ( umask 077; cat > "$PY/.env" <<'EOF'
BLOCKCOMMANDER_BIND=127.0.0.1
BLOCKCOMMANDER_PORT=5001
BLOCKCOMMANDER_WORLD_DRIVER=fabric
BLOCKCOMMANDER_DB_HOST=127.0.0.1
BLOCKCOMMANDER_DB_PORT=5433
BLOCKCOMMANDER_DB_NAME=bloCKommander
BLOCKCOMMANDER_DB_USER=admin
BLOCKCOMMANDER_DB_PASSWORD_FILE=/home/mcuser/minecraft_server/webhook/.db-password
EOF
  )
fi
chmod 600 "$PY/.env"
PYTHON_REPLACED=1

stop_python
start_python
for _ in $(seq 1 45); do
  curl --fail --silent http://127.0.0.1:5001/health >/dev/null && break
  sleep 1
done
curl --fail --silent http://127.0.0.1:5001/health; echo
PYTHON_REPLACED=0
echo "Webhook telepítve. Biztonsági mentés: $BACKUP"
