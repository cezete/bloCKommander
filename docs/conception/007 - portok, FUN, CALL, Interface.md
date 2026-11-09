---
title: "bloCKommander – CK-05/B portok, FUN/CALL és Interface koncepció"
date: 2026-10-29
tags: [bloCKommander, Minecraft, Fabric, CK-05, koncepció, Stage, FUN, CALL, Interface]
status: "Koncepció – elfogadott döntések és nyitott kérdések"
---
---
title: "bloCKommander – CK-05/B portok, FUN/CALL és Interface koncepció"
date: 2026-10-29
tags: [bloCKommander, Minecraft, Fabric, CK-05, koncepció, Stage, FUN, CALL, Interface]
status: "Koncepció – elfogadott döntések és nyitott kérdések"
---

# bloCKommander – CK-05/B: portok, FUN/CALL, Interface

> [!abstract] Cél
> A 2026. október 29-i tervezőbeszélgetés döntéseinek megőrzése. Ez **koncepciódokumentum**, nem Öcsinek kiadott implementációs feladat. A jóváhagyott döntések és a még nyitott kérdések elkülönülnek.

## 1. Kiinduló helyzet

- A Minecraftban már működik a CK Wire lerakása, a szomszédos vezetékek összeállása, a kanyar és az explicit CROSS megjelenítése.
- START → CK Wire → STOP összeköttetés után az Élbekötés hatására a vezeték **zöldre váltott**. Ez játékbeli vizuális siker; az adatbázisbeli GraphEdge/EdgeRoute darabszámot ez a próba önmagában nem igazolja.
- A START és STOP jelenleg teljes **1×1×1 Minecraft-blokkok**, látható portjelölés nélkül.
- A CK Wire jelenleg a blokk középmagasságában fut; a cél a padlóhoz közelebbi megjelenítés.
- A Python Graph domain marad a topológia és a kapcsolatok hiteles forrása; Fabric nem épít második gráfmotort.

## 2. Fizikai portok – elfogadott alapelvek

1. A port **csak vizuális jelölés** a CK-blokk oldalán; nem külön Minecraft-blokk vagy önálló világobjektum.
2. A portjelölések és a **lejjebb vitt CK Wire azonos magasságban** legyenek.
3. A blokk lerakásakor a **domináns port a lerakó játékos felé néz**. A tájolás lerakás után rögzített; a blokk nem fordul a játékos után.
4. Nem létezik világkoordinátákhoz kötött „mindig bal oldali IN” vagy „mindig jobb oldali OUT”: az oldalak a blokk **helyi tájolásához**, a lerakó játékos nézőpontjához igazodnak.
5. A **START** domináns portja **OUT**; a **STOP, IF, FOR, CALL** és általában a végrehajtható blokkok domináns portja **IN**.
6. A megjelenített port és a Python által elfogadott csatlakozási irány nem mondhat egymásnak ellent. A tájolás adatainak átadását / kezelését a jelenlegi szerződéshez kell igazítani.

### Elfogadott portkiosztások

A nézőpont minden sorban: **a lerakó játékos a blokk előtt áll**, és a domináns portot nézi.

| Blokk | Elöl (játékos felé) | Balra | Jobbra | Hátul |
|---|---|---|---|---|
| START | **OUT** | – | – | – |
| STOP | **IN** | – | – | – |
| IF | **IN** | **OUT 0** | **OUT 1** | – |
| FOR | **IN** | **BODY OUT** | **EXIT** | **BODY IN** |
| CALL | **IN** | – | – | **OUT** (javasolt, még nem külön jóváhagyott oldal) |

**FOR jelentése:** `IN → FOR → BODY OUT → ciklustest → BODY IN → FOR`; amikor a ciklus befejeződik, `EXIT`-en folytatódik. A BODY IN **visszatérés** a ciklusvezérlőhöz, nem új belépés kívülről.

> [!note] Nem véglegesített részlet
> A CALL-nál az **IN és OUT szükségessége** eldőlt, de az OUT pontos oldala nem kapott olyan külön jóváhagyást, mint az IF és FOR portjai. A „hátul OUT” jelenleg kézenfekvő javaslat.

## 3. FUN és CALL – két külön blokkfajta

**FUN = függvénydefiníció / implementáció. CALL = függvényhívás.**

Példa:

```text
FUN numeric sin(numeric angle) { ... }

CALL a = sin(60)
CALL b = sin(30)
```

