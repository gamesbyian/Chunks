"""Offline target, restoration and receipt controls; never Android evidence."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/run_compose_system_font_200pct.sh"
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ['CHUNKS_FAKE_STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
s['calls'].append({'args': a, 'serial': os.getenv('ANDROID_SERIAL')})
def save(): p.write_text(json.dumps(s))
def done(value='', code=0):
    save()
    if value: print(value)
    sys.exit(code)
if a == ['devices']:
    done('List of devices attached\n' + '\n'.join(x + '\tdevice' for x in s['devices']))
if a == ['get-state']: done('device')
if a[:2] == ['shell', 'getprop']:
    done(s['qemu'] if a[2] == 'ro.kernel.qemu' else '35')
if a[:3] == ['shell', 'wm', 'size']:
    if len(a) == 3: done('Physical size: 1080x2400' + ('\nOverride size: ' + s['size'] if s['size'] else ''))
    if s.get('fail_restore') and a[3] == '900x1800': done(code=1)
    s['size'] = '' if a[3] == 'reset' else a[3]; done()
if a[:3] == ['shell', 'wm', 'density']:
    if len(a) == 3: done('Physical density: 420' + ('\nOverride density: ' + s['density'] if s['density'] else ''))
    s['density'] = '' if a[3] == 'reset' else a[3]; done()
if a[:5] == ['shell', 'settings', 'get', 'system', 'font_scale']: done(s['font'])
if a[:5] == ['shell', 'settings', 'put', 'system', 'font_scale']:
    s['font'] = a[5]; done()
if a[:5] == ['shell', 'settings', 'delete', 'system', 'font_scale']:
    s['font'] = 'null'; done()
if a[:2] == ['install', '-r']: done('Success')
if a[:4] == ['shell', 'am', 'instrument', '-w']:
    if s.get('instrument_fail'): done('FAILURES!!!', 1)
    name = 'actualChunksActivityKeepsItsCoreScreensAccessibleAtRealSystemFontScale200Percent'
    report = ('INSTRUMENTATION_STATUS: test=' + name + '\nINSTRUMENTATION_STATUS_CODE: 1\n' +
              'INSTRUMENTATION_STATUS: test=' + name + '\nINSTRUMENTATION_STATUS_CODE: 0\n' +
              'OK (1 test)\nINSTRUMENTATION_CODE: -1')
    if s.get('missing_terminal'): report = report.replace('INSTRUMENTATION_CODE: -1', '')
    done(report)
if a and a[0] == 'pull':
    dest = Path(a[2]); dest.mkdir(parents=True, exist_ok=True)
    png = bytes.fromhex('89504e470d0a1a0a0000000d49484452') + bytes(32)
    for screen in ('connection', 'transfers-initial', 'places', 'organize', 'server', 'transfers-return', 'combined'):
        (dest / ('chunks-compose-200pct-' + screen + '.png')).write_bytes(png)
    done()
done('unexpected fake adb command', 2)
'''


class ComposeFontControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name, text in (('adb', FAKE_ADB), ('gradle', '#!/bin/sh\nexit 0\n')):
            target = self.bin / name
            target.write_text(text)
            target.chmod(0o755)
        self.state = self.root / 'state.json'
        self.config = {'devices': ['emulator-5554'], 'qemu': '1', 'size': '900x1800',
                       'density': '360', 'font': '1.3', 'calls': []}

    def execute(self, selectors=None):
        self.state.write_text(json.dumps(self.config))
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                   CHUNKS_FAKE_STATE=str(self.state), RUNNER_TEMP=str(self.root))
        env.pop('ANDROID_SERIAL', None)
        env.pop('ADB_SERIAL', None)
        env.update(selectors or {})
        result = subprocess.run(['bash', str(SCRIPT)], cwd=ROOT, env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        return result, json.loads(self.state.read_text())

    def test_physical_or_ambiguous_targets_are_refused_before_writes(self):
        for config in ({'qemu': '0'}, {'devices': ['emulator-5554', 'emulator-5556']}):
            with self.subTest(config=config):
                self.config.update(config)
                result, state = self.execute()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(c['args'][:3] in (['shell', 'wm', 'size'], ['shell', 'wm', 'density'])
                                     and len(c['args']) > 3 for c in state['calls']))
                self.config.update({'devices': ['emulator-5554'], 'qemu': '1'})

    def test_conflicting_selectors_are_refused_before_adb(self):
        result, state = self.execute({'ADB_SERIAL': 'emulator-5554', 'ANDROID_SERIAL': 'emulator-5556'})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], state['calls'])

    def test_both_viewports_run_on_pinned_target_and_original_settings_return(self):
        result, state = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(('900x1800', '360', '1.3'), (state['size'], state['density'], state['font']))
        self.assertTrue(all(c['serial'] == 'emulator-5554' for c in state['calls'][1:]))
        self.assertEqual(2, sum(c['args'][:4] == ['shell', 'am', 'instrument', '-w'] for c in state['calls']))
        self.assertIn('both_viewports=320x700,412x892 captures=14', result.stdout)

    def test_failure_preserves_partial_pixels_and_restores_settings(self):
        self.config['instrument_fail'] = True
        result, state = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(('900x1800', '360', '1.3'), (state['size'], state['density'], state['font']))
        self.assertTrue((self.root / 'chunks-compose-system-font-200pct/320x700/chunks-compose-200pct-connection.png').exists())
        self.assertNotIn('both_viewports=', result.stdout)

    def test_unset_overrides_are_restored_as_unset(self):
        self.config.update({'size': '', 'density': '', 'font': 'null'})
        result, state = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(('', '', 'null'), (state['size'], state['density'], state['font']))
        commands = [c['args'] for c in state['calls']]
        self.assertIn(['shell', 'wm', 'size', 'reset'], commands)
        self.assertIn(['shell', 'wm', 'density', 'reset'], commands)
        self.assertIn(['shell', 'settings', 'delete', 'system', 'font_scale'], commands)

    def test_invalid_prior_setting_refuses_before_changes(self):
        self.config['font'] = 'unavailable'
        result, state = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(c['args'][:3] in (['shell', 'wm', 'size'], ['shell', 'wm', 'density'])
                             and len(c['args']) > 3 for c in state['calls']))

    def test_missing_terminal_or_failed_restoration_never_passes(self):
        for flag in ('missing_terminal', 'fail_restore'):
            with self.subTest(flag=flag):
                self.config[flag] = True
                result, _ = self.execute()
                self.assertNotEqual(0, result.returncode)
                self.config.pop(flag)


if __name__ == '__main__':
    unittest.main()
