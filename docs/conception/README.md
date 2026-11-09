# CK koncepciótár

A koncepciótár a bloCKommander gondolkodástörténete: szándékok, dokumentált döntések, alternatívák és még meg nem valósult ötletek kereshető gyűjteménye. **Nem automatikus fejlesztési utasítás és nem implementációs jelentés.**

Index és státuszfelmérés: **2026-11-09**, CK-08/DOC. Vizsgált forrásbaseline: **main / b98b995eff3a0702a0b5602e54695598e6ccfed1**, G3/B2 + DIAG2. A dokumentációs GO kizárólag e három kísérőfájl létrehozását engedélyezte; új funkcióra nem ad felhatalmazást.

## Belépési pontok

- [Állításonkénti státusz és implementációs bizonyíték](STATUS.md).
- [Nyitott döntések, eltérések és alternatívák](OPEN-DECISIONS.md).
- [Jelenlegi architekturális projektguide](../../AGENTS.md).
- [Program–Stage–Hall architekturális korrekció](../CK-06A1R-program-stage-hall-architecture.md).
- [Hall Selection backend](../CK-07A1.3-G3-B1-hall-selection.md) és [Fabric-projekció](../CK-07A1.3-G3-B2-hall-selection-projection.md).
- [CK-08/A fejlesztésifront-felderítés](../CK-08A-fejlesztesi-frontok-es-blokkrendszer-felderites.md): a most feltárt tekercsek néhány korábbi UNKNOWN megállapításához új döntési forrást adnak; a jelentést ez a műszak nem írja át.

## A 11 eredeti tekercs indexe

Az alábbi sorszámok a fájlnevek azonosítói, nem implementációs prioritások. A dátum a szöveg/metaadat dátuma; a hiányzó dátumot nem következtetjük ki a fájlsorszámból vagy időbélyegből. A „jelleg” nem az egész dokumentum implementációs státusza.

