"""HTTP identity validation, independent of Stage/Graph mutation and workspace loading."""
from dataclasses import dataclass
from uuid import UUID
import psycopg2

from .repository import HallConflict, HallNotFound
from .working_copy import WorkingCopyContext


CONTEXT_HEADERS={
    "hall_id":"X-BlockCommander-Hall-Id",
    "program_id":"X-BlockCommander-Program-Id",
    "working_copy_id":"X-BlockCommander-Working-Copy-Id",
}


@dataclass(frozen=True)
class TransportContextError(Exception):
    status_code:int
    code:str
    message:str


def identity(value,field):
    try:return UUID(str(value))
    except (ValueError,TypeError) as exc:
        raise TransportContextError(400,"INVALID_CONTEXT_ID",f"Hibás UUID: {field}.") from exc


@dataclass(frozen=True)
class TransportContext:
    mode:str
    program_id:UUID
    stage_id:UUID|None=None
    mount:WorkingCopyContext|None=None

    def as_dict(self):
        result={"mode":self.mode,"program_id":str(self.program_id),"content_scope":"SHARED_PROGRAM"}
        if self.mount is not None:result.update(self.mount.as_dict())
        if self.stage_id is not None:result["stage_id"]=str(self.stage_id)
        return result


class TransportContextResolver:
    def __init__(self,repository,programs,*,allow_legacy=True):
        self.repository=repository
        self.programs=programs
        self.allow_legacy=allow_legacy

    @staticmethod
    def explicit(headers):
        present={key:headers[header] for key,header in CONTEXT_HEADERS.items() if header in headers}
        if not present:return None
        if len(present)!=len(CONTEXT_HEADERS):
            raise TransportContextError(400,"INCOMPLETE_CONTEXT","Mindhárom context-header kötelező.")
        return {key:identity(value,key) for key,value in present.items()}

    def resolve(self,headers,*,hall_id=None,program_id=None,stage_id=None):
        explicit=self.explicit(headers)
        if explicit is None and not self.allow_legacy:
            raise TransportContextError(409,"CONTEXT_REQUIRED","Explicit WorkingCopy-context szükséges.")
        hall=None if hall_id is None else identity(hall_id,"hall_id")
        program=None if program_id is None else identity(program_id,"program_id")
        stage=None if stage_id is None else identity(stage_id,"stage_id")
        mount=None
        try:
            if explicit is not None:
                if ((hall is not None and hall!=explicit["hall_id"])
                        or (program is not None and program!=explicit["program_id"])):
                    raise TransportContextError(409,"CONTEXT_MISMATCH","A kérés célja eltér a contexttől.")
                mount=self.repository.resolve_working_copy_context(**explicit)
            elif hall is not None:
                mount=self.repository.resolve_working_copy_context(hall,program_id=program)
            if mount is not None:program=mount.program_id

            # Only new, not-yet-persisted Stage IDs may be resolved from existing memory.
            # This accessor never loads or installs a workspace.
            stage_program=None
            if stage is not None:
                stage_program=self.programs.transient_stage_program_id(stage)
                if stage_program is None:
                    loaded=self.repository.load_program_for_stage(str(stage))
                    if not any(identity(row["stage_id"],"stage_id")==stage for row in loaded.stages):
                        raise HallNotFound("Nincs ilyen Stage.")
                    stage_program=identity(loaded.program["program_id"],"program_id")
                if program is not None and program!=stage_program:
                    raise TransportContextError(409,"STAGE_PROGRAM_MISMATCH","A Stage másik Programhoz tartozik.")
                program=stage_program
            if program is None:
                raise TransportContextError(400,"CONTEXT_REQUIRED","A Program kontextusa hiányzik.")
            if mount is None:
                loaded=self.repository.load_program(str(program))
                if len(loaded.mounts)>1:
                    raise TransportContextError(409,"CONTEXT_REQUIRED",
                        "Több Hall mountolja a Programot; explicit Hall–Program–WorkingCopy context szükséges.")
                # Legacy Program scope: do not infer or claim a caller Hall/WorkingCopy.
                return TransportContext("legacy_program",program,stage)
            return TransportContext("explicit" if explicit is not None else "legacy_hall",program,stage,mount)
        except HallNotFound as exc:
            raise TransportContextError(404,"CONTEXT_NOT_FOUND",str(exc)) from exc
        except HallConflict as exc:
            raise TransportContextError(409,"CONTEXT_MISMATCH",str(exc)) from exc
        except psycopg2.Error as exc:
            raise TransportContextError(503,"CONTEXT_REPOSITORY_UNAVAILABLE",
                "A context adatbázisból történő ellenőrzése nem érhető el.") from exc
