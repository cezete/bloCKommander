# Nyitott döntések és összehangolandó koncepciók

Dátum: **2026-11-09**, CK-08/DOC. Forrásbaseline: **main / b98b995**.
[Dokumentumindex](README.md) · [Állítás- és implementációs státuszok](STATUS.md)

Ez a jegyzék őrzi a T3-ban azonosított hét kérdést (DEC-001–007), és külön felsorolja a kiemelt témák további nyitott részleteit (DEC-008–010). **Nem oldja fel önállóan a kérdéseket, és nem ad implementációs GO-t.**

A dokumentációs T6 GO két kezelési szabályt már rögzített:
- CONTROL merge/WAGO és BOOLEAN kapukompozíció külön domainfogalomként kezelendő kifejezett architekturális összehangolásig.
- A fizikai Wire-törés és a workspace-módosítás eltérése nem automatikus implementációs hiba.

A „NYITOTT” nem azt jelenti, hogy minden érintett alapelv eldöntetlen. A dokumentált koncepcionális döntést és a még hiányzó részletszerződést külön jelöljük.

<a id="dec-001"></a>
## DEC-001 – Fizikai Wire-törés és workspace-módosítás határa

**Állapot: NYITOTT szemantikai összehangolás. Nem bugminősítés.**

**Forrás:** [004](004%20-%20Stage%20working%20copy%2C%20publik%C3%A1ci%C3%B3%20%C3%A9s%20k%C3%A9s%C5%91bbi%20csapatmunka.md) 3. szakasz, [005](005%20-%20Stage%2C%20feature%20branch%2C%20DRAFT%20%C3%A9s%20publik%C3%A1l%C3%A1s.md) 10. szakasz, [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 6. szakasz, [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) 5. és 8. szakasz; STATUS CS-010/012.

**Mai tényleges működés:** a [Fabric CKW-break interception](../../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt) a [WireInteractionCoordinator](../../src/main/kotlin/me/buciqua/blockommander/runtime/wire/WireInteractionCoordinator.kt) REMOVE műveletére vezet. A Python [WIRE_LAYOUT/_apply_wire](../../python-service/src/hangar/graph_application.py) eltávolítja a WireCellt a workspace tartalmából; valódi változáskor revision/dirty változhat. A GraphEdge nem törlődik automatikusan. Ez önmagában nem Program-SAVE és nem közvetlen DB-commit.

**Koncepcionális eltérés:** a fizikai sérülés/kiütés több tekercs szerint csupán fizikai eltérést eredményezne, domain-revision vagy jóváhagyott gráf módosítása nélkül.

**Döntési kérdés:** a normál játékos Wire-törés melyik kategória, és kell-e ettől megkülönböztetett fizikai sérülési esemény?

**Alternatívák, választás nélkül:**
- A normál játékos törés explicit workspace-szerkesztés marad; a véletlen/külső fizikai eltérés külön kategória.
- A törés csak fizikai eltérést jelez; a workspace módosításához külön megerősített művelet kell.
- Külön kezeljük az egyes eseményfajtákat, egyértelmű játékosi jelzéssel; ezek határait még specifikálni kell.

**Tisztázandó következmények:** mi a hiteles összehasonlítási alap; mit mutat a dirty/revision; mikor kell explicit bind/repair; hogyan viszonyul a kilépési figyelmeztetéshez és a DB-ben levő mentett állapothoz?

**Határ:** a jelen kódot és a korábbi acceptance eredményeit nem minősítjük hibásnak pusztán a koncepció eltérő szóhasználata miatt. Nincs mostani javítás vagy új reprodukció.

<a id="dec-002"></a>
## DEC-002 – A fogalomtár Atomic-példájának értelmezése

**Állapot: az eltérés dokumentálva; a későbbi magyarázó példa pontosítása NYITOTT.**

**Forrás:** [003](003%20-%20Fogalmak.md) „Három fogalom” / Atomic; [CK-01/B GraphEdge-életciklus](../CK-01B-mini-ADR.md); STATUS CS-013.

A fogalomtár elkerülendő helyzetként írja le, hogy Wire eltűnik a Minecraftból, miközben a GraphEdge megmarad a DB-ben. A jóváhagyott port/Graph-szerződés viszont a logikai él tudatos megőrzését követeli normál vezetékbontáskor. A DB-tranzakció és a Minecraft-projekció ráadásul nem egyetlen elosztott atomi tranzakció.

**Döntési kérdés:** milyen példával és megkülönböztetéssel magyarázzuk a jövőben az atomikusságot?
- Egy konkrét workspace-parancs egészben sikerül vagy nem változtat tartalmat.
- Egy Program-SAVE SQL-tranzakciója commitol vagy rollbackel.
- A világprojekció hibája külön helyreállítási kérdés, nem automatikus DB+világ atomikusság.

Ezek külön állítások; a felhasználó eredeti példájának szándékát nem írjuk át utólag.

**Határ:** az index/státuszjegyzék már jelzi az eltérést. Az eredeti 003 tekercs módosítása továbbra sem engedélyezett; erratum vagy új magyarázó szöveg csak külön döntésből készülhet.

<a id="dec-003"></a>
## DEC-003 – END_IF és CONTROL merge/WAGO viszonya

**Állapot: NYITOTT architekturális egyeztetés.**

**Forrás:** [001](001%20-%20Post%20Fabric%20world%20ut%C3%A1ni%20eszm%C3%A9l%C3%A9s.md) végrehajtási blokkok és vertical slice; [CK-01/B](../CK-01B-mini-ADR.md) END_IF-szabályok; [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 4–5. szakasz; STATUS CS-021/022.

A korábbi terv külön END_IF-et és ágproveniencia-szabályokat ír le. A 008 WAGO-ja általános **alternatív vezérlési merge**, nem párhuzamos szinkronizáló join és nem kizárólag EndIf. A rajz sem állítja, hogy minden IF-et kötelező WAGO-val zárni.

**Döntési kérdés:** hogyan éljen együtt a strukturált IF-lezárás és az általános merge?
- WAGO veszi át a korábbi END_IF helyét, új strukturális szabályokkal.
- END_IF megmarad külön speciális elemként az általános WAGO mellett.
- END_IF csak szerkesztői/validációs fogalom legyen egy merge-mechanizmus fölött.

**Nyitott részletek:** opcionális terminális ágak, több IF/SWITCH eredete, branch-proveniencia, szabad IN-ek, pontosan egy bekötött OUT szabálya, nem kívánt ciklusok és scope.

**Határ:** nincs bizonyított teljes visszavonási döntés a CK-01/B END_IF-szerződésére. A productionben ma sem END_IF, sem WAGO nincs. A BOOLEAN kapukompozíció nem ennek automatikus megoldása.

<a id="dec-004"></a>
## DEC-004 – WorkingCopy, branch, SAVE, COMMIT és PUBLISH egysége

**Állapot: NYITOTT adatmodell-/életciklus-egyeztetés.**

**Forrás:** [004](004%20-%20Stage%20working%20copy%2C%20publik%C3%A1ci%C3%B3%20%C3%A9s%20k%C3%A9s%C5%91bbi%20csapatmunka.md), [005](005%20-%20Stage%2C%20feature%20branch%2C%20DRAFT%20%C3%A9s%20publik%C3%A1l%C3%A1s.md), [009](009%20-%20CK-06%20architekt%C3%BAra%2C%20nagykock%C3%A1san.md), [010](010%20-%20A%20bloCKommander%20k%C3%B6vetkez%C5%91%20hagymar%C3%A9tegei.md); [CK-06/A1-R](../CK-06A1R-program-stage-hall-architecture.md); STATUS CS-005–010/028/029.

**Dokumentált irányok:**
- 004: közös, Hall/Stage-hez kötött munkapéldány, külön stabil verzió.
- 005: owner/branch/Stage feloldás és tartós DRAFT-autosave.
- 010: repositoryhoz tartozó branch; SAVE nem COMMIT, PUBLISH nem automatikus merge.
- Mai kód: shared Program-workspace, Hall/WC transport identity, explicit Program-wide SAVE.

**Döntési kérdés:** hogyan feleljenek meg egymásnak a feloldási kulcsok és mentési határok?

**Alternatívák és rendezendő szintek:** Hall mint puszta nézet kontra Hallhoz kötött munkaállapot; Program-aggregátum kontra tágabb repository-munkacsomag; Stage-snapshot kontra több objektumos commit; explicit SAVE kontra visszaigazolt autosave. Ezek eltérő rétegek is lehetnek, nem szükségképpen egymást kizáró modellek.

**Nyitott részletek:** alapverzió rögzítése, több Stage/task publikációja, dirty és tartósítás-visszaigazolás külön jelzése, restart utáni resume/reset, konfliktus megőrzése, branch-váltás közben folyamatban levő műveletek.

**Már rögzített koncepciós döntés:** 010 szerint a CK-repository PostgreSQL-alapú; nem kell valódi .git/push/pull. Ezt nem nyitjuk újra azzal, hogy az adatmodell részletei még hiányoznak.

**Határ:** a mai SAVE nem nevezhető át hallgatólagosan PUBLISH-re. A WorkingCopy UUID nem önmagában branch vagy tartalmi fork.

<a id="dec-005"></a>
## DEC-005 – CONTROL merge/WAGO és BOOLEAN kapukompozíció

**Kezelési szabály: KÜLÖN DOMAINFOGALOM a CK-08/DOC T6 GO szerint. Az esetleges közös mechanizmus NYITOTT.**

**Forrás:** [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 4–6. szakasz; [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) 2–5. szakasz; STATUS CS-021/024.

- **CONTROL merge/WAGO:** alternatív végrehajtási érkezések összevezetése, nem „minden bemenetre váró” párhuzamos join; a 008 szerint pontosan egy bekötött továbbvezetés.
- **BOOLEAN kapukompozíció:** logikai értékek művelete, például AND/OR több bemenettel; a 011 szerint azonos típusú kapuk kompozíciója és külön fan-out kérdés.

A 008 fizikai modulcsoporton szabadon maradó több OUT-ot is megenged, amelyek közül pontosan egy köthető tovább. A 011 összetett kapuja pontosan egy OUT-ot és minden más használható külső felületén IN-t ír le. Ezeket nem mossuk össze egy közös portszámképlettel.

**Döntési kérdés:** mely részletek lehetnek később közös fizikai infrastruktúra, és melyek maradnak kötelezően külön szemantikai szabályok?

**Alternatívák:** teljesen külön típuscsalád és lifecycle; vagy megosztott technikai többcellás reprezentáció két külön szemantikai szerződéssel. Egyetlen általános WAGO-típus bevezetése nem ennek a dokumentációs GO-nak a következménye.

**Nyitott részletek:** közvetlen szomszédság és crafting utáni fix alakzat viszonya, modul/IC/GraphNode azonosság, külső terminálok, BOOLEAN fan-out, CONTROL merge-validáció.

**Határ:** amíg nincs kifejezett architekturális döntés, az egyik domainhez tartozó szabály nem alkalmazható automatikusan a másikra.

<a id="dec-006"></a>
## DEC-006 – Craftolt kapugeometria, OUT és tárgy-életciklus

**Állapot: NYITOTT részletszerződés.**

**Forrás:** [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) 3–4. és 9. szakasz; STATUS CS-024/025.

A tekercs szerint a craftolt alakzat rögzített, egészben elforgatható; a crafting redstone-jelölője választ kimenetet. Ugyanakkor modalos kimenetkijelölés/megtekintés is felmerül.

**Döntési kérdés:** a későbbi modal csak megmutatja az OUT-ot, vagy módosíthatja is?
- Csak megtekintés; más kiosztáshoz új tárgy/crafting.
- Lerakás előtt engedett konfiguráció.
- Lerakás után is módosítható, de explicit kapcsolatrendezéssel és konzisztenciaellenőrzéssel.

**További nyitott részletek:** többcellás placement és visszavétel atomikussága; részleges bontás; állapotmegőrzés ládába helyezéskor; shape/FACING/portszámozás determinisztikussága; a P=4N−2E peremképlet fizikai terminálokra leképezése; creative-kivétel és crafting ösztönzése.

**Határ:** normál Minecraft-tárgy birtoklása a koncepció szerint sem ad Hall-szerkesztési, SAVE vagy PUBLISH jogot. Most nincs crafting-, item- vagy jogosultságimplementáció.

<a id="dec-007"></a>
## DEC-007 – Condition Stage: következő szerződés és minimális szelet

**Állapot: a CStage-irány dokumentált; a részletszerződés és következő implementációs határ NYITOTT.**

**Forrás:** [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 2. szakasz és döntésösszegzés; [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) CStage-modell; [CK-08/A](../CK-08A-fejlesztesi-frontok-es-blokkrendszer-felderites.md); STATUS CS-016/023.

A 008 egy belső Condition Stage-et rendel az IF-hez; az IF két külső vezérlési portja nem saját Stage. A 011 Expression/AND/OR/NOT/RESULT BOOLEAN DAG-ot vázol fel, START/STOP nélkül.

**Döntési kérdés:** mi legyen a következő külön megbízás?
- Először minimális CStage domain/API/link/validációs szerződés és példa.
- Már körülhatárolt CStage vertical slice, a szükséges szerződéskapu után.
- Előbb meglévő UI/paraméter/SAVE infrastruktúra felderítése, a CStage-irány megőrzésével.

A „helyette egyszerű IF-szövegmező” nem automatikus alapértelmezés: a most feltárt döntési források fényében ez külön, kifejezett irányváltást igényelne.

**Nyitott részletek:** Stage-kind/link/owner scope; RESULT számossága és bekötése; félkész blokkok menthetősége; valid/kiértékelhető/publikálható állapot; CONTROL és BOOLEAN portok határa; az Expression operandusainak típusa és mellékhatásmentessége.

**Határ:** a 011 koncepciója nem engedély IF-, CStage-, GUI-, BOOLEAN Graph- vagy interpreter-implementációra.

<a id="dec-008"></a>
## DEC-008 – Scope, változó, Expression és FUN/CALL részletszabályok

**Állapot: alapelvek dokumentáltak, további szabályok NYITOTTAK.**

**Forrás:** [007](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md) 3–9. szakasz, [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 3. szakasz, [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) Expression és nyitott kérdések.

**Nem nyitjuk újra hallgatólagosan:** FUN definíció és CALL hivatkozás külön fogalom; CALL-argumentum nem külön CK Wire; a ProgramBlock belső deklarációja nem látható kifelé; FUNCTION és VOID PROCEDURE külön return-szerződésű.

**Rendezendő kérdések:**
- Shadowing, deklarációs élettartam és korai kilépés milyen szabályú?
- Mi a változó tartós azonossága, típusa, kezdőértéke és névfeloldása?
- Milyen operandusok/relációk és függvényhívások megengedettek Expressionben?
- A FUN szerződéses paraméter-/Return Stage-e, a CALL argumentum-Stage-e és az implementáció szerkeszthető másolata hogyan kapcsolódik?
- Mi a CALL OUT pontos fizikai oldala, az overload/default-feloldás és a hiányzó implementáció jelzésének ideje?

**Alternatívák:** a nyitott részletek szerződésenként rendezhetők, vagy nagyobb, összefüggő névfeloldási/szignatúra-szeletbe vonhatók. Egyik út sem jelent automatikus célnyelvi szintaxis- vagy IR-választást.

**Határ:** a jelen PROGRAM Stage-hierarchia nem magától FUN-hívási környezet és nem kész változómodell.

<a id="dec-009"></a>
## DEC-009 – Web Workbench, szemantikus YAML és teljes snapshot

**Állapot: PROPOSED, részletszerződések NYITOTTAK.**

**Forrás:** [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) 1., 6. és 9. szakasz; [001](001%20-%20Post%20Fabric%20world%20ut%C3%A1ni%20eszm%C3%A9l%C3%A9s.md) REFORMAT; STATUS CS-030–032.

A tekercs két felületet javasol ugyanazon authoritative domain fölött. A kézzel írt YAML lehet UUID/pozíció nélküli szemantikus leírás; a veszteségmentes snapshotnak viszont meg kell őriznie az identitást és geometriát.

**Döntési kérdések:**
- Új szemantikus import új identitásokat ad-e, és hogyan különbözik meglévő Stage frissítésétől?
- Mikor explicit az import, layout, bind és SAVE? Az automatikus layout nem jelent automatikus publikálást.
- Hogyan véd a web és MC egyidejű műveleteinek epoch/revision-konfliktusai ellen?
- Mi választja el a böngészőbeli kijelölést az engedélyezett Minecraft-projekciótól?

**Alternatívák:** először csak szemantikus import/export; vagy külön, teljes snapshot formátum; illetve a kettő együtt, egyértelműen eltérő szerződéssel. A jelen illusztratív YAML nem végleges séma.

**Határ:** nincs új webkliens, parser, layout engine, IR vagy generátor. A YAML nem második igazságforrás.

<a id="dec-010"></a>
## DEC-010 – Jogosultság, repositoryhatár és több nézet

**Állapot: közös munka alapelvei dokumentáltak; teljes ACL/konkurenciamodell NYITOTT.**

**Forrás:** [004](004%20-%20Stage%20working%20copy%2C%20publik%C3%A1ci%C3%B3%20%C3%A9s%20k%C3%A9s%C5%91bbi%20csapatmunka.md), [005](005%20-%20Stage%2C%20feature%20branch%2C%20DRAFT%20%C3%A9s%20publik%C3%A1l%C3%A1s.md), [010](010%20-%20A%20bloCKommander%20k%C3%B6vetkez%C5%91%20hagymar%C3%A9tegei.md), [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) inventory-jogosultság elválasztás.

**Rögzített koncepcionális szándék:** a meghívott a közös munkát szerkeszti, nem rendezi át saját branch-e szerint a házigazda Hallját; a meghívás visszavonása nem rollback. Egy kaputárgy birtoklása nem jogosultság.

**Döntési kérdések:**
- Hogyan fér össze Stage-részfára szóló meghívás és a tervezett repository/Directory ACL?
- Milyen hatású a branch/Stage-váltás a folyamatban levő meghívotti műveletekre?
- Azonos objektum/branch több Hallban hogyan frissül és hogyan kezeli a közös módosításokat?
- Hol kezdődhet repository, hogyan kerülhető el beágyazott repositoryk kettős verziózása?

**Alternatívák:** először szűk owner + Stage-meghívás; vagy később általános ACL-réteg. Ez ütemezési választás, nem most meghozott hozzáférési szerződés.

**Határ:** a production owner-only ellenőrzés nem kész meghívási rendszer; a Hall Selection WorkingCopy-ja nem önmagában külön szerkesztési branch.

## Döntések későbbi lezárásának módja

Egy bejegyzés csak megnevezett projektgazdai/architekturális döntés alapján zárható le. Rögzítendő:
- döntés dátuma és forrása;
- pontosan mely állítás/alternatíva lett elfogadva vagy elvetve;
- mely korábbi döntést írja felül, ha valóban felülírja;
- implementációs határ és külön felhatalmazás szükségessége;
- kapcsolódó STATUS-azonosítók.

E dokumentációs körben nem született további szemantikai feloldás, és az eredeti tekercsek változatlanok maradnak.
