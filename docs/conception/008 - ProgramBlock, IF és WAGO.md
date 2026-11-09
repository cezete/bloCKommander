---
title: "bloCKommander – ProgramBlock, IF és WAGO: Stage-ek, scope és vezérlési gráf"
tags:
  - bloCKommander
  - koncepció
  - stage
  - programblock
  - if
  - wago
  - graph
status: "koncepció – implementáció előtt"
date: 2026-10-30
---

# bloCKommander – ProgramBlock, IF és WAGO

> [!abstract] Vezérelv
> A Minecraftban fizikailag megépített program és a Python által jóváhagyott logikai gráf két külön állapot. **A fizikai szerkezet megváltozása önmagában nem írhatja át a jóváhagyott gráfot.** Az eltérést jelezni kell, a rendezés explicit játékosművelet.

## 1. Kiterített programfolyam vagy ProgramBlock

Egy elágazás (`IF[0]`, `IF[1]`), illetve más vezérlési pont után két egyenrangú szerkesztési mód létezik:

1. **Kiterített program:** egymás után elhelyezett végrehajtható blokkok (`SET`, `CALL`, `FOR`, `IF` stb.) a jelenlegi Stage-en, CK Wire-rel összekötve.
2. **ProgramBlock:** egyetlen fizikailag elhelyezett blokk, amelynek saját belső, végrehajtási Stage-e van. Olyan, mint a forráskódban egy **összetett utasítás / kapcsos zárójelek közé fogott blokk**.

A ProgramBlock **nem FUN-definíció és nem CALL**: lokális, névtelen programrész, nem külön függvényhívás. Tetszőlegesen beágyazható további ProgramBlockokkal, a Stage–Block engedélyezési szabályok szerint.

```text
IF[0] ── SET ── CALL ── FOR ── ...
IF[1] ── ProgramBlock { saját Stage }
```

## 2. IF: egyetlen belső Condition Stage

- Az `IF` blokkhoz **egy belső Stage** tartozik: a feltétel (`Condition Stage`).
- Jobb kattintással az IF szerkesztője nyílik meg; a feltétel Stage-éhez **csak innen**, külön nyomógombbal lehet belépni.
- Az `IF[0]` és `IF[1]` **nem saját Stage**: két kimenő vezérlési port ugyanazon külső Stage-en.
- A két kimeneten a program kiterítve vagy ProgramBlockkal folytatódhat.
- A Condition Stage engedélyezett elemeinek köre külön szabályozandó; nem általános végrehajtási Stage.

```text
                 ┌─ [1] ── ProgramBlock { ... } ──┐
IN ── IF {cond} ─┤                               ├── WAGO ── folytatás
                 └─ [0] ── SET ── CALL ──────────┘
```

A fenti rajz szemléltetés, nem állítja, hogy minden IF-nek kötelező WAGO-val zárulnia.

## 3. ProgramBlock és lexikális változó-hatókör

A ProgramBlock **látja a befoglaló scope-ban elérhető változókat**, és saját változókat is deklarálhat.

- A belső deklarációk a ProgramBlockon **belül** láthatók.
- A beágyazott ProgramBlockok öröklik a külső ProgramBlockban látható neveket.
- A belső deklarációk a befoglaló szinten és azon kívül **nem** láthatók.
- A CK Wire-en áthaladó vezérlés **nem terjeszt változó-hatókört**.
- A FUN saját hívási/lexikális környezetét külön kell kezelni: nem örökölhet automatikusan mindent pusztán a Minecraft Stage-hierarchia miatt.

```text
Külső Stage:             a, b
  ProgramBlock A:        a, b, c
    ProgramBlock B:      a, b, c, d
  A-n kívül:             a, b    (c, d nem látszik)
```

> [!question] Nyitott részlet
> A névárnyékolás (`shadowing`), deklarációs élettartam, illetve a ProgramBlockból történő korai kilépés szabályai később pontosítandók.

## 4. Mi a WAGO?

A WAGO **általános vezérlési összevezető elem** (*merge node*), nem külön `EndIf` utasítás. Használható IF, később SWITCH vagy más alternatív végrehajtási utak összefésülésére.

Egy önálló WAGO fizikai blokk alapvetően **3 IN és 1 OUT** porttal rendelkezik. A bemenetek **alternatív érkezési útvonalak**: a WAGO nem várja meg, hogy minden IN aktiválódjon. Ez **nem párhuzamos ágak szinkronizációja**.

A WAGO nem elágaztató: a jóváhagyott logikai IC-ből **pontosan egy OUT-on** folytatódhat a program.

## 5. Fizikailag összeépülő WAGO-modulok → egy logikai IC

A WAGO-k **csak közvetlen, megfelelő oldali fizikai szomszédság esetén** épülnek egy egységgé. **CK Wire nem ragaszt össze WAGO-modulokat**, és két IN port CK Wire-es összekötése nem értelmezett modul-összeépítésként.