- A két CALL **ugyanarra** a FUN-definícióra hivatkozik. Száz hívás nem jelent száz implementációt.
- A CALL a hívó folyamatábrában vezérlési **IN és OUT** porttal rendelkezik. A függvény argumentumait **nem huzalozzuk külön CK Wire-rel**.
- A CALL szerkesztője jobb kattintással nyílik meg; a játékos **csak létező, elérhető FUN-szerződést** választhat.
- A CALL szerkesztője **nem hoz létre új FUN-t**. Új függvényhez a játékos elmegy annak tulajdonosához (például egy importált class Stage-éhez), ott létrehozza, visszatér, majd kiválasztja a CALL-ban.
- Az elérhető FUN-ok között importált class-ok metódusai is lehetnek, a láthatósági/szerződéses szabályok szerint.
- A CALL-nak a hívott FUN **azonosságára / szerződésére** kell hivatkoznia, nem egy könnyen ütköző puszta névszövegre.

## 4. Paraméterek és Return Stage-ek

A paraméterek szerkesztése **Stage-alapú**, nem külön adatvezetékes.

### Paraméter Stage

- Speciális Stage, amelyen a `START` és `STOP` között **sorrendbe kapcsolt placeholder blokkok** jelennek meg.
- A placeholder sor a paraméterek sorrendjét tükrözi.
- Hívási oldalon a helyőrzőkbe a hívó Stage-en látható **változó, literál vagy konstans** helyezhető.
- A FUN törzsében az értékadás és más műveleti blokkok hivatkozhatnak a paraméteradatokra.

### Return Stage

- Szintén speciális Stage; `START` és `STOP` között rendszerint **egy visszatérési placeholder** szerepel.
- A visszatérési adat **neve, típusa és esetleges alapértelmezett értéke** szerkeszthető a megfelelő szerződéses keretek között.

### Navigáció

Az eredeti ötlet szerint a FUN belső Stage-ében **két ajtó** vezet a Paraméter és Return Stage-hez; a szerkesztőablakból a FUN belseje is megnyitható. A FUN-definíció és CALL szétválasztása után a pontos navigációs tulajdonviszonyt még finomítani kell:

- a **FUN-definícióhoz** tartozik a paraméterek és visszatérés **szerződése** (nevek, típusok, sorrend, defaultok);
- az egyes **CALL-okhoz** tartoznak a konkrét **argumentumértékek**;
- az implementáció törzse a szerződésben deklarált adatokra hivatkozik.

> [!warning] Nyitott UI-részlet
> A CALL konkrét argumentumainak Stage-e, a FUN-definíció szerződéses Paraméter Stage-e és a FUN implementációjának szerkeszthető másolata **nem ugyanaz a Stage**. Az ajtók, modális ablakok és visszalépési útvonalak pontos térképe még tervezendő.

## 5. Interface: a szerződés alapján CALL már létrehozható

- Az **Interface** deklarálhat FUN-szerződéseket: név, paraméterlista, típusok, visszatérési szerződés.
- **Ha egy FUN-szerződés elérhető és kiválasztható, a CALL elhelyezhető és menthető**, akkor is, ha az implementáció még nincs kész.
- A hiányzó vagy nem megfelelő implementációt **fordításkor vagy futáskor** kell jelezni, a később választott végrehajtási modell szerint.
- **Szerződés érvényessége ≠ implementáció végrehajthatósága.**
- A statikus/dinamikus metódusfeloldás, az öröklődés és a tényleges futtatási kötés részletei későbbi tervezési témák.

## 6. Interface → implementáció: Stage-sablon felajánlása

Ha valaki egy Interface-ben már felépítette a FUN paraméter- és Return Stage-jeit, az implementáló class-ban **nem kell újra kézzel felépíteni** a placeholdereket.

Példa:

```text
INTERFACE IMatematika
  FUN osszead
    Paraméter Stage:
      START → [a: numeric] → [b: numeric] → STOP
    Return Stage:
      START → [eredmeny: numeric] → STOP
```

Implementáláskor a rendszer **felajánlja** a FUN vázának létrehozását:

1. Az Interface szerződéses Stage-jei alapján létrejönnek az implementáció **saját, szerkeszthető Stage-másolatai**.
2. Átkerül a paraméterek száma, sorrendje, neve és típusa, valamint a visszatérési szerkezet.
3. A másolatok **megőrzik a hivatkozást** az eredeti Interface FUN-szerződésére.
4. Az implementáció végrehajtási törzse külön építhető meg.
5. Ha az Interface később változik, a rendszer **eltérést jelez**, és **kifejezett szinkronizálást ajánl fel**; nem írja át észrevétlenül az implementáló Stage-eket.

