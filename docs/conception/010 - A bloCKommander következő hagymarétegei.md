---
title: "CK StoneCoke – System, Artifact, Stage, Repository és Branch"
aliases:
  - "StoneCoke architektúra"
  - "CK virtuális fájlrendszer"
tags:
  - blockommander
  - architecture
  - concept
  - stonecoke
  - obsidian
status: koncepció / nem implementációs specifikáció
created: 2026-11-03
---

# 🥤 StoneCoke – a bloCKommander következő hagymarétegei

> [!abstract] Miről szól?
> Kőkólás architekturális ötletelés a CK jövőbeli virtuális fájlrendszeréről, objektumtípusairól, hangármegjelenítéséről, jogosultságairól, verziókezeléséről, navigációjáról és generálási lehetőségeiről. **Nem megrendelés Öcsinek**, nem a jelenlegi implementáció leírása. Későbbi, kisebb feladatokra bontható gondolatgyűjtemény.

## 0. Kiinduló helyzet és határvonal

A CK-06 fejlesztés során működő Stage-váltás és visszalépés készült. A felhasználó a Minecraftban, három játékossal, három saját hangárban kipróbálta a működést. A PROGRAM blokkos Stage-váltást csak a hangár tulajdonosa végezheti; idegen játékos jelenleg nem szerkesztheti a másik hangárját. Ez megfelel a **jelenlegi**, meghívásos szerkesztést még nem tartalmazó fázisnak.

**Meglévő, illetve korábban implementáltnak jelentett alapok:** Program–Stage szétválasztás, Stage-hierarchia, Python-domain, PostgreSQL-perzisztencia, PROGRAM blokk, Fabric-projekció, Stage-navigáció, programszintű atomi SAVE-szolgáltatás. A teljes, Minecraftból elérhető SAVE-viselkedés és a feliratok állapota külön acceptance-et érdemel.

**Ebben a dokumentumban újként tárgyalt System, virtuális fájlrendszer, általános Artifact, CK-repository és branch nem tekinthető megvalósítottnak.**

## 1. Alapmetafora: a hangár a monitor

> **„A Hangár a monitor. Nem kevesebb és nem több.”**

- A hangár a CK-domain egy kiválasztott részét jeleníti meg fizikailag a Minecraftban.
- Nem a hangár birtokolja a Programot, Stage-et vagy azok mentett tartalmát.
- A hangár lebontása vagy másik helyen történő megépítése nem törölheti a domain-adatokat.
- Egy hangárnak egy időben egy aktív megjelenítése / Stage-e van.
- **Távlati lehetőség:** ugyanazt az objektumot több hangár is megjelenítheti, akár eltérő Stage-en vagy branch-en. A párhuzamos nézetek és szerkesztések szinkronizációja külön tervezendő.

## 2. System: a CK saját `/` gyökere

A **System** olyan, mint a Linux fájlrendszerének `/` gyökere: minden CK-beli munka szervezési kiindulópontja. Nem egyetlen Program gyökér-Stage-e.

Példa (illusztráció, nem rögzített kötelező könyvtárséma):

```text
/
├── home/
│   ├── apa/
│   │   ├── projects/
│   │   │   └── BillingApp/
│   │   ├── docs/
│   │   └── prompts/
│   ├── bela/
│   └── marci/
├── shared/
│   └── family-project/
└── public/
    └── examples/
```

- Játékosonként saját `home` könyvtárnak van értelme.
- A könyvtárak tartalmazhatnak projekteket, programokat, dokumentumokat, promptokat és későbbi egyéb objektumokat.
- A **névtérbeli útvonal** és a **belső Stage-struktúra** külön fogalom.
- Egy objektum mozgatása/átnevezése ne változtassa meg annak stabil belső azonosítóját és Stage-/gráfkapcsolatait.
- Nem eldöntött, hogy a System minden eleme milyen adatmodellben tárolódik, és mi legyen a végleges elnevezés (`Artifact`, `Project` stb.).

