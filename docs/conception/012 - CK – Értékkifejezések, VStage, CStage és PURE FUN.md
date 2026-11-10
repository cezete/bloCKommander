---
title: "CK – Értékkifejezések, VStage, CStage és PURE FUN"
status: "Koncepció – részben elfogadott döntések, részben nyitott kérdések"
date: 2026-11-10
---

# 012 – Értékkifejezések, VStage, CStage és PURE FUN

> **Hatókör:** koncepciódokumentum, nem implementációs felhatalmazás. A leírt fogalmak tervezési döntések; a jelenlegi CK-implementációban való meglétük külön ellenőrizendő.

## 1. Alapelv: vezérlés és érték különválasztása

A CK két egymásba ágyazható gráfvilágot különít el:

- **PStage (Programming Stage):** vezérlési utasítások, állapotváltoztatás és mellékhatásos műveletek világa.
- **VStage (Value Stage):** típusos, mellékhatásmentes értékkifejezések világa.
- **CStage (Condition Stage):** BOOLEAN eredményt adó, mellékhatásmentes feltételgráf. A VStage és CStage közös alapjának kérdése későbbi domain-döntés.

A belső Stage-eknek nincs hagyományos START/STOP párjuk; a kifejezés értékét a **RESULT** jelöli ki. A RESULT pontos kardinalitását, port- és validációs szabályait külön kell szerződésesíteni.

## 2. Blokkok és definíciók

| Fogalom | Szerep | Elhelyezés / belső Stage |
|---|---|---|
| **LET** | Külső értékadó utasítás; célváltozóhoz rendeli a kiszámolt értéket | PStage; jobb oldala VStage |
| **EXPR / EXPRESSION** | Összetett, értéket szolgáltató blokk | VStage; saját belső VStage lehet |
| **LITERAL** | Közvetlen, típusos konstans (`42`, `"szöveg"`, `true`) | VStage; nem általános kifejezéskonténer |
| **WHEN** | Feltételes értékválasztás, a ternary operátor szemantikájával | VStage; saját CStage a feltételhez |
| **IF** | Vezérlési elágazás | PStage; feltételéhez CStage |
| **CALL** | Egységes hívóblokk; célpontjai a környezettől függenek | PStage, VStage, CStage – szabályozott célpontszűréssel |
| **FUN** | Nem-VOID, mellékhatásokat megengedő függvénydefiníció | PStage törzs |
| **PFUN / PURE FUN** | Nem-VOID, ellenőrizhetően tiszta függvénydefiníció | VStage törzs |
| **PROC** | VOID eljárásdefiníció | PStage törzs |

**Elnevezési döntés:** az általános értékkifejezés-blokk neve **EXPRESSION**, röviden **EXPR**; a **LITERAL** megmarad a konstansoknak. A korábban felvetett `VALUE` blokknevet nem használjuk.

## 3. LET és a tiszta jobb oldal

A LET a PStage vezérlési gráfjának része. A JGB („Jobb Gombbal a Belébe”) művelettel a jobb oldali értéket előállító VStage nyílik meg. A VStage-ben **nincs további, mellékhatásos LET**. A felhasználó EXPR, LITERAL, WHEN, megengedett CALL és más tiszta kifejezéselemek segítségével épít értéket.

A VStage-en EXPR szerepelhet egy másik EXPR belső VStage-én is; ez hierarchikus kifejezésépítés, nem újabb értékadás. A kifejezés kiértékelésének függőségi gráfja ne tartalmazzon ciklust; a PFUN-hívások rekurziója ettől külön kérdés.

**Típusszabály:** a VStage eredménytípusa a kifejezésből következtethető, és a szülő által elvárt típussal ellenőrizendő. Implicit konverziók katalógusa még nincs elfogadva.

## 4. WHEN: egy CStage, két értékág a szülő VStage-en

A WHEN nem vezérlési IF, hanem **értéket választó** blokk. Saját belső CStage-e BOOLEAN eredményt ad. A TRUE/FALSE értékeket **nem két további belső VStage**, hanem a **szülő VStage-en fizikailag elhelyezett EXPR/LITERAL (vagy más megengedett értékforrás) blokkok** szolgáltatják, a WHEN megfelelő portjain keresztül.

A két ág eredménytípusának kompatibilisnek kell lennie; a WHEN kimenete a kiválasztott ág értéke. A nem kiválasztott ág kiértékelésének elkerülése a ternary szemantikához tartozó javasolt követelmény, amelyet a kiértékelési szerződésben kifejezetten rögzíteni kell.

**WAGO:** a két alternatív értékfolyam WAGO-szerű összefogása a RESULT előtt kívánatos vizuális megoldás. Ugyanakkor a **VALUE merge** és a korábban tárgyalt **CONTROL merge** eltérő domain-szemantika; nem szabad automatikusan ugyanazzá a gráfműveletté összevonni őket. A pontos portok, vezetékek, WHEN/WAGO/RESULT kapcsolat még nyitott.

