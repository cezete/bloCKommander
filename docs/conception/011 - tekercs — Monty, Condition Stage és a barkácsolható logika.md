---
title: "CK koncepció 11 — Monty és a Condition Stage"
status: "Koncepció — nem implementációs specifikáció"
date: 2026-11-09
tags: [bloCKommander, conception, condition-stage, logic-gates, minecraft, monty]
---

# 11. tekercs — Monty, Condition Stage és a barkácsolható logika

> **Jelleg:** koncepció és döntésnapló. Nem ad önmagában felhatalmazást implementációra. A tényleges domain/API/Graph/MC szerződéseket külön tervezés és felderítés előzi meg.

## 1. Vízió

A CK (bloCKommander) Minecraftban térben szerkeszthető programozási modell. A Python webhook/service beceneve **Monty** lehet. A cél nem pusztán egy IF-szövegmező, hanem **Condition Stage (CStage)**: önálló, logikai áramkörként építhető feltétel. Komplex, akár a Monty Python-féle, több feltételt és kivételt tartalmazó utasítások logikai része is modellezhető legyen.

A Minecraft és a jövőbeli Web Workbench ugyanannak az authoritative Python-domainnek két felülete. A web strukturált szerkesztést, Stage-fát, diagnosztikát és később célzott Minecraft-navigációt adhat; a Minecraft a térbeli építés elsőrendű felülete. A böngészőben történő kiválasztás önmagában ne rendezze át a Minecraft hangárt.

## 2. CStage: szemantikai modell

- A CStage-ben **Expression**, **AND**, **OR**, **NOT**, **RESULT** elemek vannak. Nem szükséges START és STOP.
- **Expression**: kívülről atomi logikai blokk, három szerkeszthető része: bal operandus, reláció, jobb operandus. Relációk kezdetben pl. `==`, `!=`, `<`, `>`, `<=`, `>=`, később `like` és egyéb típusfüggő operátorok.
- Az operandus belül lehet összetett kifejezés (pl. függvényhívás), de a CStage-ben nem kell minden operátort külön Minecraft-blokként ábrázolni.
- Expression jobb kattintással szerkeszthető; a reláció választható listából; változónevek és függvények felajánlása későbbi kényelmi funkció mindkét kliensben.
- Minden Expressionhöz automatikusan generált, olvasható **sticky tag** / tábla tartozik, pl. `van_occse == true`. A tábla megjelenítés, **nem** adatforrás; hosszú szöveg esetén rövidítés vagy több sor szükséges lehet.
- Az Expression BOOLEAN értéket ad; a logikai kapuk BOOLEAN bemeneteket fogadnak és BOOLEAN eredményt adnak.
- A CStage-nek **egy RESULT blokkja** van, amely egy BOOLEAN bemenetet fogad, és a Stage végső eredményét reprezentálja.
- A gráf irányított és **körmentes** (DAG). A RESULT felől visszafelé feltárhatók a szükséges függőségek; a tényleges kiértékelés függőségi/topologikus sorrendben történik. A kiértékelés, a validáció és a menthetőség külön státuszok lehetnek.
- A RESULT-ba nem bekötött, félkész blokkok jelenléte a szerkesztés során megengedhető; hogy milyen állapotban értékelhető/publikálható a Stage, külön validációs szabály.

### Illusztratív példa

```text
Expression A: time_hajvagas != today()
Expression B: count(f_weekend_party(younger_brothers)) > 0

A ─┐
   AND ─── RESULT
B ─┘
```

A Monty Python-féle összetett utasításban a ruhák áthelyezése **akció**, nem feltétel: a külső IF megfelelő ágán történik. Az időbeli és kivételszabályok pontos logikai fordítása külön szemantikai munka, nem automatikus a természetes nyelvből.

## 3. WAGO-elv és összetett kapuk

