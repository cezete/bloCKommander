# CK-05/A2 – Python fizikai topológiafelderítő

## 1. Eredmény

**PASS**

Elkészült az önálló, I/O-mentes és determinisztikus Python fizikai topológiafelderítő. A modul a meglévő `GraphWorkspaceState`, `GraphNode`, `WireCell`, `WireChannel`, `PortDefinition`, `PortRef` és `WireStep` típusokra épül. Nem vezet be párhuzamos gráfmodellt, nem perzisztál, és nem módosítja a bemeneti workspace-t.

Az implementáció nem érinti a Fabricet, HTTP API-t, `WIRE_LAYOUT`-ot, Stage-wide bind integrációt, Junctiont, PUBLISH-t, színmodellt vagy adatbázist.

## 2. Módosított fájlok

- `python-service/src/hangar/graph/discovery.py` – új tiszta discovery domainmodul.
- `python-service/src/hangar/graph/__init__.py` – a publikus discovery interfész exportja.
- `python-service/tests/test_graph_discovery.py` – 12 izolált unit teszt.
- `docs/CK-05A2-python-discovery.md` – ez a jelentés.

A meglévő `binding.py` és `destruction.py` változatlan maradt.

## 3. Publikus interfész

A fő belépési pont:

```python
discover_physical_topology(
    state: GraphWorkspaceState,
    port_catalog: Mapping[str, tuple[PortDefinition, ...]],
) -> PhysicalTopologyDiscovery
```

A visszatérési érték immutable objektumokat tartalmaz:

- `PhysicalWireComponent`
  - determinisztikus `component_id`;
  - `PhysicalComponentStatus`;
  - a komponens `(GridPos, WireChannel)` csúcsai `WireStep` formában;
  - csatlakozó `PortRef` értékek;
  - rendezett `OpenWireEnd` értékek.
- `DiscoveredConnection`
  - determinisztikus `candidate_id`;
  - forrás- és célport;
  - forrástól célig rendezett `WireStep`-sorozat;
  - `as_route_candidate()` adapter a meglévő, tiszta `RouteCandidate` értékhez.
- `DiscoveryDiagnostic`
  - strukturált kód;
  - komponensazonosító;
  - érintett portok és vezetéki pozíciók.
- `PhysicalTopologyDiscovery`
  - determinisztikusan rendezett komponensek, jelöltek és diagnosztikák.

A komponens- és jelöltazonosítók névtér-alapú UUID-k. Azonos kanonikus fizikai bemenethez azonos azonosító és sorrend tartozik; nincs folyamatfüggő Python `hash()` használat.

## 4. Fizikai gráf és felismerés

A fizikai gráf csúcsa `(GridPos, WireChannel)`. Két csúcs között csak akkor jön létre nem irányított kapcsolat, ha:

1. vízszintesen szomszédosak;
2. az első csatorna a második felé mutat;
3. a második csatorna kölcsönösen visszafelé mutat.

A programblokkport a meglévő orientációs függvényekkel kapcsolódik a port előtti cella megfelelő csatornájához. A port iránya és típusa a portkatalógusból származik. Beköthető jelölt csak pontosan egy kompatibilis `OUT` → `IN` portpárból készül.

A bejárás rendezett BFS-t, explicit visited halmazt és stabil irány-/csatornasorrendet használ. Emiatt ciklusban is véges és ismételhető.

### Támogatott topológiák

- többcellás vízszintes egyenes;
- `SINGLE` csatornákból felépített sarok;
- független `NS` és `EW` CROSS-csatornák;
- több, egymástól független fizikai komponens;
- nyitott/félbehagyott vezeték;
- inkompatibilis portpár;
- többértelmű port/topológia;
- duplikált cella/csatorna miatti illegális topológia;
- ciklikus fizikai komponens.

A `WireChannelState` meglévő konstruktora továbbra is a discovery előtt elutasítja a három- vagy négyirányú `SINGLE` csatornát. Ez a domain-invariáns nem lett fellazítva.

## 5. Státuszok és diagnosztikák

| Komponensstátusz | Diagnosztika | Jelölt készül? |
| --- | --- | --- |
| `COMPLETE` | nincs | igen |
| `INCOMPLETE` | `OPEN_WIRE` | nem |
| `INCOMPATIBLE` | `INCOMPATIBLE_PORTS` | nem |
| `AMBIGUOUS` | `AMBIGUOUS_TOPOLOGY` | nem |
| `ILLEGAL` | `ILLEGAL_TOPOLOGY` | nem |
| `CYCLIC` | `CYCLIC_COMPONENT` | nem |

A ciklus felismerése nem programszintű cycle validation. Kizárólag a fizikai WireCell-csatornahálózat ciklusát jelzi.

