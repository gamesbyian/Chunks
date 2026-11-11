"""Reference: inspect both names and refuse to silently skip unknown impact."""
def requires_android_checks(changes):
    if changes is None:
        return True
    for row in changes:
        if not isinstance(row, dict):
            return True
        current = row.get("path")
        status = row.get("status")
        if not isinstance(current, str) or status not in {"added", "modified", "removed", "renamed"}:
            return True
        old = row.get("previous_path")
        if status == "renamed" and not isinstance(old, str):
            return True
        if current.startswith("android/") or (isinstance(old, str) and old.startswith("android/")):
            return True
    return False
