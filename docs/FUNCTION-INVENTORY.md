# Bizonyítékokra épülő funkcióleltár

## Régi rendszer és átvétel

| Funkció | Régi hely | Tényleges működés | Fabric-átvétel |
|---|---|---|---|
| Hangár Kard + recept | `BlockCommanderTransmitter/.../items/HangarSword.kt` | Egyedi PDC-vel jelölt aranykard, 2 aranyrúd + bot recept | Regisztrált `blockommander:hangar_sword` item; a vanilla receptütközést egy, a bot mellé tett redstone por oldja fel. Nincs Bukkit/PDC. |
| Suhintás, kijelölés | `.../events/SelectionListener.kt` | Játékosonként A pont és anyag memóriában; B pont után adatbekérés és HTTP | `runtime/Interactions.kt`: AttackBlock/UseBlock Fabric callback, játékosonkénti kijelölés. |
| Adatbekérés | `SelectionListener.kt`, `ProgramBlockListener.kt` | Paper üllőinventory kétlépcsős kezelése | `client/screen/CkFormScreen.kt` és hitelesített C2S payload; külön hall- és blokkszerkesztő űrlap. |
| Hangárgeometria | `CKpu/src/hangar/builder.py`, Paper `HallRegistry.kt` | Normalizált doboz, üreg, ablak, tetőfény, ajtónyílás, kezelőszervek | A validáció és terv a Pythonban maradt; `runtime/WorldRenderer.kt` kizárólag a Fabric szerver főszálán ír világot. |
| Tulajdonos/fejlesztő | `001_halls.sql`, `repository.py` | `developers.player_uuid`, `halls.owner_uuid` | Változatlan DB-kapcsolat; minden mutáló Python végpont újra ellenőrzi a UUID-t. |
| DESTROY | `DestructionButtonListener.kt`, `HallService.destroy` | Koordináta alapján hall, owner-check, bontás, DB-törlés | Fabric gombinterakció → Python owner-check → válasz után főszálas bontás. |
| Mentés/visszaépítés | `stage.py`, `004_program_stages.sql` | Piszkos workspace, optimista verzió, atomikus snapshot | Változatlanul átvéve; a Fabric a visszakapott blokk-ID/hely/irány/adat alapján renderel. |
| Stage, START/STOP | `ProgramBlockListener.kt`, `stage.py` | Stabil stage/instance UUID, START/STOP adatok | Valódi Fabric blokkok, stabil ID-k, horizontális irány blokkállapotban; DB snapshot megmarad. |
| IF/FOR/FUN | nem volt kész | Nem létezett regisztrált programelemként | Regisztrált és lerakható alapblokkok; `005_ck_block_types.sql` adja a DB-típusokat. Végrehajtási szemantika szándékosan nincs. |
| Python webhook | `CKpu/src/ck_webhook.py` | Flask :5000 és közvetlen mcpi világírás | Új alapértelmezés: :5001, `fabric` world driver, nincs mcpi import/világírás. Opcionális legacy driver külön requirements fájllal. |

## CKParser

A CKParser külön Spring/JavaParser szolgáltatás. Egy megadott Java-fájlból osztály- és metódus-metaadatot ad vissza, de nincs Minecraft-, Stage-, PostgreSQL- vagy programvégrehajtási integrációja. A jelenlegi körben sem kódját, sem buildjét nem másoltuk át. Későbbi integráció előtt fájlútvonal-korlátozás és hitelesítés szükséges.

## Fontos határok

- A kliens nem ismeri a DB-hitelesítő adatokat és nem hívja a Pythont.
- A kliens csak űrlapadatot küld a Fabric szervernek; a szerver kontextust, hosszt és helyet ellenőriz, a Python pedig tulajdonost ellenőriz.
- A Python tranzakció és a Minecraft-világírás nem egyetlen elosztott tranzakció. Sikertelen világírás után a DB-ből történő újratöltés/rebuild a helyreállítási út.
