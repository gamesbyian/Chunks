"""Ordinary display copy, not a storage or lifecycle problem."""
def status_label(status):
    labels = {"queued": "Queued", "working": "Working", "done": "Done"}
    return labels.get(status, "Unknown")
