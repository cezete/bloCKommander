# CK-06/A4 – Program HTTP API

Az API verziója `ck-program-v1`. Minden végpont a meglévő
`X-BlockCommander-Graph-Secret` transportvédelmet használja. A JSON-válaszok
domainobjektumok másolatai; Python lock, repository-objektum vagy SQL-részlet
nem kerül a szerződésbe.

## Service lifecycle

Egy webhook-folyamat egyetlen `StageService` példányt hoz létre, ehhez egyetlen
`ProgramService` kapcsolódik. A `GraphApplicationService` ugyanezt a
`StageService` példányt kapja. Ezért a Stage HTTP API, a Graph API és a Program
API ugyanazokat a processzlokális `StageWorkspace` objektumokat és ugyanazt a
Program → Stage lock-sorrendet használja; nincs külön Program-workspace cache.

Ez a garancia folyamaton belüli. Több webhook-processz külön memóriával és
külön lockokkal rendelkezne. A PostgreSQL CAS megakadályozza a csendes
felülírást, de az A4 nem vezet be elosztott workspace-koordinációt. A jelenlegi
üzemeltetési modell ezért továbbra is egy webhook worker.

## Végpontok

| Metódus | Útvonal | Eredmény |
| --- | --- | --- |
| `GET` | `/api/v1/halls/{hall_id}/program` | Aktuális Hall mount és a teljes Program-nézet |
| `GET` | `/api/v1/programs/{program_id}` | Program identity, root, Stage-ek, linkek, mountok és aggregate állapot |
| `GET` | `/api/v1/programs/{program_id}/stages/{stage_id}/children` | ACTIVE és RETAINED child-linkek, valamint a child Stage-ek |
| `GET` | `/api/v1/programs/{program_id}/stages/{stage_id}` | Authoritative relatív Stage-projekció blokkokkal |
| `POST` | `/api/v1/programs/{program_id}/stages/{stage_id}/children/ensure` | Idempotens child ensure egy PROGRAM blokkhoz |
| `GET` | `/api/v1/programs/{program_id}/status` | Aggregate dirty, lifecycle, Program- és Stage-verziók |
| `POST` | `/api/v1/programs/{program_id}/save` | Tulajdonosi, atomi Program-SAVE receipt |

Child ensure kérés:

```json
{
  "player_uuid": "uuid",
  "program_block_instance_id": "uuid",
  "stage_name": "opcionális név"
}
```

Program-SAVE kérés:

```json
{
  "player_uuid": "uuid",
  "expected_saved_version": 3
}
```

A SAVE route nem tartalmaz mentési algoritmust: a validált kérést a
`ProgramService.save()` műveletnek adja át. Az `expected_saved_version` a kliens
stale kérését már a Program lock alatt elutasítja; a repository Program- és
Stage-CAS ellenőrzése ettől függetlenül megmarad.

## DTO-elválasztás és fencing

A Program DTO tartalmazza a Program ID-t, ownert, root Stage ID-t,
`saved_version`, `aggregate_dirty`, `workspace_epoch`, `hierarchy_revision`, `workspace_state`,
Stage-listát, ACTIVE/RETAINED linkeket és Hall mountokat. A betöltött Stage-ek
processzlokális `workspace.epoch`, `working_revision` és `dirty` értéket is
adnak. A child ensure és a SAVE válasz megismétli a Program identityt és a
releváns verziókat, így a későbbi Fabric runtime el tudja vetni a későn érkező
válaszokat.

A Stage `domain_layout.origin` kizárólag relatív Stage-koordináta. Hall/world
UUID és abszolút fizikai koordináta nem része a Program Stage DTO-nak. A mount
DTO csak a Hall–Program azonosságot fejezi ki; a fizikai Hall-adatok továbbra is
a meglévő Hall API szerződéséhez tartoznak.

## Hibák

Az error body mindig tartalmaz `api_version`, `status`, `error_code`, `message`
és `diagnostics` mezőt. A fő kategóriák:

- `INVALID_BODY`, `MISSING_FIELD`, `UNKNOWN_FIELD`, `INVALID_FIELD` – hibás transport DTO;
- `FORBIDDEN` – nem tulajdonosi mutáció vagy SAVE;
- `NOT_FOUND` – hiányzó vagy a Programhoz nem tartozó azonosító;
- `DOMAIN_CONFLICT` – hierarchia/domain invariáns sérülése;
- `STALE_VERSION` – kliens- vagy repository-verziókonfliktus;
- `RELOAD_REQUIRED` – commit utáni memóriabeli állapot nem használható tovább;
- `REPOSITORY_ERROR` – tárolási hiba, SQL-részlet nélkül;
- `INTERNAL_ERROR` – váratlan belső hiba, traceback nélkül.

Az A4 nem kapcsol Stage-et másik fizikai Hall-területre, nem végez Minecraft
világprojekciót, és nem valósít meg `switchGeneration` fencinget; ezek az A6/A7
runtime feladatai.

## A6 kiegészítés: relatív blokklerakás

A Hallhoz közvetlenül nem kötött `PROGRAM_BODY` Stage blokklerakása az explicit
`coordinate_space: "STAGE_RELATIVE"` mezővel Stage-relatív `x/y/z` értékeket
fogad. A régi, mező nélküli kérés továbbra is a korábbi világkoordináta-szerződést
használja. A válasz `rel_x/rel_y/rel_z` mezői hiteles domain-koordináták; a Fabric
ezeket az aktív Hall fizikai origójára vetíti, a Python-koordináták átírása nélkül.
