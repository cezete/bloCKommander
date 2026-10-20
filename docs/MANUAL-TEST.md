# Játékbeli kézi ellenőrzés

1. Kliens és szerver: Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, `blockommander-fabric-0.1.0.jar`.
2. Kreatív inventoryban nyisd meg a **CK** fület. Ellenőrizd a Hangár Kardot és az öt blokkot. Barkácsasztalon próbáld ki a két függőleges aranyrúd + alattuk bot + a bot mellett redstone receptet; redstone nélkül továbbra is vanilla aranykard az elvárt eredmény.
3. Húzd hotbarra az IF/FOR/FUN blokkokat. Ezek egyértelmű vizuális alapblokkok, végrehajtási szemantikájuk nincs; Stage-en kívüli lerakásukat a szervernek el kell utasítania.
4. Hangár Kard: bal kattintás A pont, jobb kattintás B pont. Töltsd ki a saját képernyőn a kódot/nevet. Ellenőrizd a falat, üreget, ablakokat, fényeket és bejáratot.
5. A létrejött Stage-en rakj le, forgass el, majd törj ki START/STOP/IF/FOR/FUN blokkokat.
6. Jobb kattintással szerkeszd a feliratot/leírást; töréssel távolíts el blokkot. A MENTÉS előtt a DB snapshot ne változzon.
7. Másik játékossal próbáld a szerkesztést, MENTÉST és DESTROY-t: elvárt HTTP 403 és változatlan világ/DB.
8. Tulajdonosként MENTÉS, majd szolgáltatás- és szerver-újraindítás után ellenőrizd a típus, pozíció, irány, felirat és leírás visszaépítését.
9. Ellenőrizd `curl http://127.0.0.1:5001/health` válaszában a `world_driver: fabric` értéket.

Ezek a lépések valódi játékosklienst igényelnek; a helyi build és unit teszt önmagában nem minősíti őket kipróbáltnak.
