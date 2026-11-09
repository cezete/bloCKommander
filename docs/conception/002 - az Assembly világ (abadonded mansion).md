---
title: "bloCKommander – Assembly mód: ötletmagok"
date: 2026-10-23
tags: [bloCKommander, CK, assembly, 6502, Z80, brainstorming, architecture]
status: "Ötletelés – nem elfogadott implementációs specifikáció"
---

# bloCKommander – Assembly mód: ötletmagok

> [!abstract] Az alapgondolat
> **Ugyanaz a CK Stage- és komponensvilág több absztrakciós szinten működhet:** a felső szinten `IF`, `FOR`, `FUN`, `ASSEMBLY` blokkokkal építünk programot; az assembly Stage-en pedig processzorutasításokból szervezünk végrehajtási gráfot. A közös, újrafelhasználható komponensnek lehet magas szintű hívása és célprocesszor-specifikus megvalósítása.

## 1. Miért nem 56 külön blokk?

A klasszikus MOS 6502-nek **56 hivatalos utasítás-mnemonikja** van, de ezek családokba rendezhetők. Nem szükséges minden mnemonikhoz külön inventory-blokk: egy blokkcsaládon belül konfigurálható a konkrét művelet, a címzési mód és az operandus.

| CK blokkcsalád (ötlet) | Példák |
|---|---|
| LOAD / STORE / TRANSFER | `LDA`, `STA`, `LDX`, `TAX` |
| ARITHMETIC | `ADC`, `SBC`, `INC`, `DEC` |
| LOGIC / BIT | `AND`, `ORA`, `EOR`, `BIT` |
| SHIFT / ROTATE | `ASL`, `LSR`, `ROL`, `ROR` |
| COMPARE | `CMP`, `CPX`, `CPY` |
| BRANCH / JUMP | `BEQ`, `BNE`, `JMP` |
| CALL / RETURN | `JSR`, `RTS` |
| STACK / FLAGS | `PHA`, `PLA`, `CLC`, `SEC` |

Például egy LOAD/STORE blokk beállításai: `LDA` + immediate + `$20` → `LDA #$20`; vagy `STA` + absolute + `$D020` → `STA $D020`. **A CPU-profil ellenőrzi**, mely utasítás–címzési mód kombinációk legálisak (például `STA` nem használ immediate címzést).

Külön **6502 / Z80 inventory-tab** vagy CPU-profil alapján váltó assembly-tab képzelhető el.

## 2. A vezérlési gráf assemblyben is működik

A legtöbb utasítás a következőre lép, de az ugrások és hívások természetes gráfelágazások:

- **`JMP` / `GOTO`:** ha másik Stage-re irányul és nem tér vissza, az aktuális Stage-ből kilépés; ha azonos Stage-en belüli címkére ugrik, normál gráfél, **nem** Stage-terminátor.
- **`RTS` / `RETURN`:** visszatérés a hívóhoz; a hívott egység terminális pontja.
- **`JSR` / `GOSUB`:** a `FUN` rokona: hívás, majd folytatás a következő utasításnál. A visszatérési címet és a stack-hatást a szemantika kezeli.
- **Feltételes branch:** két vezérlési kimenet: ugrás és továbbhaladás.
- **Z80 `DJNZ`:** a B regiszter csökkentése és nemnulla esetén ugrás – jó példa egyetlen blokkba sűrített ciklusszervezésre.

**Nyitott UX-döntés:** az utasítások közti sorrendet mindig CK Wire adja-e, vagy bizonyos lineáris szakaszokban a fizikai elrendezésből is származhat? A brainstorming a **gráfalapú megoldást** részesítette előnyben, de ezt még nem véglegesítettük.

## 3. Az assembly Stage és a felső Stage

```text
PROGRAM STAGE (magasabb szint)
  START → FOR → [ASSEMBLY / FUN: PrintCharacter] → NEXT → STOP
                      │
                      ▼
              ASSEMBLY STAGE (6502)
              LDA ... → JSR $FFD2 → RTS
                      │
                      ▼
                 6502 backend
                      │
                      ▼
                 C64 program
```

Az `[ASSEMBLY]` nem pusztán szövegdoboz: **egy másik, szerkeszthető Stage-re hivatkozó komponens** lehet, hasonlóan a korábban elképzelt `[FUN]` blokkhoz.

A felső Stage lehet magas szintű, C-szerű modell vagy akár assembly-közeli szervezés is. A fordító a célplatformnak megfelelő kódot generálja. Ez **nem** jelenti azt, hogy tetszőleges assembly automatikusan visszafordítható C-re.

## 4. Újrafelhasználható KERNAL-komponensek

Például a C64 **CHROUT** szolgáltatása (`JSR $FFD2`), amely a kiírandó karaktert az **A regiszterben** várja.

Egy lehetséges CK-komponens:

```text
[PrintCharacter]
  szemantika: egy karakter kiírása
  bemenet: karakter
  célplatform: C64 / 6502
  assembly megvalósítás: A előkészítése + JSR $FFD2
```

Ugyanezt a komponenst használhatja:

1. Egy kézzel szerkesztett 6502 Assembly Stage.
2. Egy felső Stage `[FOR]` ciklusának törzse.
3. Egy C-szerű felső Stage, megfelelő, toolchain-függő wrapperen keresztül.

