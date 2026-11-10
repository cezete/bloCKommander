# Nyitott döntések és összehangolandó koncepciók

Dátum: **2026-11-09**, CK-08/DOC. Forrásbaseline: **main / b98b995**.
DOC2-integráció: **2026-11-10**, CK-08/DOC2-T8; baseline: **main / 8ff8dcb**. A dokumentációs GO a 012 beemelésére és e jegyzék bővítésére szól; nem zár le domainkérdést.

[Dokumentumindex](README.md) · [Állítás- és implementációs státuszok](STATUS.md)

Ez a jegyzék őrzi a T3-ban azonosított hét kérdést (DEC-001–007), és külön felsorolja a kiemelt témák további nyitott részleteit (DEC-008–010). A DOC2-kiegészítés további három nyitott tételt rögzít (DEC-011–013); korábbi döntést nem zár le. **Nem oldja fel önállóan a kérdéseket, és nem ad implementációs GO-t.**

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
## DEC-005 – CONTROL merge/WAGO, BOOLEAN kapukompozíció és VALUE merge

**Kezelési szabály: KÜLÖN DOMAINFOGALOM a CK-08/DOC T6 GO szerint. Az esetleges közös mechanizmus NYITOTT.**

**Forrás:** [008](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) 4–6. szakasz; [011](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) 2–5. szakasz; STATUS CS-021/024.

- **CONTROL merge/WAGO:** alternatív végrehajtási érkezések összevezetése, nem „minden bemenetre váró” párhuzamos join; a 008 szerint pontosan egy bekötött továbbvezetés.
- **BOOLEAN kapukompozíció:** logikai értékek művelete, például AND/OR több bemenettel; a 011 szerint azonos típusú kapuk kompozíciója és külön fan-out kérdés.

A 008 fizikai modulcsoporton szabadon maradó több OUT-ot is megenged, amelyek közül pontosan egy köthető tovább. A 011 összetett kapuja pontosan egy OUT-ot és minden más használható külső felületén IN-t ír le. Ezeket nem mossuk össze egy közös portszámképlettel.

**Döntési kérdés:** mely részletek lehetnek később közös fizikai infrastruktúra, és melyek maradnak kötelezően külön szemantikai szabályok?

**Alternatívák:** teljesen külön típuscsalád és lifecycle; vagy megosztott technikai többcellás reprezentáció külön szemantikai szerződésekkel. Egyetlen általános WAGO-típus bevezetése nem ennek a dokumentációs GO-nak a következménye.

**Nyitott részletek:** közvetlen szomszédság és crafting utáni fix alakzat viszonya, modul/IC/GraphNode azonosság, külső terminálok, BOOLEAN fan-out, CONTROL merge-validáció.

**Határ:** amíg nincs kifejezett architekturális döntés, az egyik domainhez tartozó szabály nem alkalmazható automatikusan a másikra.

### DOC2-kiegészítés – három külön szemantika

