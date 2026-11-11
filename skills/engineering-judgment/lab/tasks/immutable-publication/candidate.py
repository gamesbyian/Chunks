"""Production-like miniature: exclusive reservation but incomplete publication."""
from pathlib import Path

def publish_original(chunks, destination):
    destination = Path(destination)
    with destination.open("xb") as output:
        for chunk in chunks:
            output.write(chunk)
    return destination
