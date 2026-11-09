Ezeket a CK jelenlegi architektúrájához kötve érdemes megismerni.

|Fogalom|Kölyökcsimpánz-fordítás|
|---|---|
|Snapshot|Pillanatfelvétel. A Stage adott pillanatban érvényes, menthető állapota.|
|Immutable|Megváltoztathatatlan objektum. Módosítás helyett új példányt hozunk létre.|
|Copy-on-write|Először másolaton módosítunk; a régit csak siker esetén váltjuk le.|
|Atomic|Vagy minden módosítás megtörténik, vagy egyik sem. Nincs félkész eredmény.|
|Transaction|Adatbázis-műveletek összefogása egy közös sikeres vagy sikertelen egységbe.|
|Rollback|Sikertelen tranzakció visszavonása.|
|Idempotent|Ugyanazt a műveletet többször végrehajtva nem keletkezik újabb változás.|
|Lock|Zár. Amíg valaki kritikus műveletet végez, más nem módosíthatja ugyanazt az erőforrást.|
|Race condition|Versenyhelyzet. A végeredmény attól függ, melyik párhuzamos művelet ér oda előbb.|
|Invariant|Olyan szabály, amelynek mindig igaznak kell maradnia.|
|DTO|Adatátadó objektum. Egy rendszerkomponens így ad át strukturált információt a másiknak.|
|Adapter|Fordító két eltérő adatmodell között.|
|ValidationReport|Ellenőrzési jelentés: érvényes-e a gráf, és ha nem, miért nem.|
|Fail-closed|Ha nem tudjuk biztonságosan eldönteni, szabad-e valamit végrehajtani, inkább megtiltjuk.|

### Három fogalom, amelyet külön kiemelnék

Atomic: képzeld el, hogy egy CK Wire megsemmisítésekor a Minecraftból eltűnik a vezeték, de a PostgreSQL-ben megmarad a GraphEdge. Ezt szeretnénk elkerülni. A DB-n belüli atomikus tranzakciók ezt támogatják; a Minecraft világának és az adatbázisnak az együttes atomikus frissítése viszont külön integrációs feladat.

Idempotent: ha Öcsi kétszer küldi el ugyanazt a már sikeresen alkalmazott gráfműveletet, ne szülessen két GraphEdge. A megismétlés ne okozzon újabb változást.

Invariant: például egy GraphEdge nem hivatkozhat tetszőleges, nem létező portokra. Ez nem egyszerű felhasználói ajánlás, hanem a domain működésének alapfeltétele.