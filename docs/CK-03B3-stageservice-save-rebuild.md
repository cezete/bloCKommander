# CK-03/B3 – StageService graph-aware SAVE/REBUILD

- Dátum: 2026-10-23
- Hatókör: Python StageService és HallRepository integráció
- Kiinduló kapu: CK-03/B2-T GO, valódi PostgreSQL 18.2 integráció
- Eredmény: **GO a graph_snapshot_version=1 StageService SAVE/REBUILD útjára**

## 1. Kiinduló állapot és felderítés

A B1 után a StageWorkspace már egyetlen immutable StageSnapshot referenciát, külön `saved_version`, `dirty`, `workspace_epoch` és Stage-enkénti `RLock` mezőt tartott. A graphművelet owner-, epoch- és working-revision ellenőrzéssel, a Stage-lock alatt futott.

A B2/B2-T elkészítette és valódi PostgreSQL 18.2-n bizonyította a teljes snapshot repository API-t:

- `save_full_stage_snapshot()`;
- `load_full_stage_snapshot()`;
- persistent `saved_version` CAS;
- teljes blokk/edge/route/step/wire tranzakció;
- formátum- és portkatalógus-kompatibilitási envelope.

A B3 előtt a StageService SAVE még blokk-only repository API-t használt, graph tartalomnál leállt, a REBUILD pedig csak legacy blokkokat töltött vissza és nem futtatta újra a graph-validátort.

## 2. Módosított fájlok

- `python-service/src/hangar/stage.py`
- `python-service/tests/test_stage_service.py`
- `python-service/tests/test_stage_graph_service.py`
- `python-service/tests/test_stage_graph_persistence_service.py`
- `python-service/tests/integration/postgres/run-tests.ps1`
- `python-service/tests/integration/postgres/postgres_stage_service_integration.py`
- `docs/CK-03B3-stageservice-save-rebuild.md`

Kotlin, Fabric, HTTP, migráció és repository publikus API nem változott. Commit, push, deploy és üzemi DB-művelet nem történt.

A záró Git státusz:

```text
 M python-service/src/hangar/stage.py
 M python-service/tests/integration/postgres/run-tests.ps1
 M python-service/tests/test_stage_graph_service.py
 M python-service/tests/test_stage_service.py
?? docs/CK-03B3-stageservice-save-rebuild.md
?? python-service/tests/integration/postgres/postgres_stage_service_integration.py
?? python-service/tests/test_stage_graph_persistence_service.py
```

Az index és a korábbi B2/B2-T változások staging állapota nem lett átrendezve.

## 3. SAVE életciklus

A `StageService.save()` sorrendje:

1. workspace feloldása;
2. Stage `RLock` megszerzése;
3. owner ellenőrzése;
4. ha a payload hordozza, az epoch + working revision pár együttes ellenőrzése;
5. immutable snapshot és persistent saved version kiolvasása ugyanazon lock alatt;
6. version 1 Stage esetén `save_full_stage_snapshot()`;
7. version 0 Stage esetén csak graph nélküli `save_stage_snapshot()`;
8. kizárólag sikeres repository-return után `saved_version` frissítés és CLEAN állapot;
9. lock feloldása.

Bizonyított invariánsok:

- a SAVE nem cseréli le és nem módosítja a snapshotot;
- `working_revision` változatlan;
- `saved_version` pontosan +1;
- siker után CLEAN;
- DRAFT és BROKEN snapshot menthető;
- repository exception vagy `StageVersionConflict` után snapshot, revision, epoch, saved version és dirty változatlan;
- nincs automatikus retry vagy overwrite.

## 4. REBUILD életciklus

A `StageService.rebuild()` sorrendje:

1. Stage-lock;
2. owner ellenőrzése;
3. opcionális epoch + working revision ellenőrzése;
4. dirty workspace-nél kötelező `confirm_discard`;
5. `load_full_stage_snapshot()`;
6. teljes új StageWorkspace-jelölt lokális előkészítése;
7. új process-lokális working revision: előző revision +1;
8. programvalidáció visszaállítása `NOT_CHECKED` állapotra;
9. kompatibilis snapshotnál `validate_graph()` és friss ValidationReport;
10. új epoch létrehozása;
11. az előkészített mezők egyetlen lockolt állapotcseréje;
12. CLEAN állapot.

