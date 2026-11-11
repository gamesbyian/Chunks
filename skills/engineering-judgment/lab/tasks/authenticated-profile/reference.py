"""Reference behavior: exact principal must be authenticated by a protected call."""
def connect(profiles, endpoint, profile_id, username, password):
    try:
        version = endpoint.version()
        principal = endpoint.authenticate(username, password)
    except (PermissionError, ConnectionError, TimeoutError):
        return False
    if principal != username:
        return False
    profiles[profile_id] = {"principal": principal, "version": version}
    return True
