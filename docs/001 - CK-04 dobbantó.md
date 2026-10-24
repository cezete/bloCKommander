# bloCKommander – CK-04 dobbantó

**Dátum:** 2026-10-24  
**Átmenet:** CK-03/B3 → CK-04/A Fabric-integrációs felderítés → CK-04/B első működő CK Wire  
**Állapot:** CK-03/B3 dokumentált GO; CK-04 még nem kezdődött el.

## 1. Miért váltunk irányt?

A Python–PostgreSQL lánc részletes, szemenkénti kézi vizsgálatát egyelőre jegeljük. A következő értelmes demonstráció már **Minecraftból induljon**. A CK-04 célja a játékos által látható, működtethető START → CK Wire → STOP kapcsolat. Az első demonstrációhoz **nem kell SAVE vagy REBUILD**.

## 2. Bizonyított kiinduló állapot

- CK-02: immutable Graph domain, GraphNode / GraphEdge / EdgeRoute / WireCell, manuális wire és explicit Élbekötés szemantika; graph-regresszió **270/270 PASS**.
- CK-03/B2 és B2-T: teljes graph-aware StageSnapshot PostgreSQL-perzisztencia; fresh és 005→006 migráció, deferred wire-integritás, kétkapcsolatos CAS és hét rollback-pont. Valódi PostgreSQL-integráció **13/13 PASS**.
- CK-03/B3: `StageService.save()` teljes snapshotot ment, `StageService.rebuild()` visszatölti és újravalidálja. Stage-lock, epoch/revision, persistent CAS, DRAFT/BROKEN, legacy read-only/blocks-only szerződések. Új B3 service **9/9 PASS**, teljes Python **355/355 PASS**, új StageService PostgreSQL end-to-end/CAS **2/2 PASS**.
- CK-03/B3 dokumentáció: `docs/CK-03B3-stageservice-save-rebuild.md`; implementáció: `python-service/src/hangar/stage.py`.
- B2-T elkülönített PostgreSQL-konténer a jelentés idején **running/healthy**; kizárólag kifejezett jóváhagyással bontható le. Üzemi adatbázis nem módosult.
- B2/B2-T/B3 munkafa és Git index részben staged, részben unstaged lehet; **nincs automatikus git add, commit, push vagy deploy**.

Ezek a korábbi Öcsi-jelentések szerinti eredmények; a Fabric runtime készültsége **még felderítendő**, nem tekinthető bizonyítottnak.

## 3. A CK-04 teljes mérföldkő terve

1. **Fabric ↔ Python összekötés:** Minecraft-események a meglévő Python Stage/Graph domainhez jutnak; Kotlinban nincs második gráfmotor.
2. **CK Wire lerakás:** a játékos két CK-blokk portjai között fizikailag lefekteti a vezetéket.
3. **Élbekötés:** hangárfali gomb indítja a Stage-szintű topológiaellenőrzést; siker esetén GraphEdge jön létre a munkapéldányban, hibánál diagnosztika és visszajelzés.
4. **SAVE → PostgreSQL:** a CK-03/B3 meglévő szerződését használja; a munkapéldány/publikáció különválasztása későbbi feladat.
5. **REBUILD → Minecraft:** a mentett blokkok és vezetékek újra megjelennek a világban.

**Az első kézzelfogható cél csak az 1–3. pont minimális megvalósítása.** A SAVE/REBUILD későbbi CK-04 lépés.

## 4. Rögzített domain- és termékdöntések

- A játékos rakja le a CK Wire-t; **GraphEdge nem keletkezik automatikusan** a lerakáskor.
- Az **Élbekötés** külön művelet; a Python domain topológiaellenőrzése után keletkezhet GraphEdge.
- GraphEdge = logikai port–port kapcsolat; EdgeRoute = fizikai vezetékút; WireCell = elhelyezett vezetékelem, SINGLE vagy CROSS csatornákkal.
- Hibás vagy megszakadt útvonalnál a logikai edge megmaradhat; automatikus törlés/javítás nincs.
- A Python az egyetlen hiteles graph-szemantikai motor; Fabric/Kotlin a világinterakció és megjelenítés felelőse.
- DB a tartós hiteles állapot; Stage SAVE külön a későbbi task/shift COMMIT-tól; DRAFT menthető; owner-only SAVE.
- Az első CK-04 demonstrációban nem vezetünk be csapatmunkát, publikációt, automatikus graph-upgrade-et vagy új Kotlin gráfvalidátort.

