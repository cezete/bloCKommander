# CK-08/B3/C – Minecraft Block Editor GUI és network

Dátum: **2026-11-12**. Kiinduló/záró branch: **main**. HEAD: **797892ef5ad01e5f7171dc5e5225212ca3baee71**, `feat(CK-08): add fenced metadata reconciliation committer`. A working tree és index induláskor tiszta volt. A [B3/A transport](CK-08B3A-fabric-block-editor-transport.md), [B3/B session](CK-08B3B-block-editor-session.md) és [B3/B2 committer](CK-08B3B2-metadata-reconciliation-committer.md) commitolva volt. HEAD/index változatlan.

**Minősítés: PASS az implementáció és determinisztikus tesztek határán.** Élő Minecraft acceptance és vizuális/játékbeli ellenőrzés nem futott; ez külön kapu. Nincs commit, deploy, restart, production DB- vagy világmódosítás.

## Fájlok

Új:

- [EditorPackets.kt](../src/main/kotlin/me/buciqua/blockommander/network/EditorPackets.kt): közös prezentációs DTO-k, korlátos bináris codec és két Fabric payload.
- [EditorUiModel.kt](../src/main/kotlin/me/buciqua/blockommander/network/EditorUiModel.kt): Minecraft-client import nélküli, tesztelhető draft/state/routing modell és kliens connection fence.
- [BlockEditorNetworking.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorNetworking.kt): szerveroldali intent-router és session → prezentációs állapot leképezés.
- [BlockEditorInteraction.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorInteraction.kt): registryből feloldott START/STOP célpont, legacy írás tiltása e két típusra.
- [BlockEditorScreen.kt](../src/client/kotlin/me/buciqua/blockommander/client/screen/BlockEditorScreen.kt): fizikai kliens GUI.
- [BlockEditorGuiNetworkTest.kt](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorGuiNetworkTest.kt): 50 codec/router/UI/target teszt.
- [BlockEditorGuiRoundtripTest.kt](../src/test/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorGuiRoundtripTest.kt): 5 átmenő packet/session/UI teszt.
- Ez a jelentés.

Módosított:

- [Interactions.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt): START/STOP jobb kattintás átvezetése; régi form submit blokkolása START/STOP-ra.
- [Networking.kt](../src/main/kotlin/me/buciqua/blockommander/network/Networking.kt): payload-regisztráció, szerver-executoros dispatch.
- [BlockCommanderMod.kt](../src/main/kotlin/me/buciqua/blockommander/BlockCommanderMod.kt): fencelt sessionállapotok küldése, játékosonként növekvő értesítési sequence.
- [BlockEditorSessionRuntime.kt](../src/main/kotlin/me/buciqua/blockommander/runtime/editor/BlockEditorSessionRuntime.kt): hiteles, új sessiont nyitó reload belépés.
- [BlockCommanderClient.kt](../src/client/kotlin/me/buciqua/blockommander/client/BlockCommanderClient.kt): kliens receiver, connection/world lifecycle és képernyőrouting.

A CkFormScreen változatlan; csarnoklétrehozáshoz és a többi meglévő blokkformhoz továbbra is használható. A közös prezentációs DTO/model publikus, mert a külön client source set nem látja a main Kotlin internal típusait. Nem jelent szerveroldali jogosultságot vagy új domain API-t. Minden fizikai klienskód src/client alatt van; src/main nem importál net.minecraft.client/Fabric client osztályt.

## Interakció és a régi írási út lezárása

**Belépés:** főkézzel jobb kattintás egy megjelenített CK START vagy STOP blokkon, üres kézzel vagy normál tárggyal. A CK Wire item és a csarnokkijelölő kard meglévő speciális prioritása megmarad, velük nem az editor nyílik. PROGRAM jobb kattintás továbbra is Stage-navigáció.

A szerver a tényleges világblokkból és a HallRegistry megjelenített Stage-blokklistájából oldja fel az instance UUID-t. Kötelező a Hall owner/world egyezés, azonos Hall/transport identity, megfelelő fizikai blokktípus, editingAllowed és navigation-in-progress kizárása. A runtime ezután az aktuális ServerPlayerből végzi a B3/A capture-t és a B3/B kapcsolat-/Hall-/Stage-fence ellenőrzését. A végső owner jogosultságot továbbra is Python ellenőrzi.