## 3. Objektumtípusok és belső Stage-fa

Példák jövőbeli CK-objektumokra:

| Típus | Jellemző tartalom | Lehetséges kimenet |
|---|---|---|
| `Program` | Package, Class, FUN, IF, programlogika | Python-forrás vagy alkalmazás |
| `Document` | Fejezetek, szöveg, leírások | Markdown |
| `Prompt` | Prompt-részek, alpromptok, szerkezet | Markdown / prompt-szöveg |
| `Directory` | Szervezési egység, gyermekobjektumok | Nem feltétlenül generálható |

Az önállóan szerkeszthető objektum belsejében lehet saját Stage-hierarchia. **A CK-06 jelenlegi perzisztens `Program` fogalma és a Minecraftban lerakott `PROGRAM` blokk nem automatikusan ugyanaz**: az utóbbi ma gyermek `PROGRAM_BODY` Stage-et nyit. A fogalmak összehangolása külön tervezést igényel.

### Név és leírás: három külön mező

1. **Szimbolikus név:** például `calculateTotal` – generált kód azonosítója.
2. **Megjelenített Stage-név:** például `Számla végösszegének kiszámítása` – navigátor, ajtó fölötti tábla.
3. **Leírás:** például `ÁFA és kedvezmények alkalmazása` – dokumentáció, lehetséges komment/docstring.

A megjelenített név alapértelmezésben generálódhat a szimbolikus névből, de a játékos felülírhatja. A technikai UUID ettől független és stabil.

## 4. Hangárfelirat, Stage Navigator, CK Explorer

### Fizikai tájékozódás

- Ajtó fölött mindig látható felirat az aktuális Stage-ről.
- Lehet típus, név, útvonal, esetleg mentési állapot.
- Nem kell a falra kitenni az összes gyermek Stage-et: egy Class alatt akár 150 metódus is lehet.
- A Stage-típushoz kötött környezetdekoráció (mező, tehenek, alpakkák stb.) játékos, későbbi ötlet.
- A blokkok mellett **felülről olvasható, vízszintes névtábla** lehetne hasznos, mert a játékos kreatív repülés közben felülről nézi a folyamatábrát. A vanilla táblák korlátai miatt egyedi CK-renderelés vagy CK névtáblablokk lehet célszerű; a technika nincs eldöntve.

### Két külön navigátor

- **CK Explorer:** a System fájlrendszerében projektek, dokumentumok, promptok és más objektumok keresése/megnyitása.
- **Stage Navigator:** a már megnyitott objektum Stage-fájában navigálás, keresés, **random-access Stage-váltás**; nem csak szülő→gyermek→szülő bejárás.
- Előhívás: gomb, parancs vagy CK-eszköz; modális, dinamikus fa-UI.
- A random-access navigáció ugyanazt az autoritatív, biztonságos Stage-switch mechanizmust használja, mint a PROGRAM blokk; ne legyen második, eltérő projekciómotor.
- A fizikai PROGRAM blokkos bejárás ettől még megmaradhat.

## 5. Jogosultságok: player, group, láthatóság

A System elemeinek hozzáférése külön szabályozható:

- Tulajdonos / konkrét player.
- Csoport (`group`).
- Nyilvános vagy megosztott elérés.
- Jogosultságok lehetséges öröklődése könyvtár- és Stage-hierarchián.

Lehetséges jogdimenziók (még **nem végleges ACL-szerződés**):

| Jog | Jelentés |
|---|---|
| READ | Böngészés és megtekintés |
| WRITE | Engedélyezett tartalmi szerkesztés |
| MANAGE | Hozzáférés-kezelés és tulajdonosi műveletek; SAVE/PUBLISH pontos hozzárendelése még tervezendő |

A hiteles jogosultságvizsgálat a Python/backend domain feladata; a Fabric csak érvényesíti és megjeleníti az eredményt.

### Közös szerkesztés mint alkalmi esemény

A normál használat: mindenki a saját hangárjában dolgozik. A közös építés ritkább, társasági esemény – „szaunafelöntés”, porondmesterrel.

