"""Behavioral contract, not a prescription for implementation details."""
import unittest
from candidate import connect

class Endpoint:
    def __init__(self, credentials):
        self.credentials = credentials
        self.auth_calls = []
    def version(self):
        return "remote-1.2"  # public even for invalid credentials
    def authenticate(self, username, password):
        self.auth_calls.append((username, password))
        if self.credentials.get(username) != password:
            raise PermissionError("wrong credentials")
        return username

class Tests(unittest.TestCase):
    def test_valid_credentials_bind_exact_account(self):
        endpoint = Endpoint({"agent ": "works"})
        profiles = {}
        self.assertTrue(connect(profiles, endpoint, "remote1", "agent ", "works"))
        self.assertEqual(profiles["remote1"]["principal"], "agent ")
        self.assertEqual(endpoint.auth_calls, [("agent ", "works")])
    def test_public_version_is_not_authentication(self):
        endpoint = Endpoint({"user": "correct"})
        profiles = {}
        self.assertFalse(connect(profiles, endpoint, "remote1", "user", "wrong"))
        self.assertEqual(profiles, {})
        self.assertEqual(len(endpoint.auth_calls), 1)
    def test_failed_reconnect_preserves_prior_verified_profile(self):
        endpoint = Endpoint({"owner": "correct"})
        profiles = {"remote1": {"principal": "old", "version": "0.9"}}
        self.assertFalse(connect(profiles, endpoint, "remote1", "owner", "wrong"))
        self.assertEqual(profiles["remote1"], {"principal": "old", "version": "0.9"})

if __name__ == "__main__":
    unittest.main()