## 6. Szemantikai határ

A `discover_physical_topology()` kizárólag olvas és számol. Tesztelt invariánsok:

- nem hoz létre és nem töröl `GraphEdge`-et;
- nem módosít `EdgeRoute`-ot vagy route státuszt;
- nem állít automatikus `BROKEN` állapotot;
- nem módosítja a `dirty`, `working_revision`, `saved_version` vagy validációs mezőket;
- nem hívja a bind vagy destruction műveleteket;
- nem végez SAVE, PUBLISH, adatbázis-, hálózati vagy világműveletet.

Az `as_route_candidate()` csak immutable értéket képez a későbbi A3 integráció számára; nem hajt végre Élbekötést.

## 7. Teszteredmények

Futtatási környezet: a repository saját `python-service/.venv` környezete, `PYTHONPATH=src;tests`. PostgreSQL, HTTP-szolgáltatás és Minecraft nem kellett.

### Célzott A2 tesztek

```text
python -m unittest -q test_graph_discovery
Ran 12 tests
OK
```

Lefedett esetek:

1. többcellás egyenes START → STOP;
2. sarok START → STOP;
3. két független útvonal CROSS cellán;
4. félbehagyott, nyitott vezeték;
5. két inkompatibilis OUT port;
6. háromirányú `SINGLE` domain-elutasítása;
7. ciklikus komponens és véges futás;
8. ismételt discovery azonos eredménnyel/azonosítókkal/sorrenddel;
9. meglévő GraphEdge/EdgeRoute és workspace-metaadatok változatlansága;
10. immutable eredményobjektumok;
11. háromportos többértelmű komponens;
12. duplikált wire pozíció illegális diagnosztikája.

### Graph regresszió

```text
python -m unittest discover -s tests -p "test_graph_*.py" -q
Ran 313 tests
OK
```

### Teljes Python unit suite

```text
python -m unittest discover -s tests -q
Ran 399 tests
OK
```

`git diff --check` nem jelzett whitespace-hibát. Gradle-t nem futtattunk, mert az A2 kizárólag Python domainváltozás, nincs deploy vagy Fabric-módosítás.

## 8. Ismert korlátok

- A discovery minden híváskor a teljes Stage fizikai hálózatát számolja újra; inkrementális érintettkomponens-optimalizáció nincs.
- CK Junction nincs, és érvényes `SINGLE` csatorna nem ágazhat el.
- A ciklikus komponens szándékosan nem ad bind-jelöltet, még akkor sem, ha portok is kapcsolódnak hozzá.
- Ismeretlen vagy a katalógusban `UNSPECIFIED` blokktípus portjai kimaradnak; külön katalógusdiagnosztika továbbra is a meglévő kompatibilitási réteg felelőssége.
- A discovery strukturált eredménye még nem része HTTP DTO-nak vagy Graph GET válasznak.
- Nincs Stage-wide `BIND_EDGES` integráció.
- Nincs PUBLISH/live fizikai státusz vagy színprojekció.
- Az azonosító stabil a kanonikus fizikai tartalomhoz, de nem perzisztált domainidentitás.

## 9. CK-05/A3 integrációs szerződés

Az A3 a következő határon integrálhat biztonságosan:

1. Mindig az aktuális, epoch/revision-fencelt `GraphWorkspaceState` legyen a discovery bemenete.
2. `WIRE_LAYOUT` után a discovery újraszámolható, de eredménye önmagában nem módosíthat edge-et vagy route-ot.
3. A read-only Graph/API válasz a komponenseket, státuszokat és diagnosztikákat DTO-vá alakíthatja; a domainobjektumok ne függjenek HTTP-től.
4. Az explicit Stage-szintű Élbekötés kizárólag a `result.candidates` elemeit alakítsa `RouteCandidate` értékekké, majd egyetlen atomi `bind_edges()` hívást végezzen.
5. `INCOMPLETE`, `INCOMPATIBLE`, `AMBIGUOUS`, `ILLEGAL` és `CYCLIC` komponens nem adható át bind-jelöltként.
6. Stale epoch/revision esetén sem discovery-eredmény, sem bind nem vetíthető vissza.
7. Az A3 külön tesztelje, hogy automatikus layout/discovery soha nem hoz létre vagy töröl GraphEdge-et.
8. A Fabric és a színmodell továbbra is A3-on kívüli mérföldkő maradjon.

## 10. Következő lépés

Javasolt következő mérföldkő: **CK-05/A3 – discovery DTO és Stage-szintű, többjelöltes explicit bind alkalmazási integráció**, külön projektgazdai GO után.

Az A2 eredménye önálló, tesztelt és nincs automatikusan bekötve futó szolgáltatásba.
