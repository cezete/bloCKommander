# bloCKommander – koncepció

> Verzió: 0.1 – 2026-10-22  
> Státusz: koncepció és tervezési döntések, **nem implementációs specifikáció**.  
> Cél: Obsidian-jegyzet, új beszélgetések és Ágens Öcsi feladatpromptjainak kiindulópontja.

## 1. Vízió és célcsoport

A **bloCKommander (CK)** Minecraft/Fabric alapú, fizikailag építhető szoftvermodellező környezet. A felhasználó Minecraft-blokkokból és saját CK vezetékekből programstruktúrát, vezérlési gráfot, rendszerarchitektúrát és UI-modellt épít. A hiteles szemantikus modell adatbázisban él; a Minecraft a térbeli szerkesztő és megjelenítő.

Lehetséges kimenetek: pszeudokód, célnyelvi forráskód, Markdown fejlesztési promptok, specifikációk, tesztvázlatok, UI-definíciók. A kezdeti cél **nem** teljes IDE-helyettesítés, hanem élvezetes hobbi/oktatási modellezés, később pontos, géppel feldolgozható tervezési artefaktumok és csapatmunka.

Példák: baráti 6502-es algoritmusépítés; közös package- és DB-tervezés; Spring/Java architektúra; UI-képernyő modellezése; Ágens Öcsi számára promptok előállítása.

## 2. Meglévő környezet és állapot

- Szerver: HP Ubuntu „Nagypapi”; Minecraft **26.3**, Fabric Loader **0.19.5**, Fabric API; Fabric szerver a **25566** porton, korábbi Paper szerver párhuzamosan a 25565-ön.
- Windows kliens: Fabric telepítve; csatlakozás sikeres. A **Fabric migráció sikeres**.
- A CK jelenleg egyetlen mod JAR-ba csomagolt; ez egyelőre megfelelő. Belső moduláris szervezés előbb, külön mod JAR-ok csak valós önálló kiadási/felhasználási igénynél.
- IntelliJ ernyő: `C:\Users\tamas\IdeaProjects\`; korábbi projektek: `BlockCommanderTransmitter`, `CKParser`, `CKpu`, és új Fabric projekt (pontos mappanév ellenőrizendő).
- Korábban létező funkciók: hangárkardos kijelölés/építés, jobb kattintásos blokkdialog, Destroy/Save felületek; **migráció utáni konkrét implementációs állapotot fel kell mérni**, nem szabad feltételezni, hogy minden korábbi funkció változatlanul él.
- PostgreSQL már része a rendszernek. **A DB a hiteles állapot**; a hangár megjelenítés és szerkesztési munkapéldány.

## 3. Fogalmi modell: három különböző dolog

1. **Model Node**: szoftverrendszer szemantikus eleme (Project, Package, Class, Method, Screen, Field stb.).
2. **Workspace View / Stage**: egy Model Node szerkeszthető nézete (Body, Params, Return, Imports, Members, UI stb.). A felhasználó ezt „Stage”-ként éli meg. Nem minden Stage végrehajtási gráf.
3. **Hangar**: Minecraftbeli fizikai szerkesztőpanel, amely egy aktuális nézetet jelenít meg. Egy fejlesztőhöz N hangár tartozhat. A hangár és a modellobjektum nem azonos.

További alapfogalmak:

- **ModelRelation**: szemantikus kapcsolat (contains, extends, implements, imports, invokes, binds, depends-on stb.).
- **Annotation**: modellobjektumhoz kapcsolt, paraméterezhető metaadat (`@Service`, `@Controller`, `@Transactional`, `@GetMapping` stb.). Nem control-flow elem.
- **GraphNode**: Stage-ben megjelenő CK blokk, azonosítóval és portokkal.
- **GraphEdge**: két port közötti logikai kapcsolat, irány és típus szerint.
- **Layout**: fizikai koordináták, orientáció, vezetékútvonalak, megjelenítés; **a reformat csak ezt változtathatja**.
- **NavigationContext**: aktuális hangár, nézet, fókusz, visszatérési pont és verem.
- **AmbientProfile**: nézettípushoz rendelt padló/fal/plafon/világítás/dekoráció/entitásprofil.

### Minimális fogalmi hierarchia (nem DB-táblaséma)

```text
CK Model
 └─ Project / Subproject
     └─ Package / Module
         ├─ Class / Interface / Enum
         │   ├─ Property / Constant / Constructor
         │   ├─ Method
         │   │   ├─ Parameters / Return / Imports (szerkesztési nézetek)
         │   │   └─ Body Stage -> GraphNodes + GraphEdges
         │   └─ Inner Class
         └─ Screen
             └─ FieldGroup
                 └─ Field / Button / Control