- A meghívott szerkesztők a hangár **közös munkapéldányát** módosítják.
- A tulajdonos a porondmester: ő vezényli a hangár Stage-váltását, SAVE-jét és PUBLISH-ét.
- A meghívott nem vált önállóan Stage-et a közös hangárban.
- Meghívás lehet Stage-szintű, leszármazottakra öröklődő; visszavonás azonnal hat.
- A meghívott módosításai a közös munkapéldányban megmaradnak, ha kilép.
- A Stage-váltás pillanatában késve érkező, régi Stage-hez tartozó módosításokat a backendnek biztonságosan el kell utasítania.
- Társasági protokoll: „Három… kettő… egy… GO!”; nem szükséges szavazórendszer vagy bonyolult együttműködési UI.

## 6. Repository: opcionális verziókezelési határ

**A repository nem egyenlő a Programmal vagy a Prompttal.** Ahogy a valódi Git-repót sem a fájltípus határozza meg, a CK-ban is tetszőleges alkalmas objektumnál vagy könyvtárnál kezdődhet verziókezelt tartomány.

- Egy Program lehet repógyökér.
- Egy szerteágazó Prompt is lehet repógyökér.
- Egy projektkönyvtár alatti több Program, Document és Prompt is tartozhat egy repóhoz.
- Objektum adatlapján: **„Repository inicializálása itt”** (`git init`-hez hasonló fogalom), nem automatikus repó minden PROGRAM blokknál.
- **Döntés:** a CK-repositorykat **PostgreSQL-ben** kezeljük; nem kell valódi `.git` mappa, Git-integráció vagy `git push/pull`.
- A verziókezelés a CK strukturált domainobjektumait követi, nem a Minecraft-világ fizikai pillanatképeit.
- Egymásba ágyazott repositoryk hosszú távon lehetségesek, de a határok és a kettős verziózás elkerülése külön szabályozandó.

## 7. Branch, working copy, SAVE, COMMIT, PUBLISH

A branch **a repositoryhoz**, nem egyetlen Stage-hez tartozik. Egy branch a repóhoz tartozó teljes verziózott objektumhalmaz fejlődési ága.

| Fogalom | Tervezett jelentés |
|---|---|
| Repository | Önálló verziókezelt tartomány |
| Branch | A repó egyik fejlődési ága, például `main` vagy `feature/new-tax` |
| Working copy | Egy adott ágon folyó, még nem commitolt munkaállapot |
| SAVE | Tartós munkapéldány-mentés PostgreSQL-be, **nem automatikus commit** |
| COMMIT | Rögzített, történeti snapshot szerzővel/üzenettel/szülővel |
| PUBLISH | Kiválasztott változat kiadása; **nem automatikusan merge** |

### Több monitor, több branch

Elvi példa: Apa hangárja ugyanazon objektum `feature/new-tax` branchét mutatja, Béla hangárja a `main`-t. Egyik hangár Stage-váltása nem rendezheti át a másik hangárját. Ha Béla Apa hangárjában meghívott szerkesztő, ott Apa aktív branchének közös munkapéldányán dolgozik.

**Még nyitott:** külön hangárokban megnyitott azonos branch nézeteinek szinkronizálása; párhuzamos szerkesztési szabályok; branch-létrehozás és -váltás; merge-konfliktusok; commit-adatmodell; repóhatárok.

### CK-merge mint térbeli diff

Később két branch eltérése akár Minecraftban is láthatóvá válhat: hozzáadott/eltávolított blokkok, eltérő GraphEdge-ek, vezetékútvonalak, gyermek Stage-ek. **Ez távlati ötlet, nem mostani feladat.**

## 8. Generálás, build, futtatás

Három külön művelet:

1. **GENERATE:** CK-struktúrából kimeneti fájl, például Python `.py`, Markdown `.md`.
2. **BUILD:** ellenőrzés, teszt, csomagolás vagy dokumentumexport.
3. **RUN / DEPLOY:** engedélyezett célkörnyezetben futtatás vagy telepítés.

