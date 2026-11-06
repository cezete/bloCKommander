# bloCKommander – átadó dokumentum a következő csevegéshez

## Aktuális állapot

A projektben a CK-06 A1–A6 Program/Stage infrastruktúra, a PROGRAM blokk és az első Stage-navigáció elkészült. A munkafa szándékosan tartalmaz meglévő, még nem commitolt módosításokat; ezeket meg kell őrizni.

Az utóbbi javítások fő területei:

- PROGRAM-belépés és `/ckback` Stage-navigáció, HallRegistry-fencinggel;
- child Stage-ek logikai koordinátáinak kezelése;
- Graph origin kompatibilitás fizikai és logikai eredet között;
- idegen csarnokban a szerkesztés tiltásának helyes `FORBIDDEN` visszajelzése;
- SAVE után a tábla hiteles `MENTVE` állapotának megőrzése Graph-reconciliation közben.

## Legutóbbi teszthiba és megoldás

A Gradle tesztben a `CkWireAssetsTest` egy statikus forrásellenőrzése még a régi hívást várta:

```text
refreshSaveSign(level, savedStage.id)
```

A SAVE-kezelés helyesen módosult arra, hogy a sikeres, hiteles SAVE-válasz `dirty` értéke legyen döntő:

```text
refreshSaveSign(level, savedStage.id, savedStage.dirty)
```

A teszt elvárását ehhez igazítottuk. A célzott `CkWireAssetsTest` sikeresen lefutott, és a build `BUILD SUCCESSFUL` eredménnyel zárult.

## Ismert tesztállapot

- Python teljes teszt: korábban 480/480 PASS.
- Izolált PostgreSQL integrációs teszt: korábban 36/36 PASS.
- Kotlin teljes teszt: a javítás előtt 125/125 PASS; a legutóbbi statikus elvárás javítása után a célzott teszt PASS.
- A teljes `test` és szükség esetén a `buildAll` újrafuttatása javasolt a végleges átadás előtt.

## Következő lépés

Java 25-tel futtasd:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\tamas\.gradle'
.\gradlew.bat test --no-daemon
```

Ha a teljes ellenőrzés PASS, csomagolás/deploy előtt:

```powershell
.\gradlew.bat buildAll --no-daemon
```

## Deploy-határ

A legutóbbi módosítások Fabric/Kotlin oldaliak. Ezekhez általában szükséges:

1. `buildAll`;
2. `deployServerMod`;
3. Fabric szerver szabályos újraindítása.

Python webhook, PostgreSQL és kliensmod újratelepítése nem szükséges pusztán ezekhez a legutóbbi Kotlin-javításokhoz. Ha a futó szerveren még nincs telepítve az A6 Python child/logikai-koordináta javítása, akkor külön `deployServerPython` és webhook-restart is szükséges; ezt előbb a dirty workspace-ek ellenőrzésével kell engedélyezni.

Az üzemeltetési műveleteket a projektgazda végzi; ebben a csevegésben nem történt production deploy vagy restart.

## Minecraft acceptance

Deploy után ellenőrizendő:

1. PROGRAM blokk jobb kattintására belépés a gyermek Stage-be;
2. `/ckback` visszalépés a szülő Stage-be;
3. idegen csarnokban navigáció tiltott;
4. idegen csarnokban építés és wire-módosítás tiltott, egyértelmű owner-üzenettel;
5. saját Stage mentése után a chat és a SAVE-tábla egyaránt `MENTVE` állapotot mutat;
6. SAVE után új CK Wire lerakása nem ad téves `Stage graph állapota nem aktuális` hibát.

## Fontos korlátok

- Ne módosíts production adatbázist vagy világot ellenőrzés nélkül.
- Ne töröld és ne reseteld a meglévő dirty workspace-eket.
- Ne commitolj vagy pusholj külön jóváhagyás nélkül.
- A jelenlegi unstaged módosításokat változatlanul meg kell őrizni.