**Forrás:** [012 §4](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#4-when-egy-cstage-k%C3%A9t-%C3%A9rt%C3%A9k%C3%A1g-a-sz%C3%BCl%C5%91-vstage-en) és [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); CS-038. A 012 VALUE merge/WAGO-ja alternatív értékfolyamok vizuális összefogási javaslata; nem CONTROL-érkezési merge, nem BOOLEAN AND/OR művelet és nem fan-out. A korábbi két fogalom elkülönítése megmarad, a harmadikat sem vonjuk össze velük.

**NYITOTT:** mely fizikai megoldás osztható meg, és hogyan viszonyul a VALUE merge a WHEN kiválasztásához és RESULT-hoz? A három típuscsalád portjai, validációja és lifecycle-ja nem vezethetők le egymásból. A VALUE-részleteket [DEC-012](#dec-012) őrzi; nincs most közös WAGO-domain vagy portszámképlet.

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

### DOC2-kiegészítés – VStage/CStage és RESULT

**Forrás:** [007 §4](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md#4-param%C3%A9terek-%C3%A9s-return-stage-ek), [011 §2](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md#2-cstage-szemantikai-modell), [012 §1](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#1-alapelv-vez%C3%A9rl%C3%A9s-%C3%A9s-%C3%A9rt%C3%A9k-k%C3%BCl%C3%B6nv%C3%A1laszt%C3%A1sa), [012 §3](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#3-let-%C3%A9s-a-tiszta-jobb-oldal); CS-034/045.

- A 007 Paraméter és Return Stage START/STOP közötti placeholder-modelljét megőrizzük. A 012 „belső Stage-ek” START/STOP-mentessége nem bizonyított általános felülírás: csak VStage/CStage-re vonatkozik-e, vagy a szerződéses nézetekre is?
- A 011 egy BOOLEAN RESULT blokkot javasol. A 012 általános RESULT-kardinalitást külön szerződésre hagy; az előző javaslat nem minősül automatikusan visszavontnak.
- A VStage és CStage közös absztrakciója, a PStage-hez és ROOT/PROGRAM_BODY kindokhoz való viszony, a belső Stage tulajdonosi linkje és a típusellenőrzés OPEN. A jelenlegi kindokat nem nevezzük át automatikusan.
- A Stage-tartalmazási fa, a kifejezés-DAG és a PFUN-hívási gráf külön struktúra; a rekurzió nem jelent ciklikus Stage-tartalmazást.

**Alternatívák, választás nélkül:** VStage-specializációként kezelt CStage vagy külön típusok közös ellenőrzési alappal; külön szerződéses Paraméter/Return nézetek vagy kifejezett későbbi egységesítés. Nincs most Stage-kind-, RESULT- vagy SAVE-szerződésváltás.

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

### DOC2-kiegészítés – elnevezések, jelentések és CALL

**Forrás:** [007 §3](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md#3-fun-%C3%A9s-call--k%C3%A9t-k%C3%BCl%C3%B6n-blokkfajta), [007 §9](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md#9-kieg%C3%A9sz%C3%ADt%C3%A9s--function-%C3%A9s-procedure-2026-10-29), [011 §2](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md#2-cstage-szemantikai-modell), [012 §2](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#2-blokkok-%C3%A9s-defin%C3%ADci%C3%B3k), [012 §3](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#3-let-%C3%A9s-a-tiszta-jobb-oldal), [012 §5](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#5-fun-pfun-proc-%C3%A9s-call), [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); CS-035–037/041/043/044.

**Megőrzött eltérések:**

1. A 011 Expressionje bal/reláció/jobb szerkezetű BOOLEAN összehasonlítás; a 012 EXPR-je általános értékkifejezés, akár belső VStage-dzsel. A 012 kifejezett névdöntése (EXPRESSION/EXPR, LITERAL konstans, VALUE név elvetése) dokumentálható, de a specializáció/átnevezés/felülírás jelentését nem döntjük el.
2. A 007 FUN gyűjtőfogalmába a VOID PROCEDURE is tartozik; a 012 külön FUN/PFUN/PROC kategóriát javasol. A return-különbség nem szűnik meg, a név- és kategóriamodell OPEN.
3. A 007 CALL vezérlési IN/OUT-tal rendelkezik. A 012 CALL-célpontmátrixa PStage-en FUN/PFUN/PROC, VStage-en PFUN, CStage-en BOOLEAN PFUN. A kontextusfüggő értékhívás portjait és argumentumátadását nem határozza meg a régi CONTROL-modell.

**További nyitott kérdések:** LET/SET/ASSIGN megfeleltetése; deklaráció vagy újraértékadás; típuskonverzió és inference; VAR_REF/READ azonosság; lokális immutable nevek; scope/árnyékolás; import és láthatóság. CStage-ben numerikus PFUN eredménye közvetlen operandus lehet-e, vagy belső VStage szükséges? A 012 célpontmátrixa ezt nem rendezi.

**Határ:** WHEN értékágainak huzalozása nem önmagában a CALL-argumentum CK Wire-tilalmának feloldása. A PFUN-részletek [DEC-011](#dec-011), a gesztusok [DEC-013](#dec-013) alatt maradnak nyitva. Nincs új szimbólumtábla, típusrendszer vagy célnyelvi döntés.

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

### DOC2-kiegészítés – read-only kódnézet

**Forrás:** [012 §8](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#8-k%C3%B3dn%C3%A9zetet-mutat%C3%B3-falit%C3%A1bl%C3%A1k) és [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); CS-042. A VStage falitáblája és a külső LET rövid összefoglalója származtatott, read-only nézetjavaslat. A tábla és a későbbi szemantikus YAML nem második igazságforrás.

**NYITOTT:** hiányos/hibás kifejezés megjelenítése, rövidítés, determinisztikus formázás, frissítési lifecycle és a nézet visszavezetése domainazonosságra. A kódnézet előállítása nem azonos IR/codegen implementációval; Java/Kotlin szintaxis említése nem fogad el célnyelvi szemantikát. A YAML és teljes snapshot leképezése továbbra is nyitott, és nem változtatja meg a Program-SAVE szerződését.

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

<a id="dec-011"></a>
## DEC-011 – PFUN v1 ellenőrizhetősége

**Állapot: elfogadott induló korlátozás dokumentálva; az ellenőrzési részletszerződés NYITOTT.**

**Forrás:** [012 §2](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#2-blokkok-%C3%A9s-defin%C3%ADci%C3%B3k), [012 §5](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#5-fun-pfun-proc-%C3%A9s-call), [012 §6](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#6-pfun-v1-tisztas%C3%A1gi-szerz%C5%91d%C3%A9s), [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); STATUS CS-039–041/043/044.

**Dokumentált ACCEPTED határ:** saját explicit paraméterek, literálok, tiszta műveletek és PFUN-hívások; külső mutable változó, objektummező/implicit this, globális állapot, rejtett idő/random és mellékhatás kizárva. PStage-változó aktuális értéke argumentumként átadható; ez nem külső változóhely elérése.

**Rendezendő kérdések:**

- Saját VStage törzs, nem-VOID eredmény és RESULT, paraméterazonosság/sorrend/default, típus és érték-/referenciaátadás pontos kapcsolata.
- Engedélyezett immutable típuskör, objektumok mély immutabilitása és aliasolása; az egyszerű immutable értéktípusokra szűkítés jelenleg ajánlás.
- Célnyelvfüggetlen tiszta műveletkatalógus, numerikus pontosság/túlcsordulás, hibák és részleges műveletek; az azonos bemenet/eredmény értelmezése hibánál és nemterminálásnál.
- Importált PFUN tisztasági és típusszerződésének igazolása, verziója; osztálytagság, explicit receiver és implicit this tilalmának viszonya. Ismeretlen külső metódus nem automatikusan PFUN.
- A rekurzív hívás a 012-ben javasolt irány; kölcsönös rekurzió, transzitív tisztaság, terminálás és futáskorlát külön szerződés. A hívási gráf ciklusa nem azonos a kifejezés-DAG tiltott ciklusával.

**Alternatívák, választás nélkül:** elsőként szűk, ellenőrizhető művelet-/típuskészlet; vagy később szélesebb típuskör külön igazolási szabályokkal. Nem tervezünk teljes effect systemet.

**Határ:** nincs PFUN-implementáció, importált metódusminősítés, interpreter vagy codegen. Ez a jegyzék nem fogadja el önállóan a §6-on túli részletszabályokat.

<a id="dec-012"></a>
## DEC-012 – WHEN és VALUE merge

**Állapot: PROPOSED irány, részletszerződések NYITOTTAK.**

**Forrás:** [012 §4](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#4-when-egy-cstage-k%C3%A9t-%C3%A9rt%C3%A9k%C3%A1g-a-sz%C3%BCl%C5%91-vstage-en), [012 §7](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#7-szemantikai-p%C3%A9lda), [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); STATUS CS-038; [DEC-005](#dec-005).

**Javasolt modell:** WHEN értéket választ; saját CStage-je BOOLEAN feltételt ad, értékágai a szülő VStage-en vannak. Nem vezérlési IF és nem két új belső VStage. A VALUE-WAGO vizuális összefogás javaslat, nem kész gráfművelet.

**Döntési kérdések:**

- WHEN és VALUE-WAGO külön elem-e, és mi a RESULT-hoz vezető pontos topológia? Mely port milyen típust és kardinalitást fogad?
- A két ág típuskompatibilitása és a WHEN eredménytípusa hogyan számítódik? Van-e konverzió?
- Hogyan garantált a nem választott ág kiértékelésének elkerülése, ha a szülő gráfban mindkét ág jelen van? Mi a megosztott függőségek és hibás/hiányos ágak szabálya?
- Milyen állapot szerkeszthető, menthető, valid és kiértékelhető? A példa CONCAT műveleti blokk vagy PFUN-hívás választását nem dönti el.

**Alternatívák, választás nélkül:** külön WHEN és VALUE merge; vagy a kiválasztást a WHEN-be összefogó modell. Mindkettő külön szerződést igényel; a 012 nem végleges port-/wire-specifikáció.

**Határ:** CONTROL merge, BOOLEAN kapukompozíció és VALUE merge külön marad. Nincs automatikus AND/OR vagy CONTROL-port újrahasználat, nincs WHEN-implementáció vagy lusta kiértékelési döntés.

<a id="dec-013"></a>
## DEC-013 – Editor és JGB-navigáció

**Állapot: UX-, session- és navigációs határ NYITOTT.**

**Forrás:** [001 §6](001%20-%20Post%20Fabric%20world%20ut%C3%A1ni%20eszm%C3%A9l%C3%A9s.md#6-navig%C3%A1ci%C3%B3--norton-commander-modell), [007 §3](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md#3-fun-%C3%A9s-call--k%C3%A9t-k%C3%BCl%C3%B6n-blokkfajta), [007 §4](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md#4-param%C3%A9terek-%C3%A9s-return-stage-ek), [008 §2](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md#2-if-egyetlen-bels%C5%91-condition-stage), [012 §3](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#3-let-%C3%A9s-a-tiszta-jobb-oldal), [012 §5](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#5-fun-pfun-proc-%C3%A9s-call), [012 §8](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#8-k%C3%B3dn%C3%A9zetet-mutat%C3%B3-falit%C3%A1bl%C3%A1k), [012 §9](012%20-%20CK%20%E2%80%93%20%C3%89rt%C3%A9kkifejez%C3%A9sek%2C%20VStage%2C%20CStage%20%C3%A9s%20PURE%20FUN.md#9-nyitott-d%C3%B6nt%C3%A9sek); CK-08/B0 válaszbeli felderítés, nem külön repository-szerződés.

A 001 jobb kattintás → dialog → ENTER útja, a 008 IF-editorból külön gombos CStage-belépése, a 012 LET-VStage JGB-je és szűrt CALL-célpontválasztója nem ad egységesen eldöntött gesztust. A jelen PROGRAM közvetlen Stage-navigációját nem írjuk felül hallgatólagosan.

**Döntési kérdések:**

- Mikor nyílik tulajdonságeditor, célpontválasztó vagy belső Stage? Közös ablak külön belépéssel, módosítógesztus vagy külön eszköz legyen-e?
- Hogyan különül el a definíció paraméterlistája a CALL argumentumaitól; mi a REFERENCE, EXPRESSION és PARAMETER_LIST tényleges domainszerződése? Egy EXPRESSION nem válik automatikusan szabad szövegmezővé.
- Hogyan őrződik meg a fókusz/visszatérési pont; mi történik folyamatban lévő edit, Stage-váltás, késő válasz és sémaeltérés esetén?
- A read-only falitábla milyen információs/navigációs kapcsolatot ad az editorhoz, új igazságforrás nélkül?

**Határ és ütemezési javaslat:** a START/STOP címke/leírás továbbra is első vertical-slice jelölt; nem jóváhagyott implementáció. A schema-driven editor nem tartalmaz automatikusan programnyelvi Expression-modellt vagy általános Stage-navigációt. A 012 beemelése nem bővíti a B1-et LET/EXPR/WHEN/PFUN-fejlesztéssé és nem ad implementációs GO-t.

## Döntések későbbi lezárásának módja

Egy bejegyzés csak megnevezett projektgazdai/architekturális döntés alapján zárható le. Rögzítendő:
- döntés dátuma és forrása;
- pontosan mely állítás/alternatíva lett elfogadva vagy elvetve;
- mely korábbi döntést írja felül, ha valóban felülírja;
- implementációs határ és külön felhatalmazás szükségessége;
- kapcsolódó STATUS-azonosítók.

E dokumentációs körben nem született további szemantikai feloldás, és az eredeti tekercsek változatlanok maradnak.