## 5. FUN, PFUN, PROC és CALL

A három definíció mind megjelenhet később osztálymetódusként a generált Java/Kotlin kódban: FUN és PFUN nem-VOID metódus, PROC VOID metódus. Az osztályhoz tartozás önmagában nem dönti el a tisztaságot.

| CALL kontextusa | Választható célpontok |
|---|---|
| **PStage** | FUN, PFUN, PROC |
| **VStage** | kizárólag PFUN |
| **CStage** | kizárólag BOOLEAN eredményű PFUN |

A JGB célpontválasztó csak az adott kontextusban megengedett célpontokat kínálja. Importált definícióknál a tisztasági és típus-szerződést igazolni kell; ismeretlen külső metódus nem válik automatikusan PFUN-ná.

## 6. PFUN v1: tisztasági szerződés

**Elfogadott induló korlátozás:** a PFUN törzse csak a **saját explicit paramétereiből**, literálokból, tiszta műveletekből és más PFUN-ok hívásából állíthat elő eredményt. Nem olvashat külső mutable változót, objektummezőt (implicit `this`-t sem), globális állapotot, aktuális időt, rejtett véletlenállapotot vagy más külső változó adatforrást; nem végezhet mellékhatást.

Ez **nem tiltja**, hogy egy PStage-en lévő változó **aktuális értékét argumentumként** adjuk át a PFUN-nak. A PFUN belül a saját paraméterét látja, nem a külső változóhelyet. A változóreferencia (`VAR_REF` / `READ` munkanév) nem azonos a konstans **LITERAL** blokkal.

A tisztaság nem csak az írás tilalma: azonos explicit bemeneteknek azonos eredményt kell adniuk. A `random()` vagy `now()` rejtett állapottal nem tiszta; explicit seedből számított determinisztikus függvény lehet tiszta. A paraméterként kapott objektumok mély immutabilitása és az engedélyezett típusok köre még kidolgozandó; az első változatban egyszerű immutable értéktípusokra korlátozás ajánlott.

A PFUN hívhat PFUN-t, akár rekurzívan is. A rekurzió nem mellékhatás, de a terminálás/futáskorlát külön kérdés. A transzitív hívási gráf tisztaságát ellenőrizni kell.

## 7. Szemantikai példa

A kiinduló elképzelés:

```text
LET ertek: STRING =
    concat(
        WHEN is_there_younger_brother()
            TRUE  -> "alacsonyabb"
            FALSE -> "magasabb",
        " fogasra"
    )
```

A LET belső VStage-én a WHEN egy CStage-en értékeli ki a feltételt, miközben a két LITERAL a **szülő VStage-en** csatlakozik a WHEN értékportjaihoz. A CONCAT lehet későbbi műveleti blokk vagy PFUN-hívás; ez még nincs eldöntve. A végső RESULT STRING típusú.

A példa nem állítja, hogy ezek a blokkok vagy a futtatás már implementáltak.

## 8. Kódnézetet mutató falitáblák

A VStage falán legyen az aktuális kifejezés **származtatott, read-only, programkódszerű összefoglalója**. A külső PStage-en a kapcsolódó LET mellett is lehessen rövidített tábla, hogy ne kelljen minden olvasáshoz JGB-vel belépni.

A tábla nem második forrásigazság: a Python-domainből/projekcióból származik. Hosszú kifejezéseknél rövidített megjelenítés szükséges. A későbbi Web Workbench szemantikus YAML-nézete ugyanazt a domainmodellt célozza, nem önálló szerkesztési igazságot.

## 9. Nyitott döntések

1. A VStage és CStage közös absztrakciója, Stage-típusok és RESULT-invariánsok.
2. A WHEN, VALUE-WAGO és RESULT pontos port-, topológiai és kiértékelési szerződése.
3. A nem kiválasztott WHEN-ág lusta kiértékelésének formalizálása.
4. A változóreferenciák, lokális immutable értékek, típuskonverziók és scope részletes szabályai.
5. PFUN-paraméterek típusköre, objektumok mély immutabilitása, importált PFUN-szerződések.
6. Rekurzió, nemterminálás és futáskorlátok.
7. EXPR/CALL/WHEN szerkesztésének, vizuális portjainak és falitábláinak Minecraft UX-e.
8. Szemantikus YAML és teljes perzisztált Stage-snapshot leképezése; későbbi IR/codegen.
9. A PFUN/PStage/CStage definíciók és osztálytagság, import, metódushívás részletes szabályai.

## 10. Integrációs megjegyzés

A dokumentum önálló, Obsidianban is használható. A `docs/conception/` alá történő beemelés előtt **read-only** összevetés szükséges a 001–011 eredeti koncepciókkal és a jelenlegi `README.md`, `STATUS.md`, `OPEN-DECISIONS.md` nyilvántartással. A korábbi dokumentumokat nem írjuk át hallgatólagosan. Az Öcsi által éppen végzett paraméterszerkesztő-felderítés ettől külön munkaszál.
