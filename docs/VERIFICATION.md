# Helyi ellenőrzési jegyzőkönyv

Dátum: 2026-10-20

## Automatizált ellenőrzések

- Java 25 + Gradle 9.7.1: `clean build runtimeBundle` sikeres.
- Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3: a fejlesztői dedikált szerver elindult, a mod inicializálódott, majd konzolos `stop` után szabályosan leállt.
- A dedikált szerver 58 modot töltött be, beleértve a becsomagolt Fabric Language Kotlint is.
- Python: 11 egység- és API-teszt sikeres. A Fabric world driver tesztje igazolja, hogy nincs mcpi-kapcsolat vagy Pythonból végzett világírás.
- Minden forrás JSON parse-olható.
- A `deployment/deploy-hp.ps1` PowerShell-szintaxisa érvényes.

## HP produkciós telepítés

- Telepítve: `/home/mcuser/minecraft_fabric_26.3/mods/blockommander-fabric-0.1.0.jar`.
- A helyi és távoli JAR SHA-256 értéke azonos: `1efd92af3116136cdd0b0be1793c063185cb4f3ca355f23567955c7a3dd1da6a`.
- Fabric: Minecraft 26.3, Loader 0.19.5, Java 25; a mod inicializálódott, a szerver `Done` állapotú és a 25566-os porton figyel.
- Python: Waitress 3.0.2, `127.0.0.1:5001`, `world_driver=fabric`; a health és mindhárom dimenzió hall-listázása HTTP 200.
- PostgreSQL: az ellenőrzés `BEGIN READ ONLY` tranzakcióban igazolta a START/STOP/IF/FOR/FUN típusokat és a `005_ck_block_types.sql` migrációt.
- A régi Paper (`minecraft_26_3`), webhook (`wh`), 25565/5000/4711 portok érintetlenül futnak.
- Utolsó teljes mentés: `/home/mcuser/minecraft_fabric_26.3/deployment-backups/20261020-191539`; tartalmazza a DB-dumpot, a korábbi mod JAR-t és a világ tömörített mentését.

## Még szükséges kézi ellenőrzés

A Windows Minecraft kliens könyvtárának útvonala nem volt megadva, és valódi játékosklienst nem vezéreltünk. Ezért a `MANUAL-TEST.md` teljes játékbeli ellenőrzőlistája még végrehajtandó, különösen a Hangar Sword kijelölés, a tulajdonosi jogosultságok, a SAVE/DESTROY vezérlők, valamint a START/STOP/IF/FOR/FUN blokkok mentése és visszaállítása. Ezeket nem jelöljük teszteltnek.
