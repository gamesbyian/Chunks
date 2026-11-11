"""Production-like miniature: app profile Connect, intentionally faulty."""
def connect(profiles, endpoint, profile_id, username, password):
    version = endpoint.version()  # public, needs no credentials
    profiles[profile_id] = {"principal": username, "version": version}
    return True
