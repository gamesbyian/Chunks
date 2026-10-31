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
    if "github.ref == 'refs/heads/main'" not in source:
        raise ValueError("Protected credential workflow can dispatch from a non-main ref")
    actual = re.findall(rf"(?m)^\s*{re.escape(key)}:\s*(.*)$", source)
    if actual != [f'"{PRIMARY}"'] * count:
        raise ValueError(f"Expected {count} exact primary bindings for {key}, got {actual!r}")


def instance_password_secret_names(source: str) -> dict[str, str]:
    """Validate the workflow's fixed endpoints and parse its secret-name choice.

    Restrict the accepted expression deliberately: evaluating secret values in
    an Actions `&& ... || ...` choice would fall through when a secret is empty.
    No actual credentials are needed or read by this offline policy check.
    """
    if "if: github.ref == 'refs/heads/main'" not in source:
        raise ValueError("Instance probe must run only on trusted main")
    if not re.search(r'(?m)^\s*instance:\s*\["2",\s*"3"\]\s*$', source):
        raise ValueError("Unexpected instance matrix")
    if not re.search(r'(?m)^\s*CHUNKS_LIVE_RUTORRENT_URL:\s*https://ianseeds\.wood\.seedhost\.eu/rutorrent\$\{\{ matrix\.instance \}\}/\s*$', source):
        raise ValueError("Unexpected instance endpoint")
    if not re.search(r'(?m)^\s*CHUNKS_LIVE_RTORRENT_USER:\s*ianseeds\s*$', source):
        raise ValueError("Unexpected instance account")
    match = re.search(
        r"(?m)^\s*CHUNKS_LIVE_RTORRENT_PASS:\s*\$\{\{\s*secrets\[matrix\.instance == '2' && '([^']+)' \|\| '([^']+)'\]\s*\}\}\s*$",
        source,
    )
    if not match or match.groups() != ("rTorrent_pass2", "rtorrent_pass3"):
        raise ValueError("Expected fixed per-instance secret-name choice before lookup")
    guard = 'test -n "$CHUNKS_LIVE_RTORRENT_PASS"'
    invocation = 'gradle --no-daemon --console=plain :app:testDebugUnitTest'
    if guard not in source or invocation not in source or source.index(guard) > source.index(invocation):
        raise ValueError("Missing selected-secret empty check before network test")
    return {"2": match.group(1), "3": match.group(2)}


class ProtectedSeedhostCredentialRoutesTest(unittest.TestCase):
    def test_instance_credentials_remain_bound_when_selected_secret_is_missing(self):
        source = (ROOT / ".github/workflows/seedhost-instances-readonly.yml").read_text("utf-8")
        names = instance_password_secret_names(source)
        # Model the actual Actions lookup after selection with synthetic values.
        # An absent or empty instance 2 secret must not resolve to instance 3.
        for secrets in ({"rTorrent_pass2": "", "rtorrent_pass3": "synthetic-3"},
                        {"rtorrent_pass3": "synthetic-3"}):
            self.assertFalse(secrets.get(names["2"], ""))
            self.assertEqual("synthetic-3", secrets.get(names["3"], ""))
        secrets = {"rTorrent_pass2": "synthetic-2", "rtorrent_pass3": ""}
        self.assertEqual("synthetic-2", secrets.get(names["2"], ""))
        self.assertFalse(secrets.get(names["3"], ""))
        self.assertEqual({"2": "rTorrent_pass2", "3": "rtorrent_pass3"}, names)

    def test_instance_route_policy_rejects_wrong_binding_and_value_fallback(self):
        source = (ROOT / ".github/workflows/seedhost-instances-readonly.yml").read_text("utf-8")
        for old, new in (
            ("secrets[matrix.instance == '2' && 'rTorrent_pass2' || 'rtorrent_pass3']",
             "matrix.instance == '2' && secrets.rTorrent_pass2 || secrets.rtorrent_pass3"),
            ("'rTorrent_pass2' || 'rtorrent_pass3'", "'rtorrent_pass3' || 'rTorrent_pass2'"),
            ("ianseeds.wood.seedhost.eu/rutorrent", "other.wood.seedhost.eu/rutorrent"),
            ("refs/heads/main", "refs/heads/other"),
            ('test -n "$CHUNKS_LIVE_RTORRENT_PASS"', 'true'),
        ):
            with self.subTest(mutation=old):
                self.assertIn(old, source)
                with self.assertRaises(ValueError):
                    instance_password_secret_names(source.replace(old, new))

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
            with self.subTest(name=name, mode="non-main-execution"):
                with self.assertRaises(ValueError):
                    assert_primary_binding(source.replace(
                        "github.ref == 'refs/heads/main'", "github.ref != 'refs/heads/main'"),
                        env_key, count)
            with self.subTest(name=name, mode="url-expression"):
                with self.assertRaises(ValueError):
                    assert_primary_binding(source.replace(f'"{PRIMARY}"',
                        "${{ inputs.rutorrent_url }}"), env_key, count)


if __name__ == "__main__":
    unittest.main()
