"""Only the requested label changes."""
def status_label(status):
    labels = {"queued": "Queued", "working": "Working", "done": "Complete"}
    return labels.get(status, "Unknown")
