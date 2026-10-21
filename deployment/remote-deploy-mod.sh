#!/usr/bin/env bash
set -Eeuo pipefail
cd /home/mcuser
umask 022

FABRIC=/home/mcuser/minecraft_fabric_26.3
JAR=/tmp/blockommander-fabric.jar
STATUS=/tmp/blockommander-mod-deploy.status
LOG=/tmp/blockommander-mod-deploy.log
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="$FABRIC/deployment-backups/$STAMP-mod"
SERVER_STOPPED=0

rm -f "$STATUS" "$LOG"
exec > >(tee "$LOG") 2>&1

screen_exists() { screen -list 2>/dev/null | grep -q "[.]$1[[:space:]]"; }
start_fabric() {
  env -u STY screen -dmS blockommander-fabric bash -lc \
    'cd /home/mcuser/minecraft_fabric_26.3 && exec ./start.sh'
}
rollback() {
  [[ "$SERVER_STOPPED" == 1 ]] || return 0
  echo 'Modtelepítési hiba: a korábbi Fabric modállapot visszaállítása.'
  if screen_exists blockommander-fabric; then
    screen -S blockommander-fabric -X stuff $'stop\r' || true
    for _ in $(seq 1 60); do screen_exists blockommander-fabric || break; sleep 1; done
  fi
  find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -delete
  find "$BACKUP/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -exec cp -p {} "$FABRIC/mods/" \;
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

test -s "$JAR"
test -x "$FABRIC/start.sh"
curl --fail --silent http://127.0.0.1:5001/health >/dev/null
mkdir -p "$BACKUP/mods"

if screen_exists blockommander-fabric; then
  screen -S blockommander-fabric -X stuff $'save-all flush\r'
  sleep 3
  screen -S blockommander-fabric -X stuff $'stop\r'
  for _ in $(seq 1 60); do screen_exists blockommander-fabric || break; sleep 1; done
  if screen_exists blockommander-fabric; then
    echo 'A Fabric szerver nem állt le 60 másodpercen belül.' >&2
    exit 30
  fi
fi
SERVER_STOPPED=1

tar -C "$FABRIC" -czf "$BACKUP/world.tar.gz" world
find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -exec cp -p {} "$BACKUP/mods/" \;
find "$FABRIC/mods" -maxdepth 1 -type f -name 'blockommander-fabric-*.jar' -delete
cp "$JAR" "$FABRIC/mods/blockommander-fabric-deployed.jar"

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
curl --fail --silent http://127.0.0.1:5001/health >/dev/null
SERVER_STOPPED=0

sha256sum "$FABRIC/mods/blockommander-fabric-deployed.jar"
tail -n 160 "$FABRIC/logs/latest.log" | grep -E \
  'bloCKommander Fabric initialized|Done \(|blockommander|ERROR|Exception' || true
echo "Szervermod telepítve. Biztonsági mentés: $BACKUP"