A szerződésnek való megfelelés ellenőrizhető, miközben az implementáló saját munkapéldánya önállóan szerkeszthető.

## 7. Default paraméterek, újrafelhasználás, overloading

Három külön fogalom:

- **Újrafelhasználás:** több CALL hivatkozik ugyanarra a FUN-implementációra.
- **Default paraméter:** egy hiányzó opcionális argumentumot a szerződésben megadott alapérték tölt ki; ez önmagában **nem overloading**.
- **Overloading:** azonos névvel több, paraméterlistában vagy típusban eltérő FUN-szerződés létezhet. Ennek feloldási szabályai **nincsenek még kidolgozva**.

## 8. Megvalósítási határok és nyitott döntések

### CK-05/B közeli, gyakorlati cél

- Látható portjelölések a teljes méretű CK-blokkokon.
- CK Wire padlóhoz közelebbi modellje, a portokkal azonos magasságban.
- Lerakáskori játékos felé tájolt domináns port.
- START, STOP, IF és FOR elfogadott kiosztásának vizuális leképezése.
- A Python topológiai szerződés és a látható portirány összhangjának ellenőrzése; **ne épüljön külön Kotlin gráfmotor**.

### Későbbi külön témák

- CALL/FUN/Interface blokkok tényleges CRUD-ja, Stage-típusai és navigációja.
- A CALL OUT oldalának végleges jóváhagyása.
- A függvénydefiníció, hívási argumentum-Stage és implementációs szerződésmásolat pontos kapcsolata.
- Importok, class-scope, láthatóság, Interface implementációfeloldás.
- Fordítás és futtatás hibakezelése; overloading és defaultparaméter-feloldás.
- Többféle visszatérési érték, opcionális return és további típusok szabályai. A FUNCTION/PROCEDURE alapkülönbségét a 9. szakasz rögzíti.

> [!success] Tervezési alapelv
> **A Minecraftban a vezérlés útját huzalozzuk. A paraméterek Stage-ekben szerkeszthető adatok. A FUN egyszer definiált, a CALL sokszor hivatkozhat rá. Az Interface szerződését vizuálisan is újrahasznosíthatjuk, de a felhasználó munkáját nem írjuk át csendben.**


## 9. Kiegészítés – FUNCTION és PROCEDURE (2026-10-29)

> [!note] Enterprise 128 / IS-BASIC ihletés
> A FUNCTION és CALL elnevezések kapcsán felmerült a klasszikus BASIC-ek függvény- és eljárásfogalma. A bloCKommander saját, egységes FUN-modelljében a **PROCEDURE visszatérési érték nélküli FUNCTION**.

### Elfogadott döntés

- **FUNCTION:** van visszatérésiérték-szerződése; a FUN belső Stage-éből elérhető a **Paraméter** és a **Return** ajtó/Stage.
- **PROCEDURE:** nincs visszatérési értéke (`VOID`); a **Paraméter** ajtó továbbra is elérhető, a **Return ajtó nem nyílik meg**, nincs visszatérésiérték-Stage.
- **CALL:** mindkettőt meghívhatja; ugyanúgy rendelkezik vezérlési **IN** és **OUT** porttal. A hívott kód befejezése után a vezérlés a CALL OUT-ján folytatódik.
- A **visszatérés a hívóhoz** nem azonos a **visszatérési érték átadásával**. A PROCEDURE is visszatérhet a hívóhoz, csak értéket nem ad át.
- Az Interface FUN-szerződései egyaránt deklarálhatnak értéket visszaadó FUNCTION-t és `VOID` PROCEDURE-t; az implementációhoz felajánlott Stage-sablon ennek megfelelően tartalmaz vagy nem tartalmaz Return Stage-et.

Példa (koncepcionális jelölés):

```text
FUNCTION sin(angle: numeric) -> numeric
PROCEDURE kiir(szoveg: string)  // VOID

CALL sin(60)
CALL kiir("Helló!")
```

> [!important] Határ
> A PROCEDURE-nek **nem kell külön végrehajtási mechanizmus vagy külön CALL-blokk**. A pontos korai kilépési (`RETURN` érték nélkül) és futtatási szabályokat később kell meghatározni.