- A korábban felvetett **WAGO-elv** a jelszétosztásra (egy érték több fogyasztóhoz) és a logikai kapuk fizikai kompozíciójára ad inspirációt.
- **AND csak AND-dal**, **OR csak OR-ral** egyesíthető; eltérő kaputípusokat nem olvasztunk össze. Több független BOOLEAN jel közvetlen összekötése **nem** helyettesít logikai műveletet.
- Ugyanaz a kifejezés megépíthető külön kétbemenetes kapuk láncával (`AND(A, AND(B,C))`) vagy egy többbemenetes összetett kapuval (`AND(A,B,C)`). Mindkettő érvényes.
- **Kulcsszó: blokkegyesítés.** Több érintkező azonos típusú fizikai kapublokk egyetlen logikai kaput alkothat. A fizikai cellák száma nem azonos a szemantikai GraphNode-ok számával.
- Egy összetett kapunak **pontosan egy OUT portja** van; minden más külső, portként használható felület **IN**. A kimenet helyét a játékos határozza meg.
- Három egymás melletti AND blokk vízszintes külső felületeinek száma 8: **7 IN + 1 OUT**. Ugyanez igaz háromcellás L alakra. A 2×2-es négycellás négyzetnek is 8 külső vízszintes felülete van.
- Általános, síkbeli ortogonális alakzatra: `P = 4N - 2E`, ahol `N` a cellák, `E` a belső közös oldalak száma. A formula csak a vízszintes, külső felületeket számolja; a portok pontos MC-elhelyezése még tervezendő.
- A kapu alakzata **kraftolás után rögzített**, de az egész alakzat elhelyezéskor **elforgatható**, a meglévő CK-blokkokhoz hasonlóan.
- Ha a játékos más alakzatot akar, újat készít, eltávolítja a régit, lerakja az újat, és újraköti a kapcsolatait. Nincs csendes szemantikai gráfmódosítás.

### Barkácsasztal — javasolt mechanika

A játékos a crafting gridben AND/OR blokkokat helyez egymás mellé vagy alá; egy **redstone-jelölő** megadja a kimenet kívánt helyét. Például a háromcellás vízszintes AND:

```text
[  ] [RS] [  ]
[AND][AND][AND]
[  ] [  ] [  ]
```

Eredmény: három cellát elfoglaló, nyolcportos, **7 IN + 1 OUT** összetett AND kapu. A felhasználó a kapu portjait egy alakzathű, számozott ábrán is láthatja; jobb kattintásos modalban a kimenet kijelölése vagy megtekintése felmerült. A crafting-jelölés és az utólagos átállítás pontos viszonya **nyitott**.

A hagyományos Minecraft inventory egyetlen tárgyat tartalmazhat, amely lerakáskor többcellás fizikai struktúrát hoz létre. A lerakás, bontás, helyfoglalás és visszavétel atomikussága külön implementációs szerződés.

## 4. Inventory, tárgyak és kaland

- A kraftolt összetett kapu **normál Minecraft-tárgyként** működjön: először a készítő inventoryjába kerül, másoknak nem keletkezik automatikusan példány.
- **Nem személyhez kötött**: eldobható, átadható, ládában tárolható, megszerezhető és megtalálható. Nincs külön CK-s tulajdonosi nyilvántartás.
- A tárgy megszerzésének módja **ne módosítsa a logikai funkcióját**. A kraftolt, ajándékozott vagy kincsesládából szerzett kapu azonosan használható.
- A CK Creative inventoryból történő korlátlan kivétel és a kraftolás ösztönzése külön játékmenet-döntés; nem azonos a tulajdonvédelemmel.
- A kapu birtoklása **nem ad hangárbeli szerkesztési, SAVE vagy PUBLISH jogot**.
- A Minecraft-játékmenet feladatkiadássá is válhat: a rendszerszervező térképet, rejtett ládát, összetett kaput, HangarSwordöt vagy más CK-eszközt rejt el. Ez **vízió**, nem ütemezett feature.

## 5. CK Logic Wire

**Irány:** saját **CK Logic Wire**, amely vizuálisan redstone porra emlékeztet, de nem a Vanilla redstone 0–15 jelerősségétől és terjedési szabályaitól függ. A logikai jelentést a Python-domain határozza meg, a Fabric a fizikai projekciót és interakciót kezeli.

A CONTROL-jellegű CK Wire és a BOOLEAN adatkapcsolat szemantikája különbözik. A WAGO-szerű szétosztás, elágazás, porttípusok, útvonalak, keresztezések és GraphEdge-lifecycle külön tervezendők. A vezetéktörés önmagában ne töröljön csendben logikai GraphEdge-et.

## 6. Web Workbench, YAML és formázás