ModelNode -> 0..N WorkspaceView
ModelNode -> 0..N Annotation
WorkspaceView -> 0..N GraphNode, GraphEdge, Layout
Hangar -> 1 aktuális WorkspaceView (időben változik)
```

Ez **nem** kizárólagos tartalmazási lánc: a projekten belüli több package, alprojekt, valamint különböző típusú szemantikus hivatkozások megengedettek. A Stage típusától függ, hogy az élek végrehajtási vagy szerkezeti jelentésűek-e.

## 4. CK blokkok és szerkesztési módok

### Strukturális (beléphető) blokkok

`[PROJECT]`, `[PACKAGE]`, `[CLASS]`, `[INTERFACE]`, később `[ENUM]`, `[METHOD]`, `[SCREEN]`, `[FIELDGROUP]` és egyéb konténerek. Mind **fizikailag lerakható**. Jobb kattintásos dialogból ENTER/TELEPORT; a hangár tartalma az adott belső nézetre vált.

CLASS belseje: inner classok, property-k, konstansok, metódusok, konstruktorok, kapcsolatok. Strukturális Stage-ben az egymás mellett lévő elemek nem feltétlenül végrehajtási sorrendet jelentenek.

### Végrehajtási blokkok

- `[START]` (0 IN, 1 OUT), `[STOP]` (1 IN, 0 OUT).
- Egyenes műveletek: `[VAR]`/deklaráció, `[SET]`/értékadás, `[INPUT]`, `[PRINT]`, `[CALL]` stb. jellemzően 1 IN / 1 OUT.
- `[IF]` (1 IN, két megjelölt `0`/`1` OUT), fizikailag lerakott `[END IF]` (ágak összefutása, 1 OUT).
- `[FOR]` (külön belépési és **külön visszatérő fizikai IN**, `BODY` és `EXIT` OUT), párja **`[NEXT]`** (nem END FOR).
- `[WHILE]` (külön belépési és visszatérő fizikai IN, BODY/EXIT OUT), `[END WHILE]`.
- `[DO]` és `[LOOP WHILE]` a hátul tesztelő ciklushoz; pontos portszemantika részletes tervezésre vár.
- `[BREAK]`, `[CONTINUE]`, `[RETURN]`, `[THROW]`: **helyi terminális elemek** lehetnek (1 IN, 0 normál OUT). Nem kell fizikailag a ciklus végéhez vezetni a BREAK/CONTINUE vezetéket. A motor a strukturális beágyazásból határozza meg a célciklust/switch-et és a kódgenerálási jelentést.
- `CASE`/`SWITCH` további tervezésre vár. BREAK jelentése célkonstrukció- és célnyelvfüggő.

Fontos: a gráfban több terminális csúcs lehetséges. Az `END IF` nem várja meg mindkét ágat: csak a ténylegesen választott ág jut oda.

### Kifejezés-Stage-ek

IF feltétel, FUN belseje, értékadás jobb oldala stb. külön szerkeszthető nézetet kaphat. Külső Stage-ben a kifejezés bemenetei kiszámíthatók, a belső nézet paraméterhelyeket és eredményt használhat. A pontos expression-szemantika és portmodell későbbi specifikáció.

## 5. CK Wire – fizikai vezeték, szemantikus él

- **Saját Fabric CK Wire blokk**; lapos, vastag fekete vonal, Redstone-/sín-szerű kézi lerakással és kanyarodással.
- **Egy vízszintes munkasík**; falon/födémen vezetett logika nem cél. A dekoráció és falak ettől külön kezelendők.
- Nem szükséges valódi Redstone-jel vagy a 15 blokkos jelhatár; a topológiát olvassuk ki.
- A CK blokkok **típusos IN/OUT portjai** adják a logikai irányt. A puszta CK Wire hálózat irányítatlan; a motor ebből portkapcsolatokat rekonstruál.
- **Keresztezés ≠ kapcsolat.** Két független vezeték egymáson áthaladhat (külön `CROSS` blokk/állapot vagy hasonló megoldás), és csak explicit csomópont vagy kompatibilis portkapcsolat jelent összekötést. A pontos 2D keresztezés-mechanika prototípust igényel.
- Vezeték-azonosság és élek tárolása: a keresztezéskor ne keveredjenek a logikai hálózatok. A hálózat végpontjait, nyitott portjait és esetleges többértelműségét validálni kell.
- Kézi építés **kötelező élmény**; automatikus útvonalkeresés csak későbbi REFORMAT-hoz.
- Bejárás: START-tól DFS/BFS, IF-ágak külön követése, terminális elemek felismerése, gráfvalidáció, DB mentés és azonos szemantikájú visszaépítés.

## 6. Navigáció – Norton Commander modell

A hangár egy **NC-panel**: belépéskor nem új hangár nyílik, hanem **ugyanaz a fizikai munkatér rendeződik át** az új nézet szerint.

- CK blokk jobb kattintás -> dialog -> TELEPORT/ENTER.
- Hangárban BACK gomb -> vissza az előző kontextusba, lehetőleg a kiinduló blokk közelébe.
- Navigációs verem: hangar_id, view_id, focused_block_id, player_position, navigation_type (és szükséges munkamenet-azonosítók).
- Később **shortcut**: modális, DB-ből felépített teljes alkalmazásfa; bármely kiválasztott elemre ugrás (pl. `Button.leftClickedAction`), majd BACK pontosan az indulási helyre. Nem pusztán hierarchikus `..` navigáció.
- Külön nézetek ajtókkal: PARAMS, RETURN, IMPORTS. Belépéskor ugyanaz a hangár rendeződik át, csak BACK ajtó marad. Ajtó fölötti gomb olvasási célra görgethető modális listát nyithat.
- A navigáció **nem commit**. Mentetlen módosításnál mentés/elvetés/megszakítás döntési pont szükséges.

## 7. Több hangár, későbbi kollaboráció

Egy fejlesztőhöz N hangár; hangárok NC bal/jobb/többedik panelként használhatók. Blokk vagy kijelölt M blokk és belső kapcsolataik COPY/MOVE művelettel átvihetők.

- COPY új identitásokat hoz létre; a kijelölésen belüli kapcsolatok másolódnak.
- Kijelölési határt átlépő élek nem kapcsolódnak automatikusan a célban; nyitott portként jelennek meg.
- MOVE identitás- és hivatkozáskezelése, jogosultságai későbbi döntés.
- Ugyanazon modellnézet több hangárban megjeleníthető; később zárolás, jogosultság, verziózás, ütközéskezelés szükséges.
- Korábbi döntés: a hangár tulajdonosa jogosult menteni; műszak/task commit csak akkor, ha nincs félbemaradt Stage-módosítás. Egy task több Stage-et érinthet; a task készre jelentése tulajdonosi döntés.

## 8. Annotációk és megjegyzések – nem ugyanaz

- **Annotation**: Java/Spring/egyéb framework metaadat, pl. `@Service`, `@Controller`, `@Transactional`, `@GetMapping`; célja lehet Class, Method, Parameter stb. A DB tárolja a típusát, célját, paramétereit, sorrendjét. A hangárban tábla, falikép vagy lebegő blokk mutathatja. Nem control-flow vezeték.
- **Comment**: dokumentációs megjegyzés; Minecraft tábla jó megjelenítés. Stage- vagy blokkhoz kötött komment exportálható; dekoratív szabad tábla nem feltétlenül része a modellnek.

## 9. Képernyő- és rendszertervezés

- `[SCREEN]` nézetben vizuális elrendezés; `[FIELDGROUP]` belsejében mezőblokkok.
- Mező tulajdonságai: label, componentType (`radio`, `checkbox`, `edit`, `multiline`, `dropdown`...), dataType, defaultValue, required, validáció, binding, eseménykezelő.
- Fizikai blokkpozíciók és arányok segíthetik a UI elrendezés generálását; szükséges koordináta-/méretezési szabály.
- Button `leftClickedAction` modellhivatkozásból navigálható Stage.
- Később rendszerek/alrendszerek, middleware interfészek, adatmodellek és architekturális kapcsolatok modellezhetők.

## 10. Generálás – egy modell, több kimenet

A DB-szemantikus modellből külön exporterek készülhetnek:

1. Pszeudokód és később célzott Java/Kotlin/Python/6502 stb. forráskód.
2. Strukturált Markdown promptok (`prompt_01_project.md`, `prompt_02_domain.md`, `prompt_03_api.md`, `prompt_04_ui.md`), Ágens Öcsi számára.
3. Specifikáció, dokumentáció, tesztvázlat, API/UI-definíció.

A generált prompt **nem hiteles modell**, hanem származtatott artefaktum. Pontos kódgeneráláshoz típusok, scope, névfeloldás, szimbólumtábla, célnyelvi szemantika és validáció szükséges. Kezdetben a **pszeudokód-generálás** a reális mérföldkő.

## 11. REFORMAT – későbbi funkció

Gombbal újrarendezi a sután lerakott blokkokat és CK Wire-útvonalakat; **nem változtathatja meg a GraphNode/GraphEdge szemantikáját**. Elkülönített layout engine, derékszögű vezetékvezetés, keresztezéskezelés, ütközésvizsgálat, előnézet, jóváhagyás és visszavonás. Ha nincs biztonságos elrendezés, ne módosítson. A művelet munkameneti változás, nem automatikus commit.

## 12. Vizuális Stage-témák és hangárdekoráció

Az objektumtípus és a szerkesztési nézet külön-külön befolyásolhatja a padló/fal/plafon/világítás megjelenését. Példák:

- CLASS: zöld füves padló, **1–10 legelésző tehén** (`RND(10)` hangulatban), természetes fény.
- METHOD: letisztult ipari padló, jól olvasható fekete vezetékek.
- INTERFACE: fehér, rendezett labor.
- SCREEN: színes UI-tervező stúdió.

Az AmbientProfile determinisztikusan generálható azonosító alapján. A dekorációs entitások nem részei a kódmodellnek; Stage-váltáskor eltűnnek, nem akadályozhatják tartósan a szerkesztést, és nem veszhetnek össze a játékos saját entitásaival. **Későbbi UX-funkció**, nem a mag első mérföldköve.

## 13. Kockázatok és életszerű pozicionálás

**Erős:** hobbi/csapatélmény, oktatás, szemléletes algoritmusmodellezés. **Ígéretes, de bizonyítandó:** strukturált tervezés, promptgenerálás. **Nem elsődleges cél:** teljes értékű általános IDE kiváltása.

Kockázatok: fizikai építés lassúsága; teljes nyelvi szemantika összetettsége; 2D vezetékkeresztezések; kollaborációs konfliktusok; scope creep; Fabric kliens/szerver szinkron; DB-szemantika és világállapot eltérései. A vizuális ötletek ne előzzék meg az első stabil oda-vissza mentési és gráffelderítési próbát.

## 14. Következő javasolt megvalósítási szelet – v0.1 Vertical Slice

**Cél:** egyetlen hangárban kézzel lerakható `START -> IF -> END IF -> STOP` gráf, külön `0`/`1` ágakkal, saját CK Wire-rel. A rendszer felismeri, validálja, menti, visszaépíti és pszeudokódként exportálja **ugyanazt** a gráfot.

Javasolt munkasorrend:

1. **Read-only repo-/runtime-felmérés:** Fabric projekt, jelenlegi blokkregisztrációk, jobb kattintásos dialog, DB, hangár CRUD, migrációs maradványok, tesztek. Ne írjon át semmit.
2. **Mini ADR és adatmodell:** `ModelNode`, `WorkspaceView`, `GraphNode`, `Port`, `GraphEdge`, `Layout`; identitások és invariantok; DB migrációs terv.
3. **CK Wire prototípus:** egyenes, sarok, T, kereszt; elhelyezés/törlés; portkapcsolódás; 2D keresztezés függetlenségének tesztje.
4. **Topológiafelderítő és validátor:** portok, irányok, nyitott ágak, tiltott összekötések, körök és terminális elemek; egységtesztek.
5. **DB round-trip:** SAVE -> hangár ürítése -> LOAD -> azonos szemantikus gráf, változatlan blokkazonosságok és portkapcsolatok; geometria visszaállítása.
6. **Pszeudokód exporter:** IF 0/1 és END IF strukturált megjelenítése, tesztesetekkel.
7. **Később:** Stage ENTER/BACK, PARAMS/RETURN/IMPORTS, strukturális blokkok, dekoráció, shortcut, COPY/MOVE, REFORMAT, promptgenerálás.

### Első elfogadási feltételek

- A játékos ténylegesen **kézzel** helyez el blokkokat és vezetéket.
- A két IF ág külön azonosítható; keresztezés nem okoz rejtett összeköttetést.
- A topológia felismerése **nem függ a Redstone-jelerősségtől**.
- Mentés/újratöltés után a gráf és geometria megegyezik; hibás gráf nem kerül érvényesként commitra.
- A pszeudokód ugyanazt az IF/ELSE struktúrát adja vissza.
- A meglévő hangárjogosultságok és működő Fabric funkciók nem sérülnek.

## 15. Nyitott tervezési kérdések

- Pontos Fabric-projekt struktúra és a Python motor jelenlegi szerepe.
- CK Wire reprezentáció, keresztezés és csomópont, portok geometriai megvalósítása.
- A különböző Stage-típusok közös/eltérő invariánsai.
- BREAK/CONTINUE célfeloldás nested ciklusokban; CASE/SWITCH szemantika.
- A Stage munkapéldány, Save, készre jelentés és task-commit tranzakciós határa.
- Kifejezésmodellek, típusok, scope és nyelvfüggetlen IR.
- Navigációs verem persistálása, multi-hangár ütközések, multiplayer jogosultság.
- Az első kódgenerálási célnyelv és prompt-formátum.

---

## 16. Hogyan használjuk ezt jövőbeli promptokban?

Új beszélgetésben csatold ezt a fájlt, és írd meg, melyik **konkrét mérföldkövön** dolgozunk. A megvalósító ágensnek mindig külön adjuk meg: aktuális repoállapot, változtatási határ, elfogadási tesztek, tiltott mellékhatások. **Ez a dokumentum tervezési iránytű, nem bizonyíték a már implementált funkciókra.**
