---
title: "bloCKommander – Stage, feature branch, DRAFT és publikálás"
date: 2026-10-26
tags: [bloCKommander, CK-05, architektura, stage, branch, draft, publish, minecraft]
status: "Koncepció – elfogadott elvek és nyitott tervezési kérdések"
---

# bloCKommander – Stage, feature branch, DRAFT és publikálás

> [!abstract] Cél
> A CK munkapéldányainak tartós megőrzése, a játékosonkénti több feature branch, a publikált verziók elkülönítése és az egyetlen fizikai hangár–Stage vetítés szabályainak rögzítése. Ez **tervezési dokumentum**, nem megvalósítási jelentés.

## 1. Kiinduló kép: három szereplő

- **Fabric/Kotlin – karmester és színpadmester:** Minecraft-események, játékosazonosság, jogosultsághoz kötött műveletindítás, Stage/branch kiválasztásának kiszolgálása, fizikai világvetítés. Nem tartalmaz második gráfmotort.
- **Python – agy és munkamemória:** Stage- és Graph-domain, validáció, aktuális munkapéldányok, epoch/revision, műveleti szerződések.
- **PostgreSQL – kódex:** a tartósan megőrzött DRAFT-ok **és** a publikált verziók tárolója. Nem csak a véglegesített állapotot őrzi.

A Minecraft saját világmentése a blokkok fizikai állapotát őrzi; nem helyettesíti a CK logikai Stage- és Graph-állapotát.

## 2. Elfogadott alapelvek

1. **Egy hangárban egy adott pillanatban egyetlen aktív Stage-vetítés van.**
2. A hangár tulajdonosa választja meg, hogy a hangárban **melyik saját feature branch melyik Stage-e** jelenjen meg.
3. A tulajdonos branch-et válthat; ez a hangár Minecraft-blokkjainak és vezetékeinek automatikus átrendezésével járhat.
4. A meghívott munkás a tulajdonos által berendezett közös munkateret látja és – jogosultsága szerint – szerkesztheti. **Nem rendezheti át a hangárt saját PlayerID/BranchID alapján**, és nem válthatja át annak aktív branch-ét vagy Stage-ét.
5. A látogató csak nézelődhet; az állatok és mobok sem kapnak CK Stage-berendezési jogosultságot. A fizikai világvédelmet ettől függetlenül kezelni kell.
6. Egy játékosnak **több feature branch-e lehet egyszerre**, és a saját beállításai között választhat közülük.
7. A branch **több Stage** munkaváltozatát foghatja össze; a FUN külső és belső Stage-e így ugyanazon fejlesztési ághoz tartozhat.
8. Egy Stage félkész munkaváltozata **automatikusan, tartósan mentődik** DRAFT-ként; a Stage-váltás nem követel publikálást és nem dobhatja el a félkész munkát.
9. A **publikálás** külön, explicit gombnyomáshoz és **hangártulajdonosi jogosultsághoz** kötött.
10. A **build** kizárólag publikált, lehetőleg konkrét verzióval azonosított állapotból dolgozhat; DRAFT-ból nem.
11. Ha egy játékos az adott branch-en még nem rendelkezik Stage-drafttal, a branch megfelelő alapverzióját látja; első szerkesztése nem módosíthatja közvetlenül a publikált állapotot.
12. A meghívott munkás változtatásai a hangár tulajdonosának **aktív közös munkaváltozatába** kerülnek; nem a meghívott saját branch-ébe. Kilépése nem tünteti el a módosításait.

## 3. Azonosítók és fogalmak

| Fogalom | Jelentés |
|---|---|
| `HangarID` | A fizikai munkaterület és annak tulajdonosi kerete |
| `OwnerPlayerID` | A hangár tulajdonosa, a berendezés és publikálás jogosultja |
| `BranchID` | Egy játékos tartós, névvel ellátott fejlesztési ága |
| `StageID` | Egy logikai Stage azonosítója; nem azonos a hangár fizikai koordinátáival |
| `PublishedVersion` | Változatlan, buildelhető, véglegesített Stage-/kiadási állapot |
| `Draft` | Tartósan megőrzött, nem feltétlenül kész Stage-munkaváltozat |
| `workspace_epoch` | Egy betöltött munkapéldány-életciklus azonosítója; nem időrendbe rendezhető verzió |
| `working_revision` | Az adott epochon belüli módosítások revíziója |

