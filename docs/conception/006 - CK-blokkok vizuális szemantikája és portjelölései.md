# CK-05/B – CK-blokkok vizuális szemantikája és portjelölései

**Állapot:** koncepció és felderítési javaslat; nem implementációs GO  
**Kapcsolódás:** CK-05/A2 után javasolt önálló munkacsomag  
**Cél:** a játékos a blokkra nézve értse a csatlakozási pontokat, felülről pedig folyamatábraként olvashassa a Stage-et.

## 1. Alapelv

A CK-blokk vizuális megjelenése **nem definiálja** a program szemantikáját, hanem **láthatóvá teszi** azt.

- **Python Graph domain:** a blokk típusát, logikai portjait és az élek szemantikáját kezeli; nem függ textúráktól, égtájfeliratoktól vagy Minecraft-modellektől.
- **Fabric runtime:** a blokk fizikai pozícióját és orientációját, a portok világbeli megfeleltetését és a vezetékekkel való topológiai kapcsolatát kezeli. Nem készít második gráfdomaint.
- **Fabric resource-ok:** a blokk külsejét, oldalankénti portjelöléseit és felülnézeti folyamatábra-szimbólumát adják.

A játékosnak nem kell iránytűvel keresgélnie, hogy melyik oldalon található a megfelelő kimenet: **ránéz, és oda köti a vezetéket**.

## 2. Két nézet, egyetlen fizikai blokk

### Oldalnézet – kapcsolhatóság

A kocka megfelelő oldalain jól olvasható, a tényleges portokkal egyező jelölések láthatók.

Példák (a pontos portkészlet a domain alapján véglegesítendő):

| Blokk | Példa a jelölésekre |
| --- | --- |
| START | `OUT` |
| STOP | `IN` |
| IF | `IN`, `1`, `0` |
| FOR | `IN`, `BODY`, `EXIT` |
| FUN | A tényleges domainportoknak megfelelő jelölések |

A táblázat **nem** új portszerződés: a meglévő domainből kell kiolvasni az érvényes portazonosítókat és topológiát.

### Felülnézet – folyamatábra

A blokk felső lapján a folyamatábra szimbóluma látható. Nem szükséges külön nézetmód vagy második megjelenítő rendszer: a játékos kreatív módban felrepül, lenéz, és áttekinti a Stage-et.

| Blokk | Javasolt felülnézeti szimbólum |
| --- | --- |
| START | Kör, benne rövid jel vagy `START`, ha olvasható |
| STOP | Kör, megkülönböztethető lezáró jel |
| IF | Rombusz, lehetőleg az `1` és `0` ág irányának jelzésével |
| FOR | Hatszög |
| FUN | Megkülönböztethető függvényszimbólum |

A **fizikai blokk kocka marad**; a felső lapra rajzolt alakzat jelenti a folyamatábra-szimbólumot. A szimbólumok és rövidítések pontos grafikai szabványa később véglegesíthető.

## 3. Tájolás és portmegfeleltetés

A blokk `facing=north/east/south/west` állapota és a blockstate JSON modellforgatása lehetővé teszi, hogy a jelölések a blokkal együtt forduljanak.

Példa a jelenlegi START blockstate-mintára:

```json
{
  "variants": {
    "facing=north": {"model": "blockommander:block/start"},
    "facing=east":  {"model": "blockommander:block/start", "y": 90},
    "facing=south": {"model": "blockommander:block/start", "y": 180},
    "facing=west":  {"model": "blockommander:block/start", "y": 270}
  }
}
```

**Fontos:** a JSON-modellforgatás a megjelenítést szabályozza, önmagában nem forgatja el a Graph domain portjait. A Fabric topológiafelderítésnek ugyanazt a lokális→világ koordináta-transzformációt kell alkalmaznia, mint amit a látvány sugall.

Például az IF `TRUE`/`FALSE` logikai portjai nem válnak más porttá attól, hogy a játékos elforgatja a blokkot. A hozzájuk tartozó **fizikai oldalak** viszont elfordulnak.

### Kötelező konzisztenciaelv

`logikai portazonosító → blokklokális oldal/pozíció → facing szerinti világbeli oldal/pozíció`

A resource-modell ugyanennek a leképezésnek a látható megfelelője. Nem szabad két egymástól független, kézzel karbantartott, eltérő portszerződést létrehozni.

## 4. Resource-alapú megjelenítés

