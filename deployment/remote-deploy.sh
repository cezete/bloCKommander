#!/usr/bin/env bash
set -Eeuo pipefail
cd /home/mcuser
umask 022

FABRIC=/home/mcuser/minecraft_fabric_26.3
PY=/home/mcuser/blockommander-fabric-python
STAGE=/tmp/blockommander-fabric-stage
ZIP=/tmp/blockommander-fabric-runtime.zip
DB_DUMP=/tmp/blockommander-fabric-predeploy.sql
STATUS=/tmp/blockommander-fabric-deploy.status
LOG=/tmp/blockommander-fabric-deploy.log
PASSWORD_FILE=/home/mcuser/minecraft_server/webhook/.db-password
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="$FABRIC/deployment-backups/$STAMP"
SERVER_STOPPED=0

rm -f "$STATUS" "$LOG"
exec > >(tee "$LOG") 2>&1

screen_exists() {
  screen -list 2>/dev/null | grep -q "[.]$1[[:space:]]"
}

start_fabric() {
  env -u STY screen -dmS blockommander-fabric bash -lc \
    'cd /home/mcuser/minecraft_fabric_26.3 && exec ./start.sh'
}

stop_python() {
  if screen_exists blockommander-fabric-python; then
    screen -S blockommander-fabric-python -X quit || true
  fi
}

rollback() {
  [[ "$SERVER_STOPPED" == 1 ]] || return 0
  echo "Telepítési hiba: a korábbi Fabric modállapot visszaállítása."
  if screen_exists blockommander-fabric; then
    screen -S blockommander-fabric -X stuff $'stop\r' || true
    for _ in $(seq 1 60); do screen_exists blockommander-fabric || break; sleep 1; done
  fi
  find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -delete
  find "$BACKUP/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -exec cp -p {} "$FABRIC/mods/" \;
  stop_python
  start_fabric
  SERVER_STOPPED=0
}

finish() {
  code=$?
  trap - EXIT
  if [[ $code -ne 0 ]]; then rollback || true; fi
  printf '%s\n' "$code" > "$STATUS"
  exit "$code"
}
trap finish EXIT

test -f "$ZIP"
test -s "$DB_DUMP"
test -r "$PASSWORD_FILE"
test -x "$FABRIC/start.sh"

if [[ -e "$STAGE" ]]; then
  resolved=$(readlink -f "$STAGE")
  [[ "$resolved" == /tmp/blockommander-fabric-stage ]]
  rm -rf -- "$resolved"
fi
mkdir -p "$STAGE" "$PY" "$BACKUP/mods"
python3 -m zipfile -e "$ZIP" "$STAGE"
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
cp -p "$DB_DUMP" "$BACKUP/blockommander-predeploy.sql"

stop_python
env -u STY screen -dmS blockommander-fabric-python bash -lc \
  'exec /home/mcuser/blockommander-fabric-python/start-python.sh'
for _ in $(seq 1 45); do
  curl --fail --silent http://127.0.0.1:5001/health >/dev/null && break
  sleep 1
done
curl --fail --silent http://127.0.0.1:5001/health

if screen_exists blockommander-fabric; then
  screen -S blockommander-fabric -X stuff $'save-all flush\r'
  sleep 3
  screen -S blockommander-fabric -X stuff $'stop\r'
  for _ in $(seq 1 60); do screen_exists blockommander-fabric || break; sleep 1; done
  if screen_exists blockommander-fabric; then
    echo "A Fabric szerver nem állt le 60 másodpercen belül." >&2
    exit 30
  fi
fi
SERVER_STOPPED=1

tar -C "$FABRIC" -czf "$BACKUP/world.tar.gz" world
find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -exec cp -p {} "$BACKUP/mods/" \;
find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -delete
cp "$STAGE"/fabric-server/mods/blockommander-fabric-*.jar "$FABRIC/mods/"

start_fabric
for _ in $(seq 1 120); do
  if ss -ltn | grep -q ':25566[[:space:]]' && \
     grep -q 'bloCKommander Fabric initialized' "$FABRIC/logs/latest.log" && \
     grep -q 'Done (' "$FABRIC/logs/latest.log"; then
    break
  fi
  sleep 1
done
ss -ltn | grep -q ':25566[[:space:]]'
grep -q 'bloCKommander Fabric initialized' "$FABRIC/logs/latest.log"
grep -q 'Done (' "$FABRIC/logs/latest.log"
SERVER_STOPPED=0

curl --fail --silent http://127.0.0.1:5001/health
tail -n 160 "$FABRIC/logs/latest.log" | grep -E \
  'bloCKommander Fabric initialized|Done \(|blockommander|ERROR|Exception' || true
echo "Telepítés kész. Biztonsági mentés: $BACKUP"
