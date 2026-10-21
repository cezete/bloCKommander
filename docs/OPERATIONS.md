# Üzemeltetés

## Gradle taskok

```powershell
# Mod + Python teszt + runtime ZIP
.\gradlew.bat buildAll

# Csak a Windows kliens JAR-jának cseréje
.\gradlew.bat deployClient

# Csak a Fabric szervermod cseréje és szabályos Fabric restart
.\gradlew.bat deployServerMod

# Csak a Python webhook frissítése; nem indítja újra a Fabricot
.\gradlew.bat deployWebhook

# Sorrend: kliens -> webhook -> szervermod
.\gradlew.bat deployAll
```

`deployServerMod` világ- és modmentést készít. `deployWebhook` DB-dumpot és Python-forrásmentést
készít. Mindkettő readiness ellenőrzést és saját rollbacket használ. A normál build soha nem
indít deployt.

## Python szolgáltatás

Telepítési cél: `/home/mcuser/blockommander-fabric-python`. A `.env` titkos, nem kerül a repóba.

```bash
cd /home/mcuser/blockommander-fabric-python
python3.12 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # csak első alkalommal, majd töltsd ki helyben
set -a; . ./.env; set +a
cd src
exec ../.venv/bin/waitress-serve --listen="${BLOCKCOMMANDER_BIND:-127.0.0.1}:${BLOCKCOMMANDER_PORT:-5001}" --call ck_webhook:create_app
```

Alapértelmezett bind: `127.0.0.1:5001`; így nem ütközik a megőrzött Paper webhook `127.0.0.1:5000` portjával.

Screen indítás/leállítás:

```bash
screen -dmS blockommander-fabric-python /home/mcuser/blockommander-fabric-python/start-python.sh
screen -S blockommander-fabric-python -X quit
```

## Fabric szerver

```bash
# indítás
cd /home/mcuser/minecraft_fabric_26.3
screen -dmS blockommander-fabric ./start.sh

# konzol / szabályos leállítás
screen -r blockommander-fabric
screen -S blockommander-fabric -X stuff $'save-all flush\r'
screen -S blockommander-fabric -X stuff $'stop\r'
```

Modfrissítési sorrend: `save-all flush`, szabályos `stop`, világ- és modmentés, új JAR,
Fabric indítás és log/readiness ellenőrzés. A webhook ettől függetlenül frissíthető.
Ugyanarra a világra második szerverpéldány nem indítható.

## Visszaállás

1. Állítsd le szabályosan a Fabric screent és az új Python screent.
2. Távolítsd el vagy nevezd vissza kizárólag az új `blockommander-fabric-*.jar` artifactot a Fabric `mods` könyvtárában.
3. A régi Paper fájljaihoz és `wh` webhookjához nem nyúlt az új projekt. A régi rendszer a korábban dokumentált `minecraft_26_3`/`wh` indításával állítható vissza.
4. A DB-migrációk additívak; a `blockcommander` séma régi tábláit és adatait nem törlik. A 005-ös migráció csak új típussorokat upsertel.
