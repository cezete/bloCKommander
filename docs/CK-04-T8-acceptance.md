# CK-04/T8 – Authoritative Stage reconciliation acceptance-jegyzőkönyv

## 1. Cél és eredmény

A CK-04/T8 célja annak biztosítása volt, hogy Minecraft JOIN/reconnect után a Fabric Stage-, Graph- és világprojekciója a Python szolgáltatás hiteles állapotával egyezzen, beleértve a programblokkokat, a CK Wire megjelenését és a SAVE-tábla feliratát.

**Végső döntés: CK-04/T8 ACCEPTED.**

Az elfogadás alapja három, egymástól elkülönített bizonyítéktípus:

- **Automatizált teszt:** lokális Kotlin/JVM- és Python-tesztcsomag, valamint teljes build.
- **Szerveroldali ellenőrzés:** read-only health-, PID-, PostgreSQL- és Graph GET-vizsgálatok.
- **Játékbeli megfigyelés:** a projektgazda Minecraftban végzett T8/1 és T8/2 acceptance-ellenőrzése.

## 2. A T7-ben feltárt eredeti hiba

Python webhook-restart után a Minecraft reconnect nem állította helyre az aktuális Python Stage-állapotot:

- a `Homok a szélben` csarnokban a Pythonból már eltűnt négy mentetlen START továbbra is látható maradt;
- a mentett és Python szerint clean `Mohikán` csarnok SAVE-táblája `NINCS MENTVE` állapotot mutatott;
- a két másik csarnok látszólag helyes maradt, mert a Fabricben megmaradt régi projekciójuk történetesen megegyezett a mentett állapottal.

### Gyökérok

1. Minecraft JOIN-kor a Fabric kizárólag a `/api/v1/halls/restore` útvonalat használta. Ez az API csak az eltérő world UUID-ról helyreállítandó csarnokokat adja vissza, ezért az aktuális világ csarnokai nem kaptak authoritative Stage-reloadot.
2. A Fabric a memóriában maradt, régi epochú Stage-ekkel indította a Graph reconciliationt.
3. A Python új epochú Graph GET-válaszát a `GraphProjectionStore` helyesen `EPOCH_CONFLICT` miatt elutasította, de emiatt Stage- és Wire-projekció sem történt.
4. A programblokkok a szervervilág korábbi állapotában maradtak, mert reconnectkor nem futott hiteles Stage-render.
5. A SAVE-tábla frissítése a Graph snapshot `dirty` értékét akkor is előnyben részesítette, ha a snapshot régi, `STALE` vagy `CONFLICT` lifecycle-hoz tartozott. Így a Mohikán clean Stage-e fölé egy korábbi dirty Graph snapshot került.

A két jelenség közös oka tehát a reconnect előtti authoritative Stage-lifecycle váltás hiánya volt; ehhez társult a SAVE-felirat túl megengedő Graph-snapshot prioritása.

## 3. A T8-ban megvalósított javítás

### Esemény- és adatútvonal

Az új reconnect-folyamat:

```text
Minecraft JOIN
  → meglévő POST /api/v1/halls/restore
  → kéréskori Hall/Stage/epoch/revision fence-ek rögzítése
  → authoritative GET /api/v1/worlds/{worldUuid}/halls
  → Hall- és Stage-azonosság, epoch és revision ellenőrzése
  → authoritative Stage-lifecycle reload
  → programblokkok világprojekciója
  → Graph GET reconciliation
  → CK Wire-projekció
  → lifecycle-biztos SAVE-tábla frissítés
```

A `/halls/restore` world UUID-alapú szemantikája nem változott.

### Érintett komponensek

- `runtime/Interactions.kt`
  - JOIN utáni authoritative current-world Stage GET;
  - Stage → Graph/Wire → SAVE feldolgozási sorrend;
  - részleges hibák diagnosztikai naplózása.
