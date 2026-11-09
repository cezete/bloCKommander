---
title: "bloCKommander – Stage working copy, publikáció és későbbi csapatmunka"
date: 2026-10-24
tags:
  - bloCKommander
  - architecture
  - stage
  - working-copy
  - collaboration
status: "Koncepció / döntések – nem implementációs jelentés"
---

# bloCKommander – Stage working copy, publikáció és későbbi csapatmunka

> [!abstract] Lényeg
> **A DB mind felett.** A Stage publikált, stabil állapota a programgenerálás kizárólagos forrása. A Minecraftban történő szerkesztés a hangárhoz tartozó **közös munkapéldányban** zajlik. Félkész munka megőrizhető anélkül, hogy publikált, akár BROKEN Stage-verzióvá válna. A publikálás joga a hangár tulajdonosáé.

## 1. Fogalmi alapok

| Fogalom | Jelentés a CK-ban |
|---|---|
| **Stable / published Stage** | Utolsó sikeresen validált, publikált Stage-állapot. A BUILD/GENERATE ezt használja. |
| **Working copy** | A hangár adott Stage-ének szerkesztés alatt álló, akár félkész példánya. |
| **StageSnapshot** | A Stage tartalmának menthető pillanatképe (blokkok, gráf, útvonalak és releváns metaadatok). |
| **Epoch** | A betöltött munkapéldány-generáció azonosítója; REBUILD után új epoch keletkezik. |
| **Revision** | A munkapéldány módosításainak verziója; segít észlelni a közben történt változásokat. |
| **Saved version / base_version** | A publikált/mentett állapot verziója, amelyhez a munkapéldány viszonyul. |
| **CAS (Compare-And-Swap)** | Csak akkor írjuk felül az elvárt verziót, ha az időközben nem változott; konfliktus esetén nincs néma felülírás. |
| **Atomic** | Egy művelet egészben sikerül vagy nem változtat állapotot. A tranzakció többek között ezt a tulajdonságot biztosítja. |
| **Idempotens** | Ugyanazon művelet ismétlése nem eredményez újabb változást. |

## 2. A hiteles állapot és a munkapéldány szétválasztása

- Egy Stage-hez tartozik egy **aktuális, stabil, publikált** állapot (a korábbi stabil verziók története opcionálisan megőrizhető).
- Mellette a hangárban lehet egy **szerkesztett working copy**, amely lehet befejezetlen, érvénytelen vagy részlegesen huzalozott.
- **Nem publikálunk BROKEN állapotot** csak azért, hogy a munkát később folytathassuk: erre a working copy szolgál.
- A working copy adatbázisbeli tartós megőrzése nem azonos a stabil állapot publikálásával.
- **BUILD / GENERATE mindig egy rögzített, stabil, publikált verzióból dolgozik**, nem az aktuálisan kalapált Minecraft-világból.
- A jelenlegi CK-03/B3 `save()` és `rebuild()` működése még nem ezt a teljesen szétválasztott publikációs modellt valósítja meg; ez **tervezett szerződésmódosítás**.

### Javasolt műveleti szemantika

| Művelet | Cél |
|---|---|
| **SAVE WORK** | A hangár közös, akár félkész munkapéldányának tartós megőrzése, a stabil verzió változtatása nélkül. |
| **PUBLISH** | Jogosultság- és validációellenőrzés után új stabil Stage-verzió véglegesítése, CAS-védelemmel. |
| **REBUILD** | A kiválasztott tárolt állapotból új munkapéldány-generáció létrehozása. A pontos forrást (working copy vagy stable) a parancsnak egyértelművé kell tennie. |
| **BUILD / GENERATE** | Program előállítása kizárólag stabil, publikált Stage-verzióból. |

> [!important] Elnevezések
> A **SAVE WORK / PUBLISH** nevek munkanevek, nem végleges UI-feliratok. A régi SAVE fogalmát nem szabad észrevétlenül mindkét célra használni.

## 3. Minecraft-állapot és DB-állapot: kilépési kapu

**Alapelv:** A Minecraft-világ önmagában nem módosítja a hiteles, publikált Stage-et.

- Stage elhagyásakor a rendszer a **DB-ben tárolt releváns Stage-állapotot** és a **Minecraftban ténylegesen létező Stage-állapotot** hasonlítja össze.
- **Bármilyen releváns eltérés esetén figyelmeztet**. Nem vezetünk be külön „fizikai sérülés” fogalmat vagy állapotgépet.
- A játékos eldöntheti, hogy visszatér a szerkesztéshez (például ConnectionDestroyer/DestroyWire művelet, blokkparaméterezés befejezése), menti a munkát a megfelelő módon, vagy távozik.
- Egy véletlenül kiütött CK Wire **nem törli automatikusan a GraphEdge-et**, és önmagában nem hoz létre BROKEN domainállapotot, revision-változást vagy DB-módosítást.
- A GraphEdge tudatos megszüntetéséhez a külön, megerősített kapcsolat-törlési művelet szükséges.
- A különböző állapotok összehasonlításához **kanonikus, Stage-releváns reprezentáció** szükséges: nem minden véletlen Minecraft-környezeti eltérés számít.