- Minecraft és web **közös authoritative Python-domainre** épül; a PostgreSQL mentett állapot és a Python munkapéldány közti meglévő életciklus marad kiindulás.
- A YAML lehet emberbarát **szemantikus import/export**. A kézzel írt formátumban **nem kötelező UUID vagy pozíció**.
- A parser/normalizáló szükség esetén létrehozza az azonosítókat, az **automatikus layout** pedig rendezett, áttekinthető Minecraft-elrendezést generál, az Eclipse `Ctrl+Shift+F` mintájára.
- A szemantikus YAML és a **teljes, veszteségmentes Stage-snapshot** nem azonos: a teljes round-triphez azonosítók, portok, pozíciók, alakzatok, orientációk és wire-route-ok is szükségesek.
- Az automatikus elrendezés ne változtassa meg a logikai jelentést; létező Stage újraformázásakor az identitások megőrzése külön követelmény.
- A YAML **nem második igazságforrás**. A mentés és konfliktuskezelés a CK domain szabályait követi.

Példa **szemantikus**, pozíció nélküli YAML-ra:

```yaml
condition:
  and:
    - expr:
        left: time_hajvagas
        op: "!="
        right: today()
    - expr:
        left: count(f_weekend_party(younger_brothers))
        op: ">"
        right: 0
```

Ez **illusztráció**, nem végleges séma. A típusos operandusmodell, névfeloldás, `like`, függvényhívások, scope és validáció külön tervezendő.

## 7. A CStage saját megjelenése

A Condition Stage már ránézésre is különbözzön a vezérlési Stage-től: saját színvilág, padló, fények, díszletek vagy akár **lila tehenek**. A látvány egyben szerkesztési kontextust is jelezhet: itt Expression, logikai kapuk, RESULT és CK Logic Wire használható. A konkrét dekoráció még játékos ötlet, nem elfogadott grafikai specifikáció.

## 8. Megőrzendő CK-architekturális alapelvek

1. Python-domain az üzleti/logikai jelentés hiteles helye; Fabric a Minecraft-világ fizikai megjelenítésének és interakciójának gazdája.
2. A PostgreSQL mentett állapot, a megosztott dirty munkapéldány és a Program-wide SAVE életciklusa külön kezelendő.
3. A fizikai módosítás ne változtasson csendben GraphEdge-et, domain-revíziót vagy mentett állapotot.
4. A Stage menthetősége és a teljes kiértékelhetőség/publikálhatóság nem feltétlenül ugyanaz.
5. A koncepciótekercs **nem automatikus GO** kódolásra.

## 9. Nyitott kérdések — nem eldöntött követelmények

- A kapu craftingreceptje és a kimenet későbbi modalos átállítása pontosan hogyan működik?
- Az összetett kapu elforgatása és portszámozása hogyan marad determinisztikus, különösen L alaknál?
- A WAGO jelszétosztás külön eszköz-e, és milyen fizikai szabályok szerint ágazhat el a CK Logic Wire?
- Hogyan kezeljük a kapu bontását, a már bekötött portokat, az inventoryba visszakerülést és az esetleges más játékos általi bontást?
- Hogyan működnek az Expression operandusok típusai, scope-ja, függvényhívásai és a mellékhatásmentesség?
- Hogyan reprezentáljuk a fizikai többcellás kapu és az egyetlen logikai GraphNode kapcsolatát?
- Mi a teljes, determinisztikus YAML/snapshot séma, és mikor történik explicit import, layout, bind, SAVE?
- Melyek a CStage szerkeszthető, valid, kiértékelhető és publikálható állapotai?
- Hogyan kezeljük a web és Minecraft egyidejű szerkesztésekor a revision-fencinget és a konfliktusokat?

## 10. Dokumentációs elhelyezés

Javasolt útvonal: `docs/conception/11-condition-stage-monty-koncepcio.md`.

A korábbi tíz Obsidian-koncepciót is érdemes a repo `docs/conception/` mappájába másolni, **eredeti tartalmukat megőrizve**. Készüljön `README.md` index a témákról, dátumokról, státuszokról és az esetleges későbbi felülírásokról. A repóban szereplő régi ötletek nem automatikusan aktuális követelmények; a végrehajtásra szánt szerződéseket és GO-döntéseket külön dokumentumok rögzítsék.

> *Stirlitz a 11. tekercset a frigyládába rejtette. Öcsi megtalálta. Nem implementálta azonnal. Ez volt aznap a legnagyobb szakmai bravúrja.*
