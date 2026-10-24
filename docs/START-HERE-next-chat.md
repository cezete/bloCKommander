# bloCKommander – dobbantó a következő csevegéshez

> Frissítve: 2026-10-24  
> Projektgyökér: `C:\Users\tamas\IdeaProjects\bloCKommanderFabric`  
> Ág: `main`  
> Kiinduló commit: `2db713c CK-03/B3 – StageService graph-aware SAVE/REBUILD`

## 1. Mit építünk?

A bloCKommander Minecraft/Fabric alapú, fizikailag szerkeszthető szoftvermodellező környezet. A Minecraft a térbeli szerkesztő és megjelenítő; a PostgreSQL a hiteles szemantikus állapot.

A jelenlegi vertical slice célja egy kézzel épített, portorientált vezérlési gráf felismerése, validálása, teljes mentése és veszteségmentes visszatöltése stabil blokk-, edge- és route-azonosítókkal.

## 2. Kötelező első olvasmányok

Ebben a sorrendben olvasd el:

1. `AGENTS.md`
2. `koncepcio.md`
3. `docs/CK-03B1-stage-snapshot-integracio.md`
4. `docs/CK-03B2-postgresql-graph-perzisztencia.md`
5. `docs/CK-03B2T-postgresql-integracios-kapu.md`
6. `docs/CK-03B3-stageservice-save-rebuild.md`

Fontos: a B2 dokumentum még a valódi PostgreSQL-kapu előtti BLOCKED állapotot rögzíti. A frissebb B2-T és B3 dokumentumok ezt felülírják: a repository és StageService teljes graph-perzisztenciája valódi PostgreSQL 18.2-n bizonyított.

## 3. Jelenlegi kész állapot

### Graph domain és alkalmazási réteg

- Immutable `StageSnapshot`, `GraphWorkspaceState`, GraphEdge, EdgeRoute, WireCell és diagnosztikai modell elkészült.
- Portkatalógus verziója: `ck-control-v1`.
- START, STOP és IF portjai definiáltak; FOR és FUN szándékosan `UNSPECIFIED`.
- Graph binding, validáció, kétlépcsős kapcsolatbontás és copy-on-write állapotátmenetek működnek.
- A `StageService.apply_graph_operation()` owner-, epoch- és working-revision ellenőrzött, Stage-lockolt alkalmazási határ.

### PostgreSQL-perzisztencia

- Migráció: `python-service/src/hangar/migrations/006_graph_snapshots.sql`.
- Táblák: `graph_edges`, `edge_routes`, `route_steps`, `wire_cells`, `wire_channels`.
- A GraphNode hiteles tárolója továbbra is a `stage_blocks`; külön `graph_nodes` tábla nincs.
- Deferred constraint triggerek garantálják a SINGLE/CROSS csatornakardinalitást.
- `HallRepository.save_full_stage_snapshot()` egy tranzakcióban menti a teljes snapshotot persistent CAS mellett.
- `HallRepository.load_full_stage_snapshot()` teljes immutable snapshotot és kompatibilitási envelope-ot ad.

### StageService SAVE/REBUILD

- Graph-aware SAVE a teljes snapshotot menti; working revisiont nem növel.
- Sikeres SAVE után `saved_version +1` és CLEAN.
- Hiba vagy CAS-konfliktus után a teljes memóriabeli workspace változatlan.
- REBUILD teljes snapshotot tölt vissza, új epochot hoz létre, újraszámítja a graph reportot és CLEAN állapotot ad.
- A programvalidáció REBUILD után `NOT_CHECKED`; BROKEN route nem javul automatikusan.
- Version 0 legacy Stage blokk-only marad; graphművelet explicit upgrade előtt tiltott.
- Ismeretlen snapshot-/portkatalógus-verzió read-only workspace-ként betölthető.

## 4. Bizonyított tesztkapuk

Utolsó teljes futás: 2026-10-23.

| Kapu | Eredmény |
|---|---:|
| Új CK-03/B3 StageService tesztek | 9/9 PASS |
| CK-03/B2 repository unit/statikus | 27/27 PASS |
| CK-02 graph-regresszió | 270/270 PASS |
| Teljes Python-regresszió | 355/355 PASS |
| Valódi B2-T PostgreSQL-integráció | 13/13 PASS |
| Valódi StageService→PostgreSQL end-to-end/CAS | 2/2 PASS |

A valódi DB-tesztek lefedik a fresh és 005→006 migrációt, deferred trigger-mátrixot, teljes round-tripet, kétconnectionös CAS-t, hét rollback-pontot, StageService SAVE/REBUILD-et és a StageService-re visszaható valós CAS-konfliktust.

## 5. Eldobható PostgreSQL-próbatér