A régi út `OpenFormPayload(kind=block)` → CkFormScreen → SubmitFormPayload → legacy Stage update volt, CAS nélkül. **START/STOP-ra ezt lecseréltük**, nem épült párhuzamos írható út: a szerver a régi, akár kézzel hamisított form submitot is registryből feloldja és START/STOP esetén HTTP-hívás előtt elutasítja. A régi endpoint és a többi blokk működése változatlan. Ezzel a T0 STOP-feltétel nem áll fenn.

A kliens nem küld külön editor OPEN payloadot: a meglévő vanilla blokkinterakció az indító művelet, a célfeloldás szerveroldali. A nyitás GET-et indít; nem navigál, nem ír Graphot és nem ment. Az új payloadot nem támogató régi kliens hibaüzenetet kap, nincs legacy START/STOP fallback.

## Packet-szerződés és trust boundary

Két payload: **blockommander:editor_intent_v1** C2S, **blockommander:editor_state_v1** S2C. Belső envelope-verzió **1**. Mindkét payload hosszprefixelt byte-array, legfeljebb **16384 byte**; a korlátot a Fabric buffer még a tömb kiolvasásakor ellenőrzi. A belső decoder is korlátos; ismeretlen verzió/tag, hibás UTF-8, hiányos mező, érvénytelen boolean, negatív/hibás hossz, trailing belső vagy külső adat elutasított. Nincs JSON-kulcsok elnéző figyelmen kívül hagyása.

| Irány | Adat |
|---|---|
| C2S | Teljes SessionKey: player UUID, connection-generation, session-generation, nonce; APPLY/CLOSE/RELOAD/RECONCILE action. |
| APPLY | Kizárólag label/description patch: MISSING / VALUE / CLEAR. Label CLEAR tiltott, üres patch tiltott. |
| Más C2S action | Mindkét patch MISSING; változtatás nem rejthető control actionbe. |
| S2C | Teljes SessionKey, növekvő sequence, state, START/STOP típus, authoritative schema/values, canApply, diagnosztika és failure-code, külön projection/recovery/closed/dirty információ. |

**Nincs C2S Hall/Program/WC/Stage/instance target, schema ID/version, epoch, working revision vagy saved-version.** A SessionKey player UUID-ja összevetésre szolgál; a hiteles játékost a networking ServerPlayer adja. A teljes azonosító soha nem önálló jogosultsági token: a router után a runtime ismét ellenőrzi a tényleges kapcsolatot, aktuális sessiont és lifecycle-t. Idegen/stale id nem indít műveletet és másik játékosnak nem ad állapotot.

A bináris C2S stringkorlát labelnél **128**, descriptionnél **1024 UTF-16 egység**, UTF-8 byte-hosszellenőrzéssel és strict dekódolással. Ez transportkorlát; a tényleges **1–32 / 0–256 Unicode kódpont** domainkorlát az authoritative sémából érkezik. A bővebb UTF-16 keret nem vágja félbe a 32/256 emoji-párt. Mezőérték-normalizálás továbbra is Python feladata. S2C legfeljebb 16 diagnosztikát, üzenetenként legfeljebb 256 egységet továbbít; kód/cím/sémaazonosító is külön korlátos. Nincs secret vagy HTTP-auth adat a packetben.

A v1 schema-prezentáció a B3/A által támogatott STRING, NONE default, UNICODE_CODE_POINT, PYTHON_STRIP_V1 és NONE Unicode-normalizáció keretében működik; ezek a protokollváltozat rögzített támogatott tulajdonságai. A schema reference, két mező címe, sorrendje, min/max, required/nullable/readable/editable és emptyToNull az elfogadott szerver GET-ből jön. Új Expression/REFERENCE/PARAMETER_LIST modell nincs.