A **komponens szerződése** leírja a bemeneteket, kimeneteket, regiszter-/flag-mellékhatásokat, célplatformot és hívási konvenciót. Egy konkrét KERNAL-rutin nem automatikusan platformfüggetlen; más CPU-hoz külön implementáció vagy explicit inkompatibilitás kell.

### Ugyanaz a FOR, eltérő kódgenerálás

Magas szinten:

```text
FOR i = 1 TO 10
    PrintCharacter('A')
NEXT
```

6502 célkódhoz a backend számlálót, elágazást és szubrutinhívást állít elő; a hívott rutin regiszter-mellékhatásai alapján megőrzi a szükséges állapotot. A korábbi beszélgetésben szemléltető mintaként ilyen assembly szerepelt:

```asm
    LDX #10
loop:
    TXA
    PHA
    LDA #$41
    JSR $FFD2
    PLA
    TAX
    DEX
    BNE loop
```

Ez **ötletvázlat**, nem tesztelt kódgenerátor vagy minden hívási környezetben garantáltan helyes ABI-megoldás.

## 5. Három elkülönítendő fogalom

| Fogalom | Feladat |
|---|---|
| **Stage** | Szerkeszthető végrehajtási gráf, magas szintű vagy assembly szemantikával |
| **Component** | Újrafelhasználható, meghívható egység, deklarált szerződéssel és megvalósítással |
| **Backend** | A Stage-ek és komponensek célgép-/nyelvspecifikus kódgenerálása |

A kulcselv:

> **A CK-komponens jelentése és hívási szerződése legyen különválasztva a konkrét processzorutasításoktól és a kódgenerálási módtól.**

Példa: később egy `[ADD16]` komponenshez készülhet 6502 és Z80 implementáció. A felső Stage ugyanarra a fogalomra hivatkozik, a backend pedig kiválasztja a megfelelő megvalósítást.

## 6. Közös keret, eltérő CPU-profilok

A 6502 és Z80 műveletei sokszor hasonló célt szolgálnak, de **nem azonos szemantikájúak**. Közös lehet a Stage infrastruktúrája, a port-/gráfkezelés és a komponenshívás, míg CPU-specifikus:

- regiszterek és flagek;
- utasítások és címzési módok;
- stack- és hívási szabályok;
- ugrások és elágazási távolságok;
- memória-/I/O-tér és célplatform-szolgáltatások.

Assembly Stage esetén a CONTROL gráf mellé szükséges egy **processzorállapot-modell** (regiszterek, flagek, memória, PC, SP). Nem feltétlenül kell minden regiszterértéket külön DATA-vezetékkel ábrázolni: sok utasítás implicit CPU-állapoton dolgozik.

## 7. Nyitott tervezési kérdések – későbbi filozofáláshoz

- Egy `[ASSEMBLY]` blokk **szubrutin-hívás** (`JSR`) vagy **inline kódbeillesztés** is lehet? A két módnak eltérő stack- és regiszterhatása van.
- Hogyan néz ki a Stage-en belüli címke és a Stage-ek közti ugrás pontos szerződése?
- Hogyan reprezentáljuk a flag-függő branch-eket, és hogyan ellenőrizzük a CPU-állapotot?
- A magas szintű `[FOR]` hogyan fordul le 6502-re, Z80-ra vagy más backenddel?
- Hogyan dokumentáljuk egy komponens regiszter-/flag-clobber listáját és a paraméterátadást?
- A CPU-profilhoz külön inventory-fül, vagy egy közös assembly-fül dinamikus blokklistával tartozzon?
- Mely komponensek valóban platformfüggetlenek, és melyek (például C64 KERNAL) gépspecifikusak?
- Hogyan kezeljük az assembly Stage saját validációját a magas szintű CK-gráfvalidáció mellett?

## 8. Kapcsolat a jelenlegi CK-fejlesztéssel

**Nem új implementációs feladat.** Ez a jegyzet ötletarchívum, nem változtatja meg a CK-03 elfogadott scope-ját.

A meglévő CK-építőelemek – Stage, GraphNode, GraphEdge, EdgeRoute, WireCell, revíziókezelés, snapshot és perzisztencia – később alapot adhatnak assembly Stage-ekhez is. Az új fejlesztés súlypontja várhatóan a **CPU-specifikus blokkszemantika, processzorállapot, komponensszerződés és backend** lenne.

A brainstorming idején a CK-03/B1 kész volt; a CK-03/B2 PostgreSQL graph-perzisztencia műszak már ki volt adva, eredménye még nem szerepelt ebben a beszélgetésben. A következő fő projektlépések ettől függetlenül haladnak.

---

> [!quote] A Mansion falára
> *A Minecraft-hangárban lerakott blokk nem feltétlenül egy utasítás: lehet egy újrafelhasználható programgondolat is. Azt, hogy ebből `JSR`, C-függvényhívás vagy Z80-as kód lesz, a célgép és a backend dönti el.*

*Emlékeztető: a `FORD=STOP`-féle C64 BASIC-nosztalgia külön easter eggnek kiváló, de a konkrét BASIC-lexikai trükköt majd érdemes a gépkönyvvel ellenőrizni.*