A Minecraftban akár külön CK Build Station és karok jeleníthetik meg ezeket. A Python webhook már futó szolgáltatásként fogadná a kérést; nem minden BUILD húzásra indulna új webhook szerver.

**Biztonsági alapelv:** a build/run/deploy nem jelenthet tetszőleges parancsfuttatást. Engedélyezett műveletek, célkönyvtárak, jogosultságok, izoláció és audit szükséges.

A CK-gráf **közvetlen interpreteres végrehajtása** ettől külön jövőbeli funkció. Nem azonos a generált Python alkalmazás buildelésével.

## 9. Közeli, kis Öcsi-feladatok – csak későbbi bontásra

A jelenlegi heti kvótától független, lehetséges sorrend:

| Morzsa | Terjedelem | Megjegyzés |
|---|---|---|
| CK-06/A7 SAVE-állapot és tábla | Kicsi–közepes | A programszintű atomi mentés már implementáltnak jelentett; előbb ellenőrizni, mi hiányzik ténylegesen a Fabric megjelenítésből és acceptance-ből |
| PROGRAM/Stage névadás | Közepes | Szimbolikus név, display name, description külön; migrációt és visszafelé kompatibilitást vizsgálni |
| Ajtó fölötti Stage-felirat | Kicsi–közepes | Aktuális Stage, esetleg típus és mentési állapot |
| Stage Navigator felderítés | Kicsi, read-only | Fabric GUI lehetőségek, meglévő Stage-switch API újrahasznosítása; 150+ elem és keresés |
| ConnectionDestroyer | Közepes | Szándékos, megerősített GraphEdge+Route+Wire eltávolítás; véletlen fizikai törés továbbra sem töröl domain-élt |
| CK Explorer / System adatmodell | Előbb külön ADR | Nagy architekturális munka; nem azonnali kódolási feladat |
| Repository / Branch adatmodell | Előbb külön ADR | Nagy, önálló tervezési program; nem „egy promptban implementáld” |
| Első GENERATE prototípus | Külön kicsi spike | Minimális Program→`.py` vagy Prompt→`.md`, teljes build/deploy nélkül |

### Öcsi munkamódja

- Egyetlen világosan körülhatárolt cél feladatonként.
- Először read-only felderítés, ha a szerződések bizonytalanok.
- Meglévő domainmechanizmus újrahasznosítása, párhuzamos „második motor” nélkül.
- Modellezési és konkurenciakérdésekhez indokolt esetben magasabb gondolkodási erőfeszítés; rutin implementációhoz takarékosabb beállítás.
- A nagy build/deploy és Minecraft acceptance lehet emberi lépés, hogy az agent-kvóta ne menjen el ismételt teljes tesztkörökre.

## 10. Nyitott döntések – szándékosan nyitva

- `System`, `Directory`, `Artifact`, `Program`, `Prompt` végleges fogalmi és adatbázis-határai.
- Milyen elemeknél lehet Stage-fa, és hol kezdődik egy önálló mentési egység?
- A repository init UI-ja, tulajdonjoga, beágyazási szabályai.
- Branch/working copy/commit pontos perzisztenciája és konkurenciamodellje.
- A jelenlegi `PUBLISH` és a jövőbeli repository-kiadás viszonya.
- ACL és Stage-szintű meghívások összefésülése.
- Vízszintes blokkfeliratok technikai megvalósítása.
- Több hangár ugyanazon objektum/branch párhuzamos megjelenítése.
- Generátorok célformátumai, build- és deploy-sandbox.

---

> [!quote] StoneCoke-zárszó
> A **System** a `/`, a **hangár** a monitor, a **Stage** a szerkeszthető belső szerkezet, a **repository** opcionális verziókezelt tartomány, a **branch** pedig annak egyik fejlődési ága. A CK-t nem kell Gitnek álcázni; a PostgreSQL is tud hagymát tárolni. 🧅