A network callback explicit szerver-executorra ütemez. Világ/registry írást kizárólag a session/coordinator/committer lánc végez. A régi SubmitForm receiver is main threadre ütemez. A kliens receive explicit kliens-executoron fut; a sorba állított callback a tényleges kapcsolatobjektumhoz kötött. Régi disconnect nem érvénytelenítheti az új kapcsolatot.

## GUI és helyi draft

A külön BlockEditorScreen szükséges volt: a régi CkFormScreen beküldéskor azonnal bezár, nincs szervereredmény-, session- vagy reconciliation-kezelése. Az új képernyő nem ír világot, registryt vagy authoritative CAS-t.

Megjelenik a típus/állapot, label/description mező és schema-korlát, mezőhiba, domainstátusz, külön projekciós státusz/reason, GET-folyamatjelző és a Program-SAVE-tól való különbség. A szövegmezők egy sorosak; a státuszsorok a képernyő szélességéhez vizuálisan rövidülhetnek. A teljes bounded diagnosztika a modellben, a committer reason a szerver CK_DIAG logban megmarad. A description explicit null gombot kap; üres szöveg és null külön draftállapot. A recovery aktuális értékei külön packet/model mezőben maradnak; UNKNOWN alatt nem írják felül a helyi vázlatot. Az újratöltés mutatja meg szerkeszthető új sessionben az aktuális authoritative értékeket.

| Állapot | GUI-viselkedés |
|---|---|
| OPENING | Csak folyamatjelzés, nincs szerkeszthető authoritative adat. |
| EDITING | Szerver canApply és mezőszintű editable szerint szerkeszthető. |
| PENDING | Draft megmarad; mezők/APPLY tiltva. Lokális beküldés után már a szerver ACK előtt is PENDING jelzés. |
| VALIDATION_ERROR | Diagnosztika és draft megmarad; javított beküldés megengedhető, a szerver az eredeti basisból dönt. |
| ACCEPTED | Authoritative normalizált értékek, APPLY tiltva új GET-ig. Nem Program-SAVE. |
| CONFLICT | Nincs merge/CAS-frissítés; explicit újratöltés szükséges. |
| UNKNOWN | Nincs sikerüzenet/APPLY retry; helyi draft megmarad, recovery és projekció külön. |
| CLOSED | Mezők nem szerkeszthetők; új editort új fizikai interakció nyit. |

Az üres label és túl hosszú/hibás Unicode draft lokálisan nem küldhető. A kliens nem trimel: például a csupa szóközű label tényleges domainvalidációt kaphat, a szerver nem módosító VALIDATION_FAILED válasza után megmarad a bevitel. Csak a megváltozott mezők kerülnek a patchbe; explicit null CLEAR, üres szöveg VALUE(""). Nem módosított draftnál az APPLY tiltott; a backend no-op szerződése változatlan és a korábbi tesztekben lefedett.

## Routing, CLOSE, RELOAD és reconciliation

Sessionváltáskor a teljes player/connection/generation/nonce egyezés és a generációk sorrendje számít. Azonos sessionben csak növekvő sequence fogadható el. Régi CLOSED nem zár új képernyőt, azonos generáció idegen nonce nem cseréli le az ablakot, lokálisan bezárt session nem nyílhat vissza késői nem terminális választól. Egy korábban keletkezett EDITING állapot nem oldja fel a lokálisan PENDING APPLY gombját; a szerver a második APPLY-t ettől függetlenül is kizárja.

**CLOSE/ESC/más képernyő:** sessionzárás, nincs rollback/SAVE. PENDING alatt bezárva az elindult Python APPLY még megtörténhet. Disconnect/world-change a kliensmodellt is lezárja; a szerver Hall/Stage/navigation/selection/reload fence-ei változatlanul érvénytelenítenek.

**RELOAD:** változott draftnál az első kattintás megerősítést kér, a második, „Vázlat eldobása” kattintás küldi el. A bezárás gomb ebben a megerősítési helyzetben először visszalép a draftba. A szerver kizárólag a jelenlegi sessionben tárolt hiteles célpontra nyit új sessiont és új authoritative GET-et. OPENING/PENDING/CLOSED állapotból tiltott. Régi id callbackjei leválnak. A registry epochját megváltoztató WH/reconnect helyreállítást ez nem kerüli meg: tartós epoch-konfliktusnál továbbra is a meglévő Stage-reload út szükséges.

**RECONCILE:** a „Projekció frissítése” gomb a B3/B2 reconcileAgain függvényt hívja ACCEPTED/UNKNOWN állapotban. Nem küld APPLY-t. Nincs automatikus retry-loop.

Az APPLY → authoritative Python eredmény → Stage/Graph GET → B3/B2 committer sorrend változatlan. ACCEPTED + FAILED külön domain- és projekciós eredmény. UNKNOWN + COMMITTED továbbra is bizonytalan APPLY: a projekció az aktuális állapot megjelenítését igazolja. A SAVE-boardot a meglévő committer frissíti; a GUI nem írja és nem állít explicit Program-SAVE nélkül mentést. Nem változott Wire/Graph motor vagy SAVE-szerződés.

## Tesztek és build

Repository gyökeréből, Java 25:

```powershell
$env:JAVA_HOME="$PWD\.tools\jdk-25"
.\gradlew.bat --offline compileKotlin --console=plain
.\gradlew.bat --offline compileClientKotlin --console=plain
.\gradlew.bat --offline test --tests '*editor.BlockEditor*' --console=plain
.\gradlew.bat --offline build --console=plain
git diff --check
```

| Ellenőrzés | Végső eredmény |
|---|---|
| Új codec/router/UI/interaction teszt | 50 PASS. |
| Új packet → valódi session coordinator → UI teszt | 5 PASS. |
| B3/B2 committer integráció | 47 PASS. |
| B3/B session + assembler | 55 + 9 PASS. |
| B3/A DTO + HTTP/transport | 15 + 17 PASS. |
| Teljes Kotlin készlet | **399 PASS, 28 suite, 0 failure/error/skipped.** |
| Java 25 offline build | PASS; main/client fordítás, JAR, sourcesJar. |
| Diff és új fájlok whitespace | PASS. |
| Új dokumentum relatív linkjei | PASS. |
| Minecraft vizuális/játékbeli acceptance | **NEM FUTOTT**, nem fake adapteres PASS. |

A célzott editor futás az első 49 új GUI/network esettel 192 tesztet futtatott. Az 5 átmenő és az utolsó PENDING-sorrendiség teszt a végső teljes buildben is lefutott; az ismétléseket nem számoljuk új esetnek. A regresszióban GraphProjectionStore 11, StageSwitchProjection 7, WireInteractionCoordinator 22, HallRegistryGraph 20, HallSelectionCoordinator 19, StageNavigationCoordinator 14, StageReconnectReconciler 4 és a többi meglévő készlet is PASS.

A tesztek valódi binary/Fabric codecet és session coordinatort használnak, fake kliens-/transport-/projection adapterekkel. A valódi B3/B2 registry/store/Wire komponensek korábbi 47 integrációs tesztje is lefutott. Ez nem futó Minecraft kliens képernyőjének render-/inputtesztje. A Kotlin source-set láthatósági és a helyi screen()/setEditable() API-illesztési fordítási eltéréseket a végső build előtt javítottuk. Nem változott buildkonfiguráció vagy dependency.

Python/PostgreSQL teszt nem futott: e rétegek változatlanok. Minecraft/WH/production szolgáltatás nem indult. A tesztlogger helyi latest.log mellékhatását az induló, Git-baseline-nal ellenőrzött bájtokra visszaállítottuk; csak az általunk létrehozott, ellenőrzött archívumokat távolítottuk el. Nincs log/build output a záró diffben.

## Minecraft acceptance terv – későbbi külön kapu

**Előfeltétel:** külön jóváhagyással telepített azonos B3/C kliens/szerver build, stabil WH, saját Hall, betöltött megfelelő Stage, sértetlen START/STOP/Wire/SAVE-sign, érvényes Graph és nyugalmi navigation. A telepítést és e világmódosító teszteket most nem hajtottuk végre. A bizonyítékhoz a CK_DIAG időablakot és a pontos verziót rögzíteni kell.