| ID | Eredeti fájl | Belső cím | Dátum | Jelleg és téma |
|---|---|---|---|---|
| 001 | [001 - Post Fabric world utáni eszmélés.md](001%20-%20Post%20Fabric%20world%20ut%C3%A1ni%20eszm%C3%A9l%C3%A9s.md) | bloCKommander – koncepció | 2026-10-22 | Átfogó koncepció, nem implementációs specifikáció. ModelNode/Stage/Hall, Graph/Layout, blokkok, navigáció, generálási vízió. |
| 002 | [002 - az Assembly világ (abadonded mansion).md](002%20-%20az%20Assembly%20vil%C3%A1g%20%28abadonded%20mansion%29.md) | bloCKommander – Assembly mód: ötletmagok | 2026-10-23 | Ötletelés. CPU-profilok, komponensszerződés, assembly Stage és backend; nem tesztelt generátor. |
| 003 | [003 - Fogalmak.md](003%20-%20Fogalmak.md) | Fogalmak (fájlnévből; nincs belső cím) | UNKNOWN | Magyarázó jegyzet. Snapshot, immutable, atomic, invariant; a Wire/Atomic-példa külön tisztázandó. |
| 004 | [004 - Stage working copy, publikáció és későbbi csapatmunka.md](004%20-%20Stage%20working%20copy%2C%20publik%C3%A1ci%C3%B3%20%C3%A9s%20k%C3%A9s%C5%91bbi%20csapatmunka.md) | bloCKommander – Stage working copy, publikáció és későbbi csapatmunka | 2026-10-24 | Koncepció és dokumentált döntések. Közös munkapéldány, SAVE WORK/PUBLISH, stabil verzió, részfára öröklődő meghívás. |
| 005 | [005 - Stage, feature branch, DRAFT és publikálás.md](005%20-%20Stage%2C%20feature%20branch%2C%20DRAFT%20%C3%A9s%20publik%C3%A1l%C3%A1s.md) | bloCKommander – Stage, feature branch, DRAFT és publikálás | 2026-10-26 | Elfogadottként rögzített elvek és nyitott kérdések. DRAFT-autosave, több feature branch, owner által vezérelt Hall-vetítés, publikálás. |
| 006 | [006 - CK-blokkok vizuális szemantikája és portjelölései.md](006%20-%20CK-blokkok%20vizu%C3%A1lis%20szemantik%C3%A1ja%20%C3%A9s%20portjel%C3%B6l%C3%A9sei.md) | CK-05/B – CK-blokkok vizuális szemantikája és portjelölései | UNKNOWN | Koncepció és felderítési javaslat. Oldalsó portjelölés, felső folyamatábra, orientáció és resource-alapú megjelenítés. |
| 007 | [007 - portok, FUN, CALL, Interface.md](007%20-%20portok%2C%20FUN%2C%20CALL%2C%20Interface.md) | bloCKommander – CK-05/B: portok, FUN/CALL, Interface | 2026-10-29 | Dokumentált döntések és nyitott kérdések. FOR-portok, FUN ≠ CALL, paraméter-/return Stage, Interface, FUNCTION/PROCEDURE. |
| 008 | [008 - ProgramBlock, IF és WAGO.md](008%20-%20ProgramBlock%2C%20IF%20%C3%A9s%20WAGO.md) | bloCKommander – ProgramBlock, IF és WAGO | 2026-10-30 | Implementáció előtti koncepció, döntésösszegzéssel. ProgramBlock-scope, IF Condition Stage, CONTROL merge/WAGO és modulösszeépítés. |
| 009 | [009 - CK-06 architektúra, nagykockásan.md](009%20-%20CK-06%20architekt%C3%BAra%2C%20nagykock%C3%A1san.md) | bloCKommander – CK-06 architektúra, nagykockásan | UNKNOWN | Történeti A1 célarchitektúra. Rétegek, Program/Stage/Hall szétválasztás, aggregate SAVE, kétfázisú Stage-váltás. |
| 010 | [010 - A bloCKommander következő hagymarétegei.md](010%20-%20A%20bloCKommander%20k%C3%B6vetkez%C5%91%20hagymar%C3%A9tegei.md) | StoneCoke – a bloCKommander következő hagymarétegei | 2026-11-03 | Koncepció; néhány kifejezett döntéssel. System/Artifact, Explorer, PostgreSQL-alapú CK-repository, branch és COMMIT/PUBLISH. |
| 011 | [011 - tekercs — Monty, Condition Stage és a barkácsolható logika.md](011%20-%20tekercs%20%E2%80%94%20Monty%2C%20Condition%20Stage%20%C3%A9s%20a%20bark%C3%A1csolhat%C3%B3%20logika.md) | 11. tekercs — Monty, Condition Stage és a barkácsolható logika | 2026-11-09 | Koncepció és döntésnapló, nem implementációs specifikáció. BOOLEAN CStage, Expression/kapuk/RESULT, crafting, többcellás kapuk, Logic Wire, web és YAML. |

## Státuszok értelmezése

| Státusz | Jelentés |
|---|---|
| IMPLEMENTED | Konkrét production kód/adatút igazolja az állítást; önmagában nem Minecraft-acceptance. |
| PARTIAL | Csak megnevezett rész igazolt; a hiányt külön felsoroljuk. |
| ACCEPTED | A forrás kifejezetten döntésként/elfogadott elvként rögzíti; még nem implementált képesség és nem új implementációs GO. |
| PROPOSED | Ötlet, alternatíva vagy nem véglegesített elképzelés. |
| SUPERSEDED | Konkrét későbbi döntés felülírta vagy lezárta a korábbi állítást; puszta időrend nem elegendő. |
| CONFLICT | Összehangolatlan állítások vagy koncepció–implementáció eltérés. **Nem automatikusan implementációs hiba.** |
| UNKNOWN | A rendelkezésre álló bizonyíték nem elég a megállapításhoz. |

A [STATUS](STATUS.md) minden állításnál külön megadja a koncepcionális forrást és a tényleges implementációt. Az eredeti frontmatter „status” mezőjét nem fordítjuk át automatikusan egy teljes dokumentumra érvényes ACCEPTED vagy IMPLEMENTED minősítéssé.

## Olvasási útmutató