A Stage-draft **logikai feloldási kulcsa**: `(OwnerPlayerID, BranchID, StageID)`. A tényleges adatbázis-séma még tervezendő; a BranchID önálló, stabil entitásazonosító legyen.

**Fontos:** a hangárban dolgozó vendégmunkás PlayerID-je nem írja felül a draft tulajdonosát. A munkaváltozat továbbra is a hangár tulajdonosának aktív branch-éhez tartozik.

## 4. Betöltési és navigációs szabály

A hangártulajdonos kiválasztja az aktív branch-et és Stage-et.

1. A rendszer feloldja a tulajdonos kiválasztott `(BranchID, StageID)` munkaváltozatát.
2. Ha van tartós DRAFT, azt aktiválja.
3. Ha nincs, a branch rögzített alapverzióját jeleníti meg.
4. A Fabric az új hiteles állapothoz igazítja a hangár fizikai blokkjait és CK Wire-jait.
5. A meghívott munkások és látogatók **ugyanazt az egy fizikai vetítést** látják.
6. FUN-ba belépéskor az aktív Stage változik; a korábbi Stage draftja megmarad, a hangár pedig az új Stage szerint rendeződik be.
7. Visszatéréskor az előző Stage munkaváltozata folytatható.

> [!important] Branch-váltás
> A `feature/fast-substr` → `feature/unicode-substr` váltás nem csupán menüállapot: eltérő START/STOP/FUN blokkokat, vezetékeket, portokat és később dekorációt jeleníthet meg ugyanazon fizikai hangárban. A váltást csak a tulajdonos kezdeményezheti.

## 5. DRAFT, SAVE, PUBLISH, BUILD

### 5.1. DRAFT autosave

A félkész Stage minden érdemi módosítása tartósan megőrzendő. Az automatikus mentés **nem publikálás** és nem teszi buildelhetővé a Stage-et. A Python processz, Fabric szerver vagy Minecraft kliens újraindítása után is vissza kell tudni állítani a legutóbb **sikeresen tartósított** DRAFT-ot.

A rendszernek jeleznie kell, ha a legutóbbi változtatások mentése még folyamatban van vagy meghiúsult. Nem állíthatja, hogy minden megőrződött, amíg nincs tartósítási visszaigazolás.

### 5.2. PUBLISH

A tulajdonos explicit vezérlővel kezdeményezi. A publikálhatóság feltételei – Stage-/Graph-validáció, esetleges több-Stage/task-konzisztencia, konkurens publikáció és verzióütközés – külön szerződésben kidolgozandók. A publikált verzió változatlan és buildelhető.

### 5.3. BUILD

Csak publikált verziót olvashat. A buildnek konkrét verzióazonosítóra kell hivatkoznia, hogy később megismételhető legyen. A játékos aktuális DRAFT-ja nem szivároghat be a buildbe.

### 5.4. A jelenlegi CK-03/B3-hoz viszonyítva

A meglévő `SAVE` képes DRAFT Stage-állapot tárolására, de **még nem jelenti a teljes autosave + több branch + külön publikált főág modell megvalósítását**. A fogalmak és tárolási szerződések szétválasztása későbbi fejlesztés.

## 6. Branch alapverziója és változások

Egy branch létrehozásakor rögzítendő, melyik publikált alapállapotból indult. Ha időközben a publikált ág előrelép, a branch változatlan részei **ne frissüljenek észrevétlenül** másik alapverzióra.

Későbbi külön műveletek lehetnek:

- `Update from main` / `Rebase`;
- változások összehasonlítása;
- konfliktusfeloldás;
- integrációs validáció;
- tulajdonosi publikálás.

A Git itt **szemléleti analógia**; nem eldöntött, hogy valódi Git-objektumokat vagy saját adatbázisos verziómodellt használunk.

## 7. Jogosultságok és közös munka

| Szereplő | Hangár Stage/branch berendezése | Aktív Stage szerkesztése | Publikálás |
|---|---|---|---|
| Hangártulajdonos | Igen | Igen | Igen |
| Meghívott munkás | Nem | Igen, meghívási hatókörben | Nem |
| Látogató | Nem | Nem | Nem |
| Mob / állat | Nem | Nem | Nem |

Korábbi elfogadott csapatmunka-elv: a meghívás egy Stage-csomóponthoz kötődhet és annak leszármazottaira öröklődhet; a tulajdonos azonnali hatállyal visszavonhatja. A már végrehajtott közös szerkesztések megmaradnak. Ennek pontos branch- és Stage-váltási jogosultsági következményeit még specifikálni kell.

