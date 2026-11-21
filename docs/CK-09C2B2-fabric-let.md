# CK-09/C2/B2 – LET blokk és VALUE_BODY navigáció Fabricben

A LET a CK fülön, saját BlockItemmel jelenik meg. A kardinális FACING elöl
a cián CONTROL `out`, hátul a narancs CONTROL `in` foglalatot mutatja.
A Python hagyja jóvá a lerakást és a bontást. A szerver a releváns Stage és
Graph lekérés egyezése után vetíti a blokkokat és a Wire-okat.

## Kötés és navigáció

1. A jobb kattintás megnyitja a LET ablakot. A szerver a blokk UUID-jára lekéri
   a `ck-value-v1` állapotot és a `/symbols` lapot. Azonos Program/Stage
   verzió és resolution-basis esetén jelenik meg a lap. A listán név, típus,
   VAR/VAL és scope-eredet szerepel, a deklaráló Stage és UUID a sor tooltipjén
   látható.
2. Az **Alkalmaz** a kijelölt UUID-t küldi. A Fabric szerver teszi mellé a
   sessionben tárolt Stage epochot, working revisiont és scope-digestet.
   A Python PATCH nyugtája után friss Stage/Graph projekció és konfigurációs
   GET egyezteti a szerverállapotot. Az Alkalmaz mentetlen kötést készít.
3. A **VStage megnyitása** külön művelet. A Fabric a
   `POST …/children/ensure` kérésben az explicit `LET_RHS` szerepet küldi.
   Az aktív linknek a kért LET-től az adott `VALUE_BODY` Stage-re kell mutatnia.
   A szerver az üres, C1 sémajelzővel ellátott gyereket az ép Stage-switch
   mechanizmussal vetíti. A `/ckback` az egyetlen aktív szülőlinket követi.
   A PROGRAM és PROGRAM_BODY a korábbi útvonalon marad. Régi PROGRAM DTO-ban
   az elhagyott owner-role jelentése `PROGRAM_BODY`.

A request UUID-k szerveroldalon keletkeznek. Az azonos session késői vagy
duplikált csomagját a műveletsorszám kizárja. A kliens a sessionhez tartozó
műveletet és a kiválasztott UUID-t küldi. Elavult basis esetén friss lapról
kell kiválasztani a célt. Bizonytalan HTTP-műveletnél a képernyő
`UNKNOWN`-t jelez, és GET-tel egyeztet. Az írás automatikus ismétlése elmarad.
Bezárt session már nem alkalmazhat beérkező nyugtát. A korábban elindított,
Pythonban sikerült írás egy új megnyitáskor vagy reconnectkor visszaolvasható.

## Projekció, reconnect és SAVE

A Stage és Graph GET a megjelenített vagy megnyitandó Stage UUID-jára fut.
A commit összeveti a Hall–Program–WorkingCopy contextet, a világot, a Stage
epochot és revisiont, a sémajelzőket, valamint az elérhető Program epoch,
hierarchy revision, mentett verzió és dirty mezőket. A Stage- és Graph-kérések
a meglévő Hall, navigációs, Graph lifecycle és SAVE/status generációkhoz
kötöttek. A szerver szálán futó világművelet hiba esetén visszaállítja az előző
fizikai állapotot. Az üres VALUE_BODY nem kap CONTROL Wire-t. A nem üres VALUE
gráf fizikai megjelenítése a C2/C feladata.

Élő Fabric registry mellett a reconnect a megjelenített Stage értékét rögzíti
és ugyanarra a Stage-re olvas vissza. A Hall-listából érkező, külön kiválasztott
Stage nem cseréli le. Ha a mentetlen megjelenített gyerek 404-et ad és a
hiteles Program-lista is igazolja a hiányát, az aktuális Hall selection
külön, védett Stage-switch-csel állhat vissza. Teljes Fabric-szerverinduláskor
a megjelenített Stage a perzisztált Hall selection. A LET ugyanazzal a mentett
gyerek-UUID-val nyitható meg. A navigáció egyik irányban sem írja át a
Hall selectiont és nem ment.

A fizikai SAVE gomb az éppen megjelenített Stage bélyegeivel a Python
Program-wide SAVE műveletét hívja. A tábla Program aggregate dirty alapján
frissül. A SAVE working revision növelése nélkül is sikerülhet, ezért egy
régebbi válaszhoz az egyező revision önmagában kevés. Az üres VStage menthető.

## Izolált Minecraft acceptance

A játékbeli próba és bármely telepítés külön projektgazdai engedélyt igényel.
A tesztkörnyezetben egyező Fabric szerver- és kliensmod, B1-kompatibilis
webhook, konfigurált közös titok és a meglévő 012 séma szükséges. A fizikai
`/ckbind` próbához parancsjog kell.

1. Nyiss meg egy mentett PROGRAM_BODY Stage-et. A CK fülről rakj le LET-et,
   ellenőrizd az orientációt és hogy a SAVE-tábla mentetlen állapotot jelez.
2. Jobb kattintás után válassz előbb VAR, majd VAL UUID-t. Alkalmazás után
   nyisd újra a modalt és ellenőrizd a megőrzött kötést. Próbálj örökölt és
   shadowed sort, illetve lapozást is. Nézd meg, hogy az Alkalmaz után új
   gyerek nélkül is elhagyható a modal.
3. Nyisd meg a VStage-et. Ellenőrizd, hogy üres. `/ckback` után nyisd meg
   újra, és HTTP-n ellenőrizd, hogy ugyanaz a child UUID. A Hall selection
   revision és a Program saved version maradjon változatlan a belépésektől.
4. Nyomj SAVE-et. Ellenőrizd a mentett státuszt és a hozzá tartozó LET, link
   és Stage azonosságokat. Tesztelj webhook restartot és Minecraft
   reconnectet, miközben a már megjelenített VStage eltér a Hall
   selectiontől. Ismételd meg külön mentetlen gyerekkel és ellenőrizd a
   visszatérést a mentett kiválasztásra. Teljes Minecraft-szerverrestart után
   a perzisztált Hall selectionből indulj.
5. Építs `START → LET → STOP` CONTROL-útvonalat minden blokk helyes
   orientációjával. Rakj le két CK Wire útvonalat, majd futtasd a `/ckbind`-ot.
   Ellenőrizd az Edge/Route/WireCell adatokat és a fizikai megjelenítést.
   Ments, lépj vissza és újra be, majd izolált HTTP REBUILD után reconnecttel
   ellenőrizd az egyezést. Bonts le egy Wire-t, építsd vissza és kösd újra.
6. Bontsd le a LET-et és ellenőrizd, hogy a fizikai blokk és a hozzá tartozó
   edge-ek eltűntek, a mentett gyerek `RETAINED`. Próbáld végig a korábbi
   PROGRAM, START/STOP és deklarációs GUI útvonalakat.

## Deploy és rollback

Telepítés előtt futtass Java 25-tel `gradlew.bat test build` parancsot, és
ellenőrizd a B1 API és a 012 séma elérhetőségét az elkülönített környezetben.
Ha már mentett LET vagy VALUE_BODY létezik, a rollback célja őrizze meg annak
olvasását, projekcióját és Program-wide mentését. A régi, LET-et nem ismerő
Fabric bináris és a VALUE előtti Python writer csak VALUE-adat nélküli
állapotban alkalmas teljes visszaállásra.