| Eset / előfeltétel | Kézi művelet | PASS / FAIL kritérium |
|---|---|---|
| A1 – Saját START, normál főkéz | Jobb kattintás. | OPENING → EDITING, authoritative label/description és korlátok; nincs navigation/SAVE/Graph-írás a nyitástól. |
| A2 – EDITING | Label és description módosítása, egy APPLY; közben újabb kattintás. | Draft megmarad PENDING alatt, egy backend APPLY, authoritative ACCEPTED; külön projekció COMMITTED vagy őszinte FAILED. |
| A3 – Saját STOP nem null descriptionnel | Description null gomb, APPLY. | Authoritative null jelenik meg új GET után; label változatlan, nincs automatikus SAVE. |
| A4 – EDITING | Üres label, majd 33 Unicode kódpont, majd legális szöveg. | Az első kettő nem küldhető, a harmadik igen; emoji-pár nem számít két kódpontnak. |
| A5 – Valódi domainvalidáció | Legalább egy szóköznyi label, APPLY; hiba után javítás. | VALIDATION_ERROR és draftmegőrzés; javított APPLY lehetséges, nincs köztes mutation/SAVE a hibától. |
| A6 – Módosított draft | Újratöltés egyszer; megerősítés megszakítása; újra és megerősítés. | Első kattintás nem dob draftot; megerősítve új SessionId/GET, friss authoritative érték, régi válasz nem hat. |
| A7 – Nyitott vagy elfogadott session | Bezárás/ESC, majd ismét jobb kattintás. | Nincs rollback/SAVE; új session nyílik az aktuális domainértékkel. |
| A8 – Sikeres metadata APPLY | GUI bezárás, engedélyezett Wire break/place-intent/bind. | A WIRE_COMMAND_SEND új authoritative epoch/revisiont használ, az editor commit nem hozott létre Edge-et és nem rakta újra a Wire-t. A szándékos későbbi wire-művelet hatása külön értékelendő. |
| A9 – DIRTY metadata | SAVE-board megtekintése, új editor GET. | NINCS MENTVE, saved-version nem nőtt metadata APPLY-tól; nincs automatikus Program-SAVE. |
| A10 – Más owner / navigation / speciális tárgy | Idegen blokk jobb kattintás; külön Wire item/kijelölő kard/PROGRAM próba. | Idegen editor nem nyílik; speciális interakciók és navigation meglévő prioritása változatlan. |
| A11 – Nyitott editor | Disconnect/reconnect vagy szabályos world-/Hall-/Stage-váltás. | Régi session nem marad szerkeszthető, régi CLOSED/válasz nem érinti az új ablakot. |
| A12 – Izolált, vezérelt backend hiba | Konfliktus, timeout, késleltetett completion vagy board/projection hiba előidézése. | CONFLICT/UNKNOWN/ACCEPTED+FAILED elkülönül; nincs auto APPLY/SAVE. **BLOCKED kézi fault-injection környezet nélkül.** A unit teszt PASS ezt nem oldja fel. |

A12-höz nincs új játékosparancs vagy hibainjektáló API. Egy játékos egyszerre csak egy sessiont tarthat, ezért két ugyanazon ownerhez tartozó editor párhuzamos nyitására nem építünk konfliktusreceptet. A szabályos navigáció GUI-ból kilépést igényelhet; PENDING alatti lifecycle/callback-sorrend erőltetéséhez szintén izolált vezérlés szükséges. A későbbi acceptance során a képernyőméretet, szövegrövidítést, fókuszt, Unicode-bevitelt és az egysoros description használhatóságát is vizuálisan ellenőrizni kell.

## Scope és STOP

A záró working tree öt meglévő fájl módosítását és nyolc új fájlt tartalmaz. A korábbi session/committer/domain szerződést nem lazítottuk, csak a runtime hiteles reload belépése bővült. Nincs blokkmozgatás, webes editor, új blokkfajta, Python-domain/HTTP vagy SQL-változás, automatikus SAVE/PUBLISH. Nincs production deploy/restart, DB-/világírás vagy Git-commit.

**STOP – projektgazdai felülvizsgálatra.** A kézi Minecraft acceptance megkezdése előtt szükséges a külön telepítési döntés és a fenti előfeltételek ellenőrzése.
