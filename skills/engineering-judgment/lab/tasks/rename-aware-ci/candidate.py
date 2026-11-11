"""Intentionally faulty GitHub-change impact selector."""
def requires_android_checks(changes):
    return any(row.get("path", "").startswith("android/") for row in changes)