Elsődleges cél a lehető legkevesebb egyedi Kotlin-megjelenítő kód:

- `assets/blockommander/blockstates/*.json` – modellek és forgatási variánsok;
- `assets/blockommander/models/block/*.json` – modellgeometria, lapok és textúrahozzárendelések;
- `assets/blockommander/textures/block/*.png` – felső szimbólumok, oldalsó portfeliratok.

A megjelenítéshez nem kell minden blokkhoz külön Kotlin-renderer, **amennyiben** a kívánt forma és jelölés a statikus modellekkel/textúrákkal kifejezhető. A dinamikus címkék, állapotok és összetettebb geometria külön felderítendők.

## 5. Textúrafelbontás

A CK saját textúrái használhatnak például **32×32 vagy 64×64** felbontást. Ettől a vanilla föld-, kő- és más blokkok textúrája nem változik meg, és a CK-blokk fizikai mérete továbbra is egy Minecraft-blokk marad.

A magasabbról olvasható felülnézet érdekében a geometriai forma fontosabb, mint a hosszú szöveg. A nagyobb textúrafelbontás önmagában nem teszi korlátlan távolságból olvashatóvá a feliratot.

## 6. CK Wire és felülnézeti olvashatóság

A Stage felülről akkor válik valódi folyamatábrává, ha a CK Wire is látható és követhető ebből a nézőpontból. A vezetékek megjelenítésének és a blokkok tetején lévő portirányoknak össze kell illeniük.

A korábbi döntés továbbra is érvényes: **egy vezetékkerszteződés önmagában nem csomópont**; elágazáshoz/csatlakozáshoz külön csomópontblokk szükséges. A vizuális ábrázolás nem sugallhat hamis elektromos vagy gráfkapcsolatot.

## 7. Javasolt CK-05 ütemezés

**CK-05/B0 – read-only felderítés**

1. A CK-05/A2 tényleges eredményeinek és a meglévő port-/facing-szerződésnek a feltérképezése.
2. A START, STOP, IF, FOR, FUN aktuális blockstate/model/texture erőforrásainak felmérése.
3. A portazonosítók, lokális oldalak és forgatási szabályok dokumentálása; hiányok, ellentmondások azonosítása.
4. A jelenlegi CK Wire felülnézeti olvashatóságának vizsgálata.
5. Annak tisztázása, hogy a kívánt statikus megjelenítés megoldható-e tisztán resource-okkal, illetve hol kell minimális runtime-kiegészítés.
6. Javaslat egy kis, reprezentatív mintára és ellenőrzési tervre.

**CK-05/B1 – minimális vizuális prototípus (külön GO után)**

- Egy START és egy IF blokk jelöléseinek elkészítése.
- Oldalsó portjelölések és felső folyamatábra-szimbólumok.
- Forgatáspróba mind a négy vízszintes irányban.
- Bizonyítás, hogy a látható jelölés és a ténylegesen beköthető port megfelel egymásnak.
- Szükség esetén 16×16 és 64×64 összehasonlítás.

**CK-05/B2 – kiterjesztés (külön GO után)**

- További CK-blokkok, egységes vizuális nyelv.
- Felülnézeti Stage-olvashatóság és CK Wire kapcsolódási pontok finomítása.
- Resource-szervezési és dokumentációs konvenciók.

A B0/B1/B2 elnevezések **javasoltak**, az aktuális CK-05 tervvel ütközés esetén átnevezendők.

## 8. Nem cél ebben a munkacsomagban

- Új Python Graph domain vagy második Kotlin-gráfmotor.
- A portok szemantikájának megváltoztatása pusztán a grafika kedvéért.
- Külön 2D folyamatábra-nézet vagy önálló renderelő felület.
- Teljes textúrapakk vagy a vanilla Minecraft blokkjainak áttextúrázása.
- Dinamikus feliratok/állapotok automatikus bevállalása előzetes felderítés nélkül.

## 9. Elfogadási elv

A játékos egy tetszőlegesen elforgatott CK-blokkra nézve **iránytű, F3 és a Python-portmodell ismerete nélkül** felismeri, hová kell kötnie a vezetéket. Felrepülve a blokkok tetején lévő szimbólumokból és a látható CK Wire hálózatból követni tudja a program folyását.

**Döntési javaslat:** a CK-05/A2 lezárása után először csak a CK-05/B0 felderítés induljon; a prototípus külön jóváhagyást kapjon.