LOAD-, mapping- vagy validációs exception előtt a régi workspace egyik mezője sem változik. A részletes ValidationReport továbbra sem perzisztált adat.

## 5. Owner, lock, revision és CAS szerződés

- SAVE, REBUILD és graphművelet ugyanazt a Stage `RLock` objektumot használja.
- A repository SAVE teljes ideje alatt sem graphművelet, sem REBUILD nem módosíthatja vagy cserélheti a workspace-t.
- Az owner-check a meglévő `player_uuid == owner_uuid` alkalmazási szabályt tartja meg.
- Ez nem transport-szintű autentikáció; hamisítatlan játékosazonosítót a későbbi HTTP/Fabric rétegnek kell biztosítania.
- A kliensparancs azonossága az epoch + working revision pár. Ha a payload egyik mezőt hordozza, a másik is kötelező.
- Tartalomváltozás working revision +1; idempotens művelet 0; SAVE 0; REBUILD +1 és új epoch.
- Több process utolsó védelmi vonala a PostgreSQL `saved_version` CAS.

A valódi DB-tesztben külső kliens commitja után a StageService SAVE `StageVersionConflict` hibával állt le, és a lokális dirty workspace teljesen változatlan maradt.

## 6. Snapshot és ValidationReport kezelése

A StageService kezdeti betöltése és REBUILD-je a `LoadedStageSnapshot` teljes envelope-ját használja. A workspace eltárolja:

- `graph_snapshot_version`;
- `compatible_for_graph_mutation`;
- strukturált kompatibilitási okokat.

Kompatibilis version 1 snapshotnál a teljes blokk-, edge-, route- és wire-tartalom visszaáll, majd friss graph report készül. A report a rebuild working revisionjére vonatkozik. Blocking diagnosztikánál a `validated_revision` nem lesz aktuális; hibamentes graphnál az aktuális revision kerül rögzítésre.

A generikus validátor tiszta riportot ad, nem írja át automatikusan a perzisztált EdgeRoute státuszt. Ezért egy BROKEN route BROKEN marad; a riport friss állapotértékelést ad mellé.

## 7. Legacy és kompatibilitás

`graph_snapshot_version=0` esetén:

- a legacy blokkok és metaadataik betöltődnek;
- edge, route és wire nem kerül kitalálásra;
- blokk CRUD és blokk-only SAVE megmarad;
- graphművelet `SnapshotCompatibilityError` hibával tiltott;
- nincs csendes graph-formátum-upgrade.

Ismeretlen snapshot-verzió, ismeretlen portkatalógus-verzió, runtime-katalógus hiba vagy UNSPECIFIED portszemantika esetén a teljes tartalom read-only workspace-ként betölthető. A view `READ_ONLY_INCOMPATIBLE` állapotot és kompatibilitási okokat ad; graphművelet és graph-aware SAVE tiltott.

A version 0 → version 1 explicit upgrade alkalmazási API-ja még nincs definiálva. A B3 nem talált ki automatikus upgrade-et; ez külön SysOrg döntési és implementációs pont.

## 8. DRAFT és BROKEN kezelés

- SAVE nem futtat programvalidációt és nem állít VALID státuszt.
- REBUILD után a program státusza mindig `NOT_CHECKED`.
- BROKEN, edge nélküli route és hiányzó WireCellre mutató step veszteségmentesen visszatöltődik.
- A friss graph report jelezheti a hibát, de nem javítja és nem törli automatikusan a route-ot.
- DRAFT/BROKEN snapshot is menthető, ha a tárolási struktúra konzisztens.

## 9. Hibakezelés és memóriabeli rollback

SAVE esetén a workspace mezői csak a sikeres DB-commitot jelző repository-return után változnak. REBUILD esetén előbb egy teljes jelölt workspace készül, és csak utána történik a lockolt mezőcsere.

Unit tesztek bizonyítják:

- repository SAVE exception után teljes változatlanság;
- CAS conflict után teljes változatlanság;
- LOAD exception után snapshot, stage header, revision, epoch, saved version, dirty és kompatibilitási mezők változatlansága;
- dirty REBUILD explicit megerősítés nélkül nem olvas felül;
- jogosulatlan REBUILD nem változtat állapotot;
- stale epoch vagy revision SAVE/REBUILD előtt elutasítódik.

## 10. Unit és regressziós eredmények

| Tesztcsoport | Eredmény |
|---|---:|
| Új B3 StageService perzisztencia/lock tesztek | 9/9 PASS |
| Stage-kezdetű céltesztek összesen | 43/43 PASS |
| CK-03/B2 repository unit/statikus | 27/27 PASS |
| CK-02 graph-regresszió | 270/270 PASS |
| Teljes Python-regresszió | 355/355 PASS |

Az új service tesztek fedik a graph-aware SAVE-et, DRAFT/BROKEN mentést, working/saved verziót, rollbacket, CAS-t, stale parancsot, owner-checket, SAVE↔graph és SAVE↔REBUILD lock-sorrendet, legacy/incompatible read-only módot, report újraszámítást és atomi sikertelen REBUILD-et.

## 11. Valódi PostgreSQL eredmények

Az elkülönített B2-T PostgreSQL 18.2 környezet változatlan fail-closed identitáskapujával futott:

| Tesztcsoport | Eredmény |
|---|---:|
| Meglévő B2-T repository/migráció/trigger/CAS/rollback | 13/13 PASS |
| Új StageService → HallRepository → PostgreSQL → StageService | 2/2 PASS |

Az end-to-end teszt START/STOP blokkokat, CONTROL edge-et, kötött és BROKEN route-ot, route stepeket, SINGLE/CROSS wire cellákat, stabil UUID-kat, SAVE-et, dirty discardos REBUILD-et, új epochot, CLEAN állapotot és friss reportot ellenőrzött.

A második teszt valódi PostgreSQL CAS-konfliktust idézett elő külső commit segítségével. Az adatbázisban az external winner maradt, a StageService lokális loser workspace-e pedig dirty és változatlan maradt.

## 12. Nyitott kockázatok

- Nincs explicit legacy graph-format upgrade API.
- A Stage-lock process-lokális; több Python process ellen csak a DB CAS véd.
- A payload `player_uuid` owner-check nem teljes autentikációs lánc.
- A SAVE/REBUILD HTTP DTO jelenleg nem követeli meg kötelezően az epoch/revision mezőket; ha jelen vannak, a service ellenőrzi őket. Kötelező transport-szerződés későbbi feladat.
- A runtime UNSPECIFIED portszemantikájú blokk read-only graph módot eredményez; portkatalógus-tervezés nélkül nem oldható fel.
- Minecraft-világ visszaépítése, Fabric megjelenítés és HTTP graph endpoint nem része a B3-nak.

## 13. GO/NO-GO javaslat

**CK-03/B3: GO szakmai review-ra.**

A version 1 teljes StageSnapshot StageService-en keresztül atomikusan menthető, PostgreSQL-ből veszteségmentesen újraépíthető, és a revision/epoch/CAS/lock védelem unit és valódi DB tesztekkel bizonyított. DRAFT/BROKEN és legacy adatok megmaradnak, inkompatibilis tartalom read-only.

**NO-GO automatikus CK-03/B4, HTTP/Fabric integráció vagy üzemi migráció indítására.** Az explicit legacy upgrade és a transport-szintű epoch/auth szerződés külön döntést igényel.

## Rövid összefoglaló

A StageService most már a teljes gráfot menti és tölti vissza, nem csak a blokkokat. SAVE közben ugyanaz a Stage-lock védi a snapshotot a graphművelettől és a REBUILD-től; DB-hiba vagy CAS-konfliktus nem hagy részleges memóriabeli állapotot. REBUILD új epochot, friss graph reportot és CLEAN workspace-t ad, de nem teszi automatikusan VALID-dá a programot és nem javítja át a BROKEN route-ot. A teljes út valódi PostgreSQL-en is PASS.