Helye: `python-service/tests/integration/postgres/`.

- Compose project: `blockcommander-ck03-b2t`
- Container: `blockcommander-ck03-b2t-postgres`
- DB/user: `blockcommander_b2t`
- Nagypapi loopback port: `127.0.0.1:5437`
- Helyi SSH tunnel port: `127.0.0.1:55437`
- Image: digestre rögzített PostgreSQL 18.2
- Nincs automatikus restart és nincs publikus hálózati binding.

Parancsok a projektgyökérből:

```powershell
& .\python-service\tests\integration\postgres\start.ps1
& .\python-service\tests\integration\postgres\readiness.ps1
& .\python-service\tests\integration\postgres\run-tests.ps1
& .\python-service\tests\integration\postgres\stop.ps1
```

Destruktív cleanup csak külön emberi jóváhagyással:

```powershell
& .\python-service\tests\integration\postgres\cleanup.ps1 -ConfirmCleanup
```

A cleanup előtt a script teljes container ID-t, Compose projectet, labeleket, volume-ot, networköt és portkötést ellenőriz. Globális prune tilos.

## 6. Biztonsági korlátok

- Az üzemi `bloCKommander` adatbázishoz teszttel vagy migrációval kapcsolódni tilos.
- A Nagypapin futó meglévő konténereket, volume-okat és hálózatokat módosítani tilos.
- Nincs automatikus commit, push, deploy vagy üzemi migráció.
- Minecraft-világ módosítása, szerverrestart és szolgáltatásleállítás csak külön felhatalmazással.
- A `player_uuid == owner_uuid` StageService-ellenőrzés alkalmazási jogosultság, nem teljes transport-szintű autentikáció.
- A Stage-lock process-lokális; több Python process között a PostgreSQL CAS az utolsó védelmi vonal.
- A Git indexet/worktree-t munka előtt olvasd fel, és a felhasználó változásait ne rendezd át automatikusan.

## 7. Fontos forrásfájlok

- `python-service/src/hangar/stage.py` – StageWorkspace, SAVE, REBUILD, graph-operation koordináció
- `python-service/src/hangar/repository.py` – PostgreSQL repository és CAS
- `python-service/src/hangar/graph/snapshot.py` – teljes immutable StageSnapshot
- `python-service/src/hangar/graph/adapter.py` – StageSnapshot ↔ GraphWorkspaceState
- `python-service/src/hangar/graph/state.py` – revision/save/validation állapotgép
- `python-service/src/hangar/graph/validation.py` – generikus graph-validátor
- `python-service/src/hangar/graph/port_catalog.py` – verziózott portkatalógus
- `python-service/tests/test_stage_graph_persistence_service.py` – B3 service invariánsok
- `python-service/tests/integration/postgres/postgres_stage_service_integration.py` – valódi DB end-to-end/CAS

## 8. Nyitott döntési pontok

1. **Legacy upgrade:** nincs explicit version 0 → version 1 graph-formátum upgrade API. Ne vezess be csendes upgrade-et.
2. **Transport-szerződés:** az epoch és working revision service-szinten ellenőrizhető, de a HTTP/Fabric DTO még nem teszi kötelezővé.
3. **Autentikáció:** a payloadból érkező player UUID hiteles átadási lánca még nincs megoldva.
4. **Következő fázis:** CK-03/B4 vagy későbbi HTTP/Fabric integráció csak külön specifikáció alapján kezdhető.
5. **Portkatalógus:** FOR és FUN graphművelete read-only marad, amíg a portszerződés nincs meghatározva.

## 9. Ajánlott kezdőprompt az új csevegéshez

```text
A bloCKommanderFabric projekten dolgozunk. Először olvasd el az AGENTS.md fájlt és a docs/START-HERE-next-chat.md dobbantót, majd ellenőrizd read-only módon a Git státuszt. A B2-T és B3 kapuk GO állapotúak; ne ismételd meg a már lezárt munkát. Ne commitolj, pusholj, deployolj és ne érints üzemi adatbázist külön engedély nélkül. Ezután a következő csatolt feladatspecifikáció szerint dolgozz, és őrizd meg a meglévő worktree/index állapotot.
```

## 10. Indulási ellenőrzőlista az új ágensnek

- [ ] `AGENTS.md` és ez a dobbantó teljesen elolvasva
- [ ] `git status --short` és aktuális commit felmérve
- [ ] A következő feladat határa tisztázva
- [ ] Produkciós DB/szerver művelet nincs implicit módon engedélyezve
- [ ] A régi B2 BLOCKED státuszt nem tekinti aktuálisnak
- [ ] B2-T/B3 eredményt csak szükség esetén és izolált környezetben futtatja újra
- [ ] A következő fázist nem kezdi el automatikusan