- `runtime/HallRegistry.kt`
  - kéréskori `StageUpdateFence`;
  - Hall/Stage-azonosság ellenőrzése;
  - szabályos epochváltás;
  - elavult revision és késői válasz elutasítása;
  - lifecycle-biztos SAVE `dirty` feloldás.
- `runtime/StageReconnectReconciler.kt`
  - a reconnect projekciós lépéseinek determinisztikus, tesztelhető koordinációja;
  - elutasított Stage-válasz után a destruktív világprojekció tiltása.
- `runtime/graph/GraphProjectionStore.kt`
  - reconnectkor új projekciós lifecycle indítása azonos epoch mellett is;
  - korábbi snapshot és folyamatban lévő régi Graph-kérések leválasztása;
  - a SAVE utáni azonos revisionű clean Graph GET alkalmazhatósága.
- `runtime/WorldRenderer.kt`
  - a csarnokstruktúra, a programblokkok és a SAVE-tábla projekciójának szétválasztása;
  - authoritative üres Stage esetén a stale CK programblokkok eltávolítása.
- `runtime/ApiClient.kt`
  - transport-, HTTP- és protokollhibák diagnosztizálható kezelése.

A reconciliation nem ad ki SAVE, REBUILD, BIND_EDGES vagy WIRE_LAYOUT domain-műveletet, és nem módosít Python revision vagy dirty állapotot.

## 4. Automatizált tesztek

**Bizonyítéktípus: automatizált lokális teszt.**

| Tesztkör | Eredmény |
| --- | --- |
| Kotlin/JVM | **74 PASS, 0 failure, 0 error** |
| Python | **387 PASS** |
| `cleanTest buildAll` | **BUILD SUCCESSFUL** |

A regressziós tesztek lefedték:

- régi dirty, négy START-ot tartalmazó Stage → új epochú üres clean Stage;
- mentett START–Wire–STOP Stage új epoch alatti visszavetítését;
- azonos tartalmú Stage idempotens újraprojekcióját;
- késve érkező régi epochú és elavult válaszok fencingjét;
- azonos epochos régi dirty Graph snapshot leválasztását;
- Stage GET- és Graph GET-hibák fail-safe viselkedését;
- VALID EdgeRoute zöld CK Wire-vetítését.

## 5. T8/1 játékbeli acceptance

**Bizonyítéktípus: projektgazdai játékbeli megfigyelés.**

A T8 szerver-JAR telepítése után mind a négy csarnok világprojekciója és SAVE-táblája megfelelt az elvárt állapotnak.

| Csarnok | Elvárt állapot | Tényleges állapot | Eredmény |
| --- | --- | --- | --- |
| MohaÉsPáfrány | START – zöld CK Wire – STOP; `MENTVE` | START – zöld CK Wire – STOP; `MENTVE` | **PASS** |
| Homokbucka | Üres; `MENTVE` | Üres; `MENTVE` | **PASS** |
| Mohikán | START – zöld CK Wire – STOP; `MENTVE` | START – zöld CK Wire – STOP; `MENTVE` | **PASS** |
| Homok a szélben | Üres; `MENTVE` | Üres; `MENTVE` | **PASS** |

## 6. T8/2 – élő epochváltás Fabric-restart nélkül

### Szerveroldali előellenőrzés

**Bizonyítéktípus: szerveroldali read-only ellenőrzés.**

A kontrollált restart előtt:

- a Python webhook, a Fabric szerver és a PostgreSQL elérhető volt;
- mind a négy Stage `dirty=false`, `save=CLEAN`, `working_revision=0` állapotú volt;
- a Graph GET és a PostgreSQL mentett darabszámai egyeztek;
- a stabil blokk-, GraphEdge- és EdgeRoute-ID-k rögzítésre kerültek.

### Kontrollált restart és szerveroldali eredmény

Kizárólag a Python webhook indult újra:

| Ellenőrzés | Restart előtt | Restart után |
| --- | ---: | ---: |
| Python PID | `3703654` | `3906690` |
| Webhook példányok száma | 1 | 1 |
| Fabric PID | `3900974` | `3900974` |
| Fabric port | LISTENING | LISTENING |
| Python `/health` | OK | OK |
| PostgreSQL read-only ellenőrzés | OK | OK |

Mind a négy Stage új workspace epochot kapott:

| Csarnok | Régi epoch | Új epoch | Revision | Saved version | Tartalom |
| --- | --- | --- | ---: | ---: | --- |
| Homok a szélben | `731983b9-f701-4fbb-9dbb-dd5be0d33e84` | `97b0ea0f-301d-4d24-8018-4a658bc879c7` | 0 | 0 | üres |
| Homokbucka | `1b509149-fd09-4c64-a734-22380471e22b` | `31b7a48f-67c9-4e0b-99a2-0de8af0aa883` | 0 | 0 | üres |
| MohaÉsPáfrány | `bdb4d515-8536-4c56-afb6-3f9c29804ce9` | `76ddf1c2-b50b-4a52-b640-a5aeca0dc489` | 0 | 8 | 2 blokk, 1 edge, 1 route, 1 wire |
| Mohikán | `711d2e61-f1b8-4b04-ba52-091e22ca6d45` | `0e71f9cf-0edf-46fe-a88b-6b1b1113492c` | 0 | 1 | 2 blokk, 1 edge, 1 route, 1 wire |

Az előtte és utána számított teljes PostgreSQL- és Graph API-tartalom-ujjlenyomatok megegyeztek. A stabil blokk-, GraphEdge- és EdgeRoute-ID-k változatlanok maradtak. Nem keletkezett új GraphEdge, EdgeRoute, WireCell vagy más domain-objektum.

### Minecraft reconnect eredménye

**Bizonyítéktípus: projektgazdai játékbeli megfigyelés.**

A játékos ugyanarra, végig futó Fabric szerverre újracsatlakozott. A reconnect után:

| Csarnok | Elvárt állapot | Tényleges állapot | Eredmény |
| --- | --- | --- | --- |
| MohaÉsPáfrány | START – zöld CK Wire – STOP; `MENTVE` | START – zöld CK Wire – STOP; `MENTVE` | **PASS** |
| Homokbucka | Üres; `MENTVE` | Üres; `MENTVE` | **PASS** |
| Mohikán | START – zöld CK Wire – STOP; `MENTVE` | START – zöld CK Wire – STOP; `MENTVE` | **PASS** |
| Homok a szélben | Üres; `MENTVE` | Üres; `MENTVE` | **PASS** |

Ez igazolja, hogy a Fabric restartja nélkül bekövetkező Python epochváltást a reconnect authoritative Stage-reloadja elfogadta, majd a Stage-, Graph-, Wire- és SAVE-projekció helyreállt.

## 7. Megmaradó kockázatok és elmaradt vizsgálat

- Graph GET-hiba esetén a rendszer szándékosan nem törli a meglévő CK Wire-vetítést bizonytalan állapot alapján. Egy korábbi Wire ezért átmenetileg látható maradhat a következő sikeres reconciliationig. A hiba diagnosztikai naplóbejegyzést kap.
- A fencing és a késői válaszok kezelése automatizált tesztekkel igazolt, de nagy terhelésű, több játékos egyidejű reconnectjére külön acceptance nem történt.
- A T8/2 utáni külön T4 Fabric reconnect-naplóelemzés nem készült el. Emiatt az internal log-sorrend nincs önálló naplóartefaktummal bizonyítva. A szerveroldali állapot-összehasonlítás és a játékbeli végeredmény ugyanakkor PASS.
- A hibatűrési ágak automatizált tesztekkel fedettek; élő Stage GET- vagy Graph GET-kieséses acceptance nem történt.

## 8. Végső döntés

Az automatizált tesztek, a kontrollált szerveroldali epochváltás, a változatlan mentett tartalom és a két játékbeli acceptance alapján:

> **CK-04/T8 ACCEPTED**
