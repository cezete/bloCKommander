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
.\gradlew.bat buildAll --no-daemon
```

A kliensre és a szerverre ugyanaz a runtime JAR kerül: `build/libs/blockommander-fabric-0.1.0.jar`.
Az artifact tartalmazza a Fabric Language Kotlin futtatókörnyezetet és a GSON-t; külön a már telepített `fabric-api-0.164.0+26.3.jar` szükséges.

Python teszt:

```powershell
$env:PYTHONPATH="$PWD\python-service\src"
python -m unittest discover -s python-service/tests -v
```

## Gradle telepítési taskok

```powershell
.\gradlew.bat deployClient       # csak a helyi kliens JAR
.\gradlew.bat deployServerMod    # csak a HP Fabric mod, Python nélkül
.\gradlew.bat deployWebhook      # csak a HP Python szolgáltatás, Fabric restart nélkül
.\gradlew.bat deployAll          # kliens, webhook, majd szervermod
```

A deploy taskok nem futnak a normál `build`/`buildAll` részeként. A kliens célja
`C:\Users\tamas\Minecraft\bloCKommander\mods`; a régi kliens-JAR a mellette létrehozott
`mods-backup` könyvtárba kerül. A két szerveroldali task külön backupot és rollbacket használ.

## Használat

1. A kreatív inventory **CK** füléről vedd ki a Hangár Kardot vagy a START/STOP/IF/FOR/FUN blokkot.
2. A Hangár Kard receptje két függőleges aranyrúd, alattuk egy bot, a bot mellett pedig egy redstone por. A redstone különbözteti meg a vanilla aranykardtól.
3. Hangár Karddal bal kattintás adja az A pontot, jobb kattintás a B pontot; ezután a saját képernyőn add meg a nevet és a stabil kódot.
4. CK-blokk csak egy betöltött Stage-en helyezhető el. Lerakás, törés és szerkesztés a szerver által ellenőrzött Python API-n megy át.
5. Meglévő CK-blokkra jobb kattintva szerkeszthető a felirat és a leírás.
6. A külön MENTÉS és DESTROY kezelőszerveket kizárólag a DB-ben nyilvántartott tulajdonos használhatja.

Részletek: [funkcióleltár](docs/FUNCTION-INVENTORY.md), [üzemeltetés](docs/OPERATIONS.md), [kézi próba](docs/MANUAL-TEST.md).
