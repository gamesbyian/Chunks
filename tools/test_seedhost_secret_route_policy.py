"""Offline guard: primary Seedhost credentials must never follow a dispatch URL.

This is a repository policy regression test, not an authenticated network check.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
PRIMARY = "https://ianseeds.wood.seedhost.eu/rutorrent/"
WORKFLOWS = {
    "seedhost-kotlin-adapter-smoke.yml": ("CHUNKS_LIVE_RUTORRENT_URL", 1),
    "seedhost-public-add-smoke.yml": ("SERVER_URL", 1),
    "seedhost-android-readonly.yml": ("CHUNKS_LIVE_RUTORRENT_URL", 2),
    "seedhost-readonly-probe.yml": ("RUTORRENT_URL", 1),
}


def assert_primary_binding(source: str, key: str, count: int) -> None:
    # All primary-secret-bearing steps must have the approved absolute
    # credential-free HTTPS endpoint, not a user-influenced expression.
    if re.search(r"(?m)^\s*rutorrent_url\s*:", source):
        raise ValueError("Untrusted ruTorrent URL dispatch input")
    if "inputs.rutorrent_url" in source:
        raise ValueError("User-controlled credential destination")
    actual = re.findall(rf"(?m)^\s*{re.escape(key)}:\s*(.*)$", source)
    if actual != [f'"{PRIMARY}"'] * count:
        raise ValueError(f"Expected {count} exact primary bindings for {key}, got {actual!r}")


class ProtectedSeedhostCredentialRoutesTest(unittest.TestCase):
    def test_live_workflows_pin_primary_credentials_to_approved_origin(self):
        for name, (env_key, count) in WORKFLOWS.items():
            with self.subTest(name=name):
                source = (ROOT / ".github" / "workflows" / name).read_text("utf-8")
                self.assertIn("secrets.rtorrent_pass", source)
                assert_primary_binding(source, env_key, count)
                if name == "seedhost-readonly-probe.yml":
                    self.assertIn('parsed.hostname != "ianseeds.wood.seedhost.eu"', source)
                    self.assertIn('parsed.path.rstrip("/") != "/rutorrent"', source)

    def test_tampered_secret_routes_and_dispatch_inputs_are_refused(self):
        for name, (env_key, count) in WORKFLOWS.items():
            source = (ROOT / ".github" / "workflows" / name).read_text("utf-8")
            with self.subTest(name=name, mode="foreign-host"):
                with self.assertRaises(ValueError):
                    assert_primary_binding(source.replace(PRIMARY,
                        "https://other-user.wood.seedhost.eu/rutorrent/"), env_key, count)
            with self.subTest(name=name, mode="untrusted-dispatch"):
                with self.assertRaises(ValueError):
                    assert_primary_binding(source + "\n  rutorrent_url:\n    type: string\n",
                                           env_key, count)
            with self.subTest(name=name, mode="url-expression"):
                with self.assertRaises(ValueError):
                    assert_primary_binding(source.replace(f'"{PRIMARY}"',
                        "${{ inputs.rutorrent_url }}"), env_key, count)


if __name__ == "__main__":
    unittest.main()
