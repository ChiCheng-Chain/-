import copy
import json
import shlex
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from native_rules import apply, parse_config, parse_switch, patch_config, restore, save_backup, update_command
from rule_config import load_rules


class NativeRulesTest(unittest.TestCase):
    def setUp(self):
        self.rule = load_rules()[1]
        self.original = {
            'mode': 1,
            'action': [{'from': 'example.Other', 'to': 'example.Detail'}, {'pages': ''}],
            'Activities': {'fullscreenActivities': [self.rule['searchActivity'], 'example.Video'],
                           'unknownVendorField': ['keep']},
            'EX': {'statusBarVisible': 'false', 'vendorSetting': 'keep',
                   'windowsGravity': [{'deviceType': 'FOLD', 'gravity': '1|1'}]},
            'untouched': {'nested': True},
        }

    def test_patch_preserves_vendor_settings_and_is_idempotent(self):
        before = copy.deepcopy(self.original)
        changed = patch_config(self.original, self.rule)
        self.assertEqual(before, self.original)
        self.assertEqual(changed, patch_config(changed, self.rule))
        self.assertEqual(changed['EX']['statusBarVisible'], 'false')
        self.assertEqual(changed['EX']['vendorSetting'], 'keep')
        self.assertIn({'deviceType': 'FOLD', 'gravity': '1|1'}, changed['EX']['windowsGravity'])
        self.assertEqual(changed['Activities']['fullscreenActivities'], ['example.Video'])
        self.assertIn({'pages': self.rule['homeActivity'], 'attachedPage': self.rule['searchActivity']}, changed['action'])
        self.assertEqual(changed['untouched'], self.original['untouched'])

    def test_rejects_unrecognized_schema(self):
        broken = copy.deepcopy(self.original)
        broken['action'] = 'unexpected'
        with self.assertRaises(ValueError):
            patch_config(broken, self.rule)

    def test_content_query_format_and_ambiguous_rows(self):
        row = 'Row: 0 name=example.app, fun=multi-landscape, config=' + json.dumps(self.original) + ', enable=0\r\n'
        self.assertEqual(parse_config(row), {'config': self.original, 'enable': 0})
        for text in ('No result found.', row + row, 'Error while accessing provider'):
            with self.assertRaises(ValueError):
                parse_config(text)
        self.assertEqual(parse_switch('Row: 0 name=example.app, enable=1\n'), 1)

    def test_shell_and_android_binding_escape_round_trip(self):
        data = {'url': 'https://example.test/a:b', 'text': "quote's $literal `literal` \\path 中文"}
        tokens = shlex.split(update_command('example.app', data))
        binding = tokens[tokens.index('--bind') + 1]
        self.assertTrue(binding.startswith('config:s:'))
        escaped = binding[len('config:s:'):]
        decoded = ''
        i = 0
        while i < len(escaped):
            if escaped[i] == '\\':
                i += 1
            decoded += escaped[i]
            i += 1
        self.assertEqual(json.loads(decoded), data)
        self.assertEqual(tokens[tokens.index('--where') + 1], "name='example.app' AND fun='multi-landscape'")

    def test_backup_cannot_overwrite_recovery_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'backup.json'
            save_backup(path, {'original': True})
            with self.assertRaises(FileExistsError):
                save_backup(path, {'original': False})
            self.assertEqual(json.loads(path.read_text()), {'original': True})

    def test_write_failure_rolls_back_even_when_write_was_applied(self):
        device = FakeDevice(self.rule['package'], self.original, fail_once=True)
        with tempfile.TemporaryDirectory() as temp:
            backup = Path(temp) / 'backup.json'
            with self.assertRaises(RuntimeError):
                apply(device, [self.rule], backup)
            self.assertEqual(device.current, self.original)
            self.assertTrue(backup.exists())

    def test_restore_rejects_external_changes(self):
        device = FakeDevice(self.rule['package'], {'external': True})
        with tempfile.TemporaryDirectory() as temp:
            backup = Path(temp) / 'backup.json'
            save_backup(backup, {'schemaVersion': 1, 'apps': {self.rule['package']: {
                'original': self.original, 'applied': patch_config(self.original, self.rule)}}})
            with self.assertRaises(RuntimeError):
                restore(device, backup, {self.rule['package']})
            self.assertEqual(device.writes, 0)

    def test_apply_then_restore(self):
        device = FakeDevice(self.rule['package'], self.original)
        with tempfile.TemporaryDirectory() as temp:
            backup = Path(temp) / 'backup.json'
            apply(device, [self.rule], backup)
            self.assertEqual(device.current, patch_config(self.original, self.rule))
            restore(device, backup, {self.rule['package']})
            self.assertEqual(device.current, self.original)


class FakeDevice:
    """Provider boundary: models a write committed before the transport reports failure."""
    def __init__(self, package, config, fail_once=False):
        self.package = package
        self.current = copy.deepcopy(config)
        self.fail_once = fail_once
        self.writes = 0

    def read(self, package):
        assert package == self.package
        return {'config': copy.deepcopy(self.current), 'enable': 0}

    def switch(self, package):
        return 1

    def stage_helper(self):
        pass

    def reload(self, packages):
        pass

    def write(self, package, config):
        self.writes += 1
        self.current = copy.deepcopy(config)
        if self.fail_once:
            self.fail_once = False
            raise RuntimeError('Simulated transport failure after commit')


if __name__ == '__main__':
    unittest.main()