Az összeépített modulcsoportot a Fabric/Python integrációnak fel kell ismernie, és a **Python Graph domain egyetlen logikai IC-ként** kezelje. A Minecraftban továbbra is több fizikailag lerakott WAGO-blokk látszik.

**Az IC típusát a ténylegesen szabadon maradó IN/OUT lábak száma határozza meg**, nem pusztán a modulok darabszáma.

Példák három modulra, az összeépítési geometriától függően:

| Fizikai összeépítés | Lehetséges eredő portkiosztás |
|---|---|
| Három WAGO, az OUT-okból csak egy szabad | 7 IN / 1 OUT |
| Három WAGO, több OUT szabadon marad | 5 IN / 3 OUT |

> [!important] A korábbi egyszerű képlet korlátozott
> Az `IN = 2 × modulok + 1` **nem általános szabály**. Csak egy meghatározott összeépítési mintára lehet érvényes. A tényleges portkiosztást a fizikai csatlakozásokból kell meghatározni.

### OUT-port validáció

- **0 bekötött OUT:** nem érvényes végleges vezérlési folytatás.
- **1 bekötött OUT:** érvényes, ha a további gráfszabályok is teljesülnek.
- **2 vagy több bekötött OUT:** érvénytelen; a WAGO nem elágaztató.
- A további fizikailag létező OUT-ok **szabadon maradhatnak**.
- A szabad IN portok önmagukban nem hibák.

A játékos választja ki, melyik OUT-ot vezeti tovább CK Wire-rel; **nem történik automatikus OUT-választás**. Az explicit **Élbekötés / gráfvalidáció** ellenőrzi a jóváhagyott kapcsolódást.

## 6. Fizikai és logikai állapot szétválasztása

Példa: egy hárommodulos WAGO-csoport középső modulját a játékos kitöri. A Minecraftban a csoport két részre esik, ezért a fizikai IC-kiosztás megváltozott.

**Elvárt viselkedés:**

1. A Minecraft az új fizikai állapotot jeleníti meg.
2. A Python **nem töröl, nem ír át és nem hoz létre automatikusan GraphEdge-et vagy logikai IC-t** a véletlen módosítás miatt.
3. A rendszer jelzi a fizikai–logikai eltérést / javításigényt.
4. A játékos kifejezett művelettel rendezi a kapcsolást, majd validáltatja.

Ez összhangban áll a CK Wire korábbi elvével: egy véletlen törés nem változtathatja meg csendben a hiteles domain-állapotot.

## 7. Stage–Block engedélyezési mátrix

A Stage-eknek **típusa** van, és az engedélyezett blokkfajtákat Stage-típus × Block-típus mátrix szabályozza.

- `ProgramBlock.BODY`: végrehajtási Stage; például SET, CALL, IF, FOR, újabb ProgramBlock megengedett.
- `IF.CONDITION`: feltételkifejezést szerkesztő Stage; saját, szűkebb szabályokkal.
- CLASS és INTERFACE definíció **nem** helyezhető el egy lokális ProgramBlockban vagy IF-ág végrehajtási folyamában.
- Az Inventory CK-fülei szűrhetők a Stage típusa alapján, de a szerver/domain oldali validáció is kötelező.

> [!note] Nyitott tervezési kérdések
> - Melyik WAGO-oldal melyik másik oldalhoz épülhet fizikailag? Hogyan képezzük le a belső csatlakozásokat és a szabad lábakat?
> - Hogyan marad stabil az IC logikai azonosítója bővítés, szétválás és újraösszeépítés esetén?
> - Hogyan jelezzük az eltérő fizikai állapotot úgy, hogy ne legyen automatikus domain-módosítás?
> - Hogyan érvényesül a lexikális scope IF-ágak, WAGO-utáni folytatás és későbbi ciklusok esetén?
> - Hogyan ellenőrizzük a nem kívánt ciklusokat, elérhetetlen ágakat és lezáratlan vezérlési utakat?

## 8. Javasolt megvalósítási sorrend – még nem kiadott feladat

1. Stage–Block engedélyezési modell és ProgramBlock-scope szerződésének pontosítása.
2. IF Condition Stage navigáció és feltételmodell.
3. WAGO egyedi blokk: 3 IN / 1 OUT, alternatív merge-szemantika.
4. Közvetlen fizikai WAGO-összeépítés felismerése és portkiosztás számítása.
5. Egyetlen logikai IC vetület, explicit validáció és sérülésjelzés.

> [!success] Eddigi döntések lényege
> **IF:** egy Condition Stage, két külső vezérlési ág. **ProgramBlock:** lokális, lexikális scope-pal rendelkező összetett utasítás. **WAGO:** közvetlenül összeépülő fizikai modulokból egy logikai, tipizált merge-IC; pontosan egy továbbvezetett OUT. **Domain:** fizikai módosításból nincs automatikus logikai gráfátírás.
