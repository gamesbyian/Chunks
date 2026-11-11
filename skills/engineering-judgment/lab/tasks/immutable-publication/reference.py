"""Reference: private POSIX staging and exclusive final-name link (not SAF)."""
from pathlib import Path
import os
import tempfile

def publish_original(chunks, destination):
    destination = Path(destination)
    fd, stage = tempfile.mkstemp(prefix=".staging-", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            for chunk in chunks:
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        os.link(stage, destination)
        return destination
    finally:
        os.unlink(stage)