> [!warning] Tisztázandó részlet
> Mivel a DB-ben később **stable** és **working copy** is lehet, a kilépési diff pontos referenciáját és a „távozom” művelet tartóssági következményét specifikálni kell. A felhasználói alapelv változatlan: eltérésnél figyelmeztetés, nincs automatikus publikálás vagy domain-törlés.

## 4. A working copy a hangárhoz tartozik

**Nem játékosonkénti privát Stage-eket vezetünk be.** A meghívott játékos a házigazda hangárjának **közös** munkapéldányát szerkeszti.

- Egy hangár és Stage pároshoz legfeljebb **egy aktív közös working copy** tartozik.
- A játékos Stage-váltása, Netherbe távozása vagy kilépése **nem szünteti meg a közös munkát**.
- A munkapéldány megőrzésének szerveroldali, szükség szerint tartósnak kell lennie; nem függhet egyetlen kliens memóriájától.
- Az azonos Stage-en dolgozó meghívottak módosításai **ugyanabba** a munkapéldányba kerülnek.

Lehetséges adattábla (egyelőre koncepció):

```text
stage_working_copy
  hangar_id
  stage_id
  base_version
  working_snapshot
  ...

UNIQUE (hangar_id, stage_id)
```

A korábban felmerült `UNIQUE(player_id, stage_id)` **nem megfelelő a közös hangármunkához**. A „STACK” név helyett a `stage_working_copy` pontosabb, mert ez nem klasszikus LIFO-verem.

## 5. Jogosultságok – későbbi csapatmunka

A csapatmunka **nem része a mostani implementációs körnek**, de a szabályait előre rögzítjük.

| Szerep | Megtekintés | Szerkesztés | Working copy mentése | Publikálás |
|---|:---:|:---:|:---:|:---:|
| **Hangár tulajdonosa** | Igen | Igen | Igen | Igen |
| **Meghívott player** | Igen | Meghívott Stage-részfában | Nem | Nem |
| **Járókelő** | Igen | Nem | Nem | Nem |

A BUILD jogosultsága később pontosítandó; **a BUILD adatforrása mindenképpen a stabil, publikált verzió**.

### Stage-fa szerinti meghívás

- A meghívás **mindig egy konkrét Stage-hez** kötődik.
- A szerkesztési jogosultság **lefelé öröklődik** a Stage-fa összes leszármazottjára.
- Példa: Béla meghívása a `CustomerService` class Stage-re jogosítja őt az alatta lévő `getCustomer()`, `saveCustomer()` stb. metódus-Stage-ek szerkesztésére is.
- A meghívás **nem jogosít** a szülő Stage vagy a testvérágak szerkesztésére.
- Az újonnan létrehozott al-Stage-ek is öröklik a hozzáférést.
- Stage-áthelyezésnél a jogosultság az új fahelyzettől függhet; ezt a csapatmunka tervezésekor kezelni kell.

### Meghívás visszavonása

- A hangártulajdonos bármikor visszavonhatja a meghívást.
- A jogosultság **azonnal megszűnik**, akkor is, ha Béla éppen az adott Stage-en áll.
- A jogosultságot **minden módosító műveletnél szerveroldalon** ellenőrizni kell, nem csupán belépéskor.
- Béla korábban elvégzett módosításai **megmaradnak a hangár közös working copy-jában**; a meghívás visszavonása nem rollback.
- Béla ezután legfeljebb nézelődhet és hümmöghet.

## 6. Konkurencia és publikációs konfliktus

- Ha a working copy egy korábbi stabil verzióból indult, és időközben új stabil verzió jelent meg, **nem írhatja felül észrevétlenül** azt.
- A PUBLISH használjon verzióellenőrzést / CAS-t; konfliktus esetén a munkát meg kell őrizni, és külön döntés kell a feloldáshoz.
- Két meghívott egyidejű, azonos blokkra irányuló módosításának ütközéskezelése **későbbi csapatmunka-feladat**.
- A DB-tranzakció és a Minecraft-világ fizikai változása nem egyetlen automatikus atomi művelet. A **DB mint hiteles állapot + explicit publikáció** elv miatt nem kell minden csákányütést DB-tranzakcióvá alakítani.

## 7. Implementációs határ és következő lépés

**Most:** CK-03/B3 elkészült Python/DB mentés-visszaépítésének megértése és ellenőrzése; a rövid gyakorlati bemutató külön beszélgetésben történik.

**Később:** working copy és publikált Stage elkülönítése, a SAVE/REBUILD szerződésének tudatos átalakítása, majd csapatmunka, Stage-fa jogosultságok és több szerkesztő konkurenciakezelése.

> [!success] Döntések egy mondatban
> **A hangár a közös műhely, a working copy a félkész munka, a publikált Stage a stabil igazság, a BUILD csak ebből dolgozik, és a publikálás joga a hangár gazdájáé.**

---

*Döntés- és koncepciójegyzet, 2026-10-24. Nem állítja, hogy a tervezett csapatmunka vagy a külön PUBLISH mechanizmus már implementálva lenne.*
