"""Scalar diagnostic events only; never log secrets, full bodies or Graph content."""
import contextvars
import json
import logging
import os

logger=logging.getLogger("blockcommander.diagnostics")
if not logger.handlers:
    handler=logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s CK_DIAG %(message)s"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)
logger.propagate=False
context=contextvars.ContextVar("ck_diagnostic_context",default=None)


def emit(event,**fields):
    try:
        logger.info(json.dumps({**(context.get() or {}),"event":event,"process_id":os.getpid(),
            **fields},default=str,ensure_ascii=True,separators=(",",":")))
    except Exception:
        # Diagnostics must never alter an HTTP response or transaction outcome.
        pass