## 8. Vizuális munkastátusz

A jelenlegi egyszerű tábla helyett jól látható Stage-státuszkijelzés kívánatos, például a hangár állandó felületén vagy CK HUD-ban:

- aktív `Stage` és `Branch` neve;
- publikált alapverzió;
- `DRAFT` / `PUBLISHED` állapot;
- van-e eltérés a publikált verzióhoz képest;
- autosave: **mentve / folyamatban / hiba**;
- publikálhatóság és diagnosztikák.

A `dirty` és a **tartósítás visszaigazolása** nem ugyanaz: lehet eltérés a publikált verzióhoz képest úgy is, hogy a DRAFT már biztonságosan mentve van.

## 9. REBUILD pontosítása

A Minecraft saját chunk-/világ-visszatöltése nem CK REBUILD. A CK-ban külön kell kezelni:

- **Resume Draft:** a legutóbb tartósított munkaváltozat visszaállítása;
- **Load/Switch Stage:** navigáció másik Stage-re, draftvesztés és publikálási kényszer nélkül;
- **Switch Branch:** tulajdonosi döntés, a hangár teljes Stage-vetítésének cseréjével;
- **Reconcile/Render:** a Minecraft világ fizikai állapotának igazítása a kiválasztott hiteles CK-állapothoz;
- **Reset to Published:** tudatos, megerősített visszaállás a publikált verzióra;
- **Build:** publikált verzió feldolgozása.

A korábbi `REBUILD` API konkrét jelentését az átnevezés vagy bővítés előtt fel kell térképezni.

## 10. Kockázatok és nyitott kérdések

1. **Autosave atomikusság:** mi történik a már látható, de még nem tartósított szerkesztéssel szerverleálláskor?
2. **Branch-váltás közbeni módosítás:** előbb be kell fejezni a függőben lévő autosave-et; hibás mentésnél ne legyen csendes adatvesztés.
3. **Több meghívott szerkesztő:** konkurens műveletek sorrendje, revision/CAS, elvesző frissítések elkerülése.
4. **Branch-alapverzió:** Stage-enként vagy teljes Stage-fa/kiadási snapshotként rögzítjük?
5. **Publikálás egysége:** egy Stage, több Stage-ből álló task, vagy hangár-munkacsomag? Korábbi task-/műszakhatár-döntésekkel egyeztetendő.
6. **Tulajdonosi branch-váltás vendégmunkások mellett:** folyamatban lévő kérések fencingje, értesítés, jogosultságok.
7. **Világvetítés biztonsága:** idegen blokkok védelme, eltérő branch-változatok közti teljes és idempotens takarítás/újrarajzolás.
8. **Mobok és fizikai sérülések:** a Minecraft-világ eltérései nem módosíthatják észrevétlenül a CK logikai GraphEdge-et vagy a DRAFT-ot.
9. **Régi B3 wire break szemantika:** a jelenlegi `WIRE_LAYOUT cell:null` explicit domainmódosítás, míg a véletlen fizikai sérülésnek korábbi döntés szerint nem szabad domainmódosítássá válnia; a két műveletet külön kell választani.
10. **Több branch tárolási költsége, életciklusa:** archiválás, törlés, névütközés, jogosultság, branch-azonosító.
11. **Kezdő játékos és főág:** mit lásson és hogyan kezdjen dolgozni, ha nincs aktív saját branch-e?

## 11. Javasolt fejlesztési határ

**CK-04/B4A** jelenleg GraphEdge-vetítéssel és CK Wire-színezéssel foglalkozik. A mostani branch-/draft-koncepció **nem változtatja meg visszamenőleg a B4A feladatát**.

**CK-05 – Tartós munkaváltozatok, feature branchek, publikált Stage-verziók** javasolt külön tervezési/implementációs mérföldkő, először szerződés- és migrációs felderítéssel. Nem automatikus GO adatbázis-migrációra.

## 12. Rövid alapelv

> **A hangár a tulajdonos által kiválasztott branch egyetlen aktuális Stage-ét jeleníti meg. A félkész munka automatikusan és tartósan DRAFT-ként megőrződik. A meghívott munkás ebben a közös munkaváltozatban dolgozik, de nem rendezheti át a hangárt. Publikálni csak a tulajdonos tud, explicit gombbal. Buildelni kizárólag publikált, verziózott állapotból szabad.**