## 5. CK-04/A – Ágens Öcsi első megbízása: célzott felderítés

**Csak read-only vizsgálat és rövid jelentés. Implementáció TILOS.**

Felderítendő:

1. **Fabric mod állapota:** projekt(ek), Minecraft/Fabric/Kotlin verziók, entrypointok, kliens/szerver modulok, regisztrált blokkok, itemek, creative inventory tab, resource-ok, meglévő hangárintegráció.
2. **Eseményút:** jelenleg mely Minecraft-események jutnak el a Pythonhoz, pontosan milyen komponenseken/protokollon/DTO-n keresztül? Ha nincs működő út, ezt egyértelműen mondd ki. Ne következtess pusztán fájlnevekből.
3. **Valódi CK-blokkok és portok:** ténylegesen mi regisztrált és elhelyezhető? START, STOP, IF, FOR, FUN státusza külön-külön; portok megjelenítése és iránya; egyezés a Python `ck-control-v1` katalógussal.
4. **CK Wire:** létezik-e már blokk/modell/textúra/lerakási esemény/koordinátakezelés, portkapcsolat, SINGLE/CROSS megjelenítés? Mi csak koncepció?
5. **Élbekötés gomb:** létezik-e Minecraft-interakció, Stage-azonosítás, tulajdonosi jogosultság, topológiaellenőrzési hívás és visszajelzés?
6. **A minimális működő függőleges szelet akadályai:** pontosan mely fájlokban és rétegekben kell majd módosítani, és milyen sorrendben? Milyen azonosítók és koordinátatranszformációk szükségesek?
7. **Kockázatok:** szerveroldali autoritás, kliens/szerver szinkron, hiteles játékosazonosság, epoch/revision átadás, Stage–világ koordináták, idempotencia és hiba-visszajelzés. Csak a következő minimális szelet szempontjából szükséges döntéseket emeld ki.

### Elvárt kimenet

`docs/CK-04A-fabric-integracios-felderites.md` – tömör, bizonyítékokra épülő felderítési jelentés:

- **MŰKÖDIK / RÉSZBEN / NINCS / NEM ELLENŐRIZHETŐ** státusz minden kulcsképességre;
- fájlútvonalak, osztályok, események és meglévő API-k konkrét megnevezése;
- legfeljebb 2–3 reális megvalósítási út, rövid előny/hátrány;
- **egy javasolt CK-04/B minimális implementációs lépés**, világos acceptance testtel;
- döntést igénylő kérdések, ha vannak;
- Git státusz; explicit nyilatkozat, hogy kód, DB, Docker, deploy és commit nem módosult.

**Ne implementálj, ne készíts új DTO-t vagy protokollt, ne regisztrálj új blokkot, ne módosíts Minecraft-világot, ne futtass migrációt.** Felderítés után STOP és SysOrg review.

## 6. CK-04/B első látható demonstráció – még NEM kiadott feladat

**Elfogadási jelenet:** belépek a hangárba; lerakok egy START és egy STOP blokkot; fizikailag összekötöm őket CK Wire-rel; megnyomom a hangárfali Élbekötés gombot; a meglévő Python graph domain felismeri a kapcsolatot, a munkapéldányban létrejön a `START.out → STOP.in` CONTROL GraphEdge; Minecraftban egyértelmű sikeres/hibás visszajelzést kapok.

A konkrét blokk-, wire- és portmegvalósítást **csak CK-04/A után** választjuk meg. Az első demonstrációhoz nincs PostgreSQL SAVE, nincs REBUILD, nincs publikáció.

## 7. Következő beszélgetés nyitómondata

> Folytassuk a bloCKommandert a mellékelt CK-04 dobbantó alapján. CK-03/B3 dokumentáltan GO. Most a CK-04/A célzott, read-only Fabric-integrációs felderítését kérem Ágens Öcsi számára. A cél az első Minecraftból indított START → CK Wire → STOP → Élbekötés demonstráció, egyelőre SAVE és REBUILD nélkül. A felderítés után álljunk meg, és együtt válasszuk ki a legkisebb CK-04/B implementációt.
