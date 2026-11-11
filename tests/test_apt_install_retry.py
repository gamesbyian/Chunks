"""Offline tests for tools/apt_install_retry.sh using stubbed sudo, apt-get and dpkg."""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools" / "apt_install_retry.sh"
AZURE = "URIs: http://azure.archive.ubuntu.com/ubuntu/\n"

STUBS = {
    "sudo": '#!/usr/bin/env bash\nexec "$@"\n',
    # Fails while the Azure mirror is configured (or always); records installs unless told to skip.
    "apt-get": r'''#!/usr/bin/env bash
if [ "${STUB_ALWAYS_FAIL:-0}" = 1 ]; then exit 100; fi
if [ "${STUB_FAIL_AZURE:-0}" = 1 ] && grep -rq "azure.archive.ubuntu.com" "$APT_SOURCES_DIR" 2>/dev/null; then exit 100; fi
case " $* " in
  *" install "*)
    [ "${STUB_SKIP_INSTALL:-0}" = 1 ] && exit 0
    for arg in "$@"; do case "$arg" in -*|install|Acquire*) ;; *) echo "$arg" >> "$STUB_INSTALLED";; esac; done;;
esac
exit 0
''',
    "dpkg": '#!/usr/bin/env bash\n[ "$1" = "-s" ] && grep -qx "$2" "$STUB_INSTALLED" 2>/dev/null\n',
}


class AptInstallRetryTest(unittest.TestCase):
    def run_script(self, *packages, **env):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            for name, body in STUBS.items():
                path = bin_dir / name
                path.write_text(body)
                path.chmod(path.stat().st_mode | stat.S_IEXEC)
            sources = tmp / "apt" / "sources.list.d"
            sources.mkdir(parents=True)
            (sources / "ubuntu.sources").write_text(AZURE)
            full_env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}",
                            APT_SOURCES_DIR=str(tmp / "apt"), STUB_INSTALLED=str(tmp / "installed"))
            full_env.update({k: str(v) for k, v in env.items()})
            result = subprocess.run(["bash", str(SCRIPT), *packages], env=full_env,
                                    capture_output=True, text=True, timeout=60)
            return result, (sources / "ubuntu.sources").read_text()

    def test_healthy_mirror_installs_without_fallback_or_rewriting_sources(self):
        result, sources = self.run_script("rtorrent", "nginx")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("APT_FALLBACK", result.stderr)
        self.assertEqual(AZURE, sources)

    def test_unreachable_azure_mirror_falls_back_to_the_primary_archive(self):
        result, sources = self.run_script("rtorrent", STUB_FAIL_AZURE=1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("APT_FALLBACK: default mirror failed", result.stderr)
        self.assertIn("http://archive.ubuntu.com/ubuntu/", sources)
        self.assertNotIn("azure.archive", sources)

    def test_total_failure_is_never_reported_as_success(self):
        result, _ = self.run_script("rtorrent", STUB_ALWAYS_FAIL=1)
        self.assertEqual(1, result.returncode)
        self.assertIn("packages not installed", result.stderr)

    def test_apt_exit_zero_without_the_package_installed_is_a_failure(self):
        result, _ = self.run_script("python3-libtorrent", STUB_SKIP_INSTALL=1)
        self.assertEqual(1, result.returncode)

    def test_missing_arguments_are_a_usage_error(self):
        result, _ = self.run_script()
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
