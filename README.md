# bloCKommander Fabric

Közös projekt a Minecraft 26.3-as Fabric modhoz és a PostgreSQL-alapú Python szolgáltatáshoz.

## Szerkezet

- `src/main`: közös és dedikált szerveren is betöltődő Kotlin/Fabric kód.
- `src/client`: kizárólag kliensoldali adatbekérő képernyő.
- `python-service`: a meglévő CKpu átvett DB-, csarnok- és Stage-logikája. Az új alapértelmezett `fabric` driver nem ír közvetlenül Minecraft-világot.
- `deployment`: ismételhető szerveroldali indítási és telepítési segédletek.
- `docs`: funkcióleltár, architektúra és kézi ellenőrzés.

## Helyi build

Java 25 szükséges. A projektbe lokálisan letöltött JDK-val PowerShellben:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\tamas\.gradle'
.\gradlew.bat clean build runtimeBundle --no-daemon
```

A kliensre és a szerverre ugyanaz a runtime JAR kerül: `build/libs/blockommander-fabric-0.1.0.jar`.
Az artifact tartalmazza a Fabric Language Kotlin futtatókörnyezetet és a GSON-t; külön a már telepített `fabric-api-0.164.0+26.3.jar` szükséges.

Python teszt:

```powershell
$env:PYTHONPATH="$PWD\python-service\src"
python -m unittest discover -s python-service/tests -v
```

## Használat

1. A kreatív inventory **CK** füléről vedd ki a Hangár Kardot vagy a START/STOP/IF/FOR/FUN blokkot.
2. Hangár Karddal bal kattintás adja az A pontot, jobb kattintás a B pontot; ezután a saját képernyőn add meg a nevet és a stabil kódot.
3. CK-blokk csak egy betöltött Stage-en helyezhető el. Lerakás, törés és szerkesztés a szerver által ellenőrzött Python API-n megy át.
4. Meglévő CK-blokkra jobb kattintva szerkeszthető a felirat és a leírás.
5. A külön MENTÉS és DESTROY kezelőszerveket kizárólag a DB-ben nyilvántartott tulajdonos használhatja.

Részletek: [funkcióleltár](docs/FUNCTION-INVENTORY.md), [üzemeltetés](docs/OPERATIONS.md), [kézi próba](docs/MANUAL-TEST.md).