1. **Alapfogalmak:** 001 → 003 → 009, majd a jelenlegi CK-06/A1-R architektúra. A 009 „még tervezett” állapotjelzése az A1 korabeli pillanatkép.
2. **Mentés, munkaállapot, jogosultság:** 004 → 005 → 010; közben a STATUS mentési és WorkingCopy-sorai. A mai explicit Program-SAVE nem azonos a koncepcionális DRAFT-autosave/PUBLISH rendszerrel.
3. **Programozási blokkok és vizuális szerződés:** 006 → 007 → 008. A FUN/CALL szétválasztás, FOR-portok és ProgramBlock-scope dokumentált koncepcionális irány; production készültségüket külön kell ellenőrizni.
4. **Condition Stage:** 008 → 011. A CONTROL merge/WAGO és a BOOLEAN kapukompozíció **külön domainfogalom**, amíg kifejezett architekturális döntés nem kapcsolja össze őket.
5. **Távlati környezetek:** 002 assembly-ötletek, 010 System/repository, 011 Web Workbench/YAML/layout. Ezek nem soron következő implementációs feladatok.
6. Konkrét fejlesztés előtt: aktuális baseline, releváns státuszállítás, [nyitott döntés](OPEN-DECISIONS.md), majd külön jóváhagyott domain/API/acceptance-szerződés.

## Koncepció és működő rendszer

- A jelenlegi Program/Stage/Graph-szolgáltatás nem valósít meg minden tekercsbeli képességet.
- A koncepcióban szereplő FUN, CALL, branch, CStage vagy WAGO neve nem bizonyítja runtime regisztrációját.
- Kód, automatizált teszt, korábbi futási jegyzőkönyv és Minecraft-megfigyelés külön bizonyítékfajták.
- A fizikai Wire-törés ma explicit workspace-módosítási úton halad. A fizikai sérülésre vonatkozó koncepcióktól való eltérés [nyitott szemantikai kérdés](OPEN-DECISIONS.md#dec-001), nem ebben a dokumentációs műszakban megállapított bug.
- Nincs új döntés IR-ről, kódgenerálásról, branch-adatmodellről vagy a jelen SAVE átértelmezéséről.
- A CK alkalmazáson belüli repository/branch fogalma különbözik e forrásrepository Git-verziókezelésétől.

## Karbantartási alapelvek

1. Az eredeti 11 Obsidian-tekercs tartalma és fájlneve ebben a rendezésben változatlan. Nem normalizálunk frontmattert, címeket vagy elírásokat.
2. Új tekercs csak kifejezett dokumentációs feladatból kerüljön ide. Kapjon indexsort; egy újabb ötlet ne írja felül csendben a korábbi döntést.
3. A státuszjegyzékhez állításazonosító, konkrét forrás és baseline szükséges. A „kód már tudja” mellé implementációs bizonyítékot kell adni.
4. Emberi döntés után az OPEN-DECISIONS-be kerüljön a döntés forrása/dátuma/határa; csak ezután módosuljon a kapcsolódó státusz. A technikai specifikáció és az implementáció külön feladat.
5. Linkek relatívak, repositoryn belüliek; szóközös/ékezetes fájlnevek URL-kódoltak. Átnevezés helyett előbb hivatkozás- és indexellenőrzés szükséges.
6. A dokumentumok repositoryba helyezése nem azonos Git-committal. E műszak nem hoz létre commitot.

## Megőrzött forrásjellemzők

- A 001 jelenleg byte-azonos a gyökérben levő [koncepcio.md](../../koncepcio.md) fájllal. Egyiket sem töröljük; későbbi eltérésnél az authoritative szerkesztési hely külön döntés.
- A 003 nem tartalmaz belső címet vagy frontmattert; az index címe a fájlnévből származik.
- A 007 elején ugyanaz a YAML-frontmatter kétszer szerepel. A rendezés ezt nem javítja.
- A 006 és 009 keltezése nem igazolt; UNKNOWN jelöléssel szerepelnek.
- Az eredeti tekercsek témákra/címekre szövegesen utalnak, de a felmérés nem talált bennük Markdown- vagy Obsidian-wikilinket. Az új kereshető kapcsolatrendszert a kísérődokumentumok adják.

**Határ:** a koncepciók segítik a szándék megértését; a végrehajtás alapja a jóváhagyott architektúra, a domain-szerződés és a kifejezett feladat.
