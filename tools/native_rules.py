"""Explicit, scoped OriginOS rule updates. Never installs APKs or reboots a device."""
import argparse
import copy
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

from rule_config import ROOT, load_rules

PROVIDER = 'content://com.vivo.smartmultiwindow.multiwindow/'
REMOTE_HELPER = '/data/local/tmp/tablet-completion-reload.dex'


def patch_config(original, rule):
    result = copy.deepcopy(original)
    # Require the observed schema instead of inventing vendor defaults on unknown firmware.
    actions = result['action']
    full = result['Activities']['fullscreenActivities']
    extra = result['EX']
    if not isinstance(actions, list) or not isinstance(full, list) or not isinstance(extra, dict):
        raise ValueError('Unexpected OriginOS config shape')
    if any(not isinstance(item, dict) for item in actions):
        raise ValueError('Unexpected action entry')
    result['action'] = [a for a in actions if 'pages' not in a]
    if not any(a.get('from') == rule['homeActivity'] and a.get('to') == '*' for a in result['action']):
        result['action'].append({'from': rule['homeActivity'], 'to': '*'})
    result['action'].append({'pages': rule['homeActivity'], 'attachedPage': rule['searchActivity']})
    result['Activities']['fullscreenActivities'] = [a for a in full if a != rule['searchActivity']]
    # Preserve device-specific settings; adjust only the PAD default ratio.
    gravity = extra.get('windowsGravity', [])
    if not isinstance(gravity, list) or any(not isinstance(item, dict) for item in gravity):
        raise ValueError('Unexpected windowsGravity')
    extra['windowsGravity'] = [g for g in gravity if g.get('deviceType') != 'PAD']
    extra['windowsGravity'].append({'deviceType': 'PAD', 'gravity': rule['defaultRatio']})
    extra['draggable'] = str(rule['draggable']).lower()
    return result


def parse_config(output):
    rows = [line for line in output.splitlines() if line.startswith('Row: ')]
    if len(rows) != 1:
        raise ValueError('Expected exactly one native rule row; got ' + str(len(rows)))
    match = re.search(r'\bconfig=(\{.*\}), enable=(\d+)\s*$', rows[0])
    if not match:
        raise ValueError('Unrecognized content query format')
    config = json.loads(match[1])
    if not isinstance(config, dict):
        raise ValueError('Rule must be a JSON object')
    return {'config': config, 'enable': int(match[2])}


def parse_switch(output):
    rows = [line for line in output.splitlines() if line.startswith('Row: ')]
    if len(rows) != 1:
        raise ValueError('Enable this app in the system parallel-window settings first')
    match = re.search(r'(?:^|, )enable=(\d+)(?:,|$)', rows[0])
    if not match:
        raise ValueError('Unrecognized app switch row')
    return int(match[1])


def update_command(package, config):
    payload = json.dumps(config, ensure_ascii=False, separators=(',', ':'))
    # Android 17 content CLI has an escape layer in addition to shell quoting.
    binding = 'config:s:' + payload.replace('\\', '\\\\').replace(':', '\\:')
    where = f"name='{package}' AND fun='multi-landscape'"
    return ('content update --user 0 --uri ' + PROVIDER + 'p_client --bind '
            + shlex.quote(binding) + ' --where ' + shlex.quote(where))


class Device:
    def __init__(self, adb, serial=None):
        self.prefix = [adb] + (['-s', serial] if serial else [])

    def command(self, *args):
        result = subprocess.run(self.prefix + list(args), capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=20)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'ADB command failed')
        return result.stdout

    def root(self, command):
        return self.command('shell', 'su -c ' + shlex.quote(command))

    def check(self):
        if self.command('shell', 'am get-current-user').strip() != '0':
            raise RuntimeError('Only Android owner user 0 is supported')
        if self.root('id -u').strip() != '0':
            raise RuntimeError('Root is required to read/update the native provider')

    def read(self, package):
        where = shlex.quote(f"name='{package}' AND fun='multi-landscape'")
        return parse_config(self.root('content query --user 0 --uri ' + PROVIDER
                                      + 'p_client --projection name:fun:config:enable --where ' + where))

    def switch(self, package):
        return parse_switch(self.root('content query --user 0 --uri ' + PROVIDER
                                      + 'app_switch --projection name:enable --where '
                                      + shlex.quote(f"name='{package}'")))

    def write(self, package, config):
        self.root(update_command(package, config))
        # Some content CLI failures return exit code zero. Always query the row back.
        if self.read(package)['config'] != config:
            raise RuntimeError('Read-back mismatch for ' + package)

    def stage_helper(self):
        helper = ROOT / 'dist/reload.dex'
        if not helper.is_file():
            raise RuntimeError('Build first: python tools/build.py (dist/reload.dex is missing)')
        self.command('push', str(helper), REMOTE_HELPER)
        self.command('shell', 'chmod 644 ' + REMOTE_HELPER)

    def reload(self, packages):
        print(self.root('CLASSPATH=' + REMOTE_HELPER + ' app_process /system/bin '
                        + 'dev.tabletcompletion.tools.ReloadRules '
                        + ' '.join(shlex.quote(p) for p in packages)).strip())


def save_backup(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Never overwrite the only known recovery copy, including on repeated apply.
    with path.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())


def validate_backup(data, supported):
    if data.get('schemaVersion') != 1 or not isinstance(data.get('apps'), dict) or not data['apps']:
        raise ValueError('Invalid backup')
    for package, item in data['apps'].items():
        if package not in supported:
            raise ValueError('Backup contains an unsupported package')
        if not isinstance(item.get('original'), dict) or not isinstance(item.get('applied'), dict):
            raise ValueError('Invalid backed-up config')


def apply(device, rules, backup):
    changes = {}
    for rule in rules:
        package = rule['package']
        if device.switch(package) != 1:
            raise RuntimeError('First enable parallel windows in system settings for ' + package)
        original = device.read(package)
        changes[package] = {'original': original['config'], 'applied': patch_config(original['config'], rule)}
    device.stage_helper()
    save_backup(backup, {'schemaVersion': 1, 'apps': changes})
    attempted = []
    try:
        for package, item in changes.items():
            if device.read(package)['config'] != item['original']:
                raise RuntimeError('Config changed after backup; refusing to overwrite ' + package)
            attempted.append(package)
            device.write(package, item['applied'])
        device.reload(list(changes))
    except Exception:
        for package in reversed(attempted):
            try:
                current = device.read(package)['config']
                if current not in (changes[package]['original'], changes[package]['applied']):
                    raise RuntimeError('Config changed externally; automatic rollback refused')
                device.write(package, changes[package]['original'])
            except Exception as error:
                print('Rollback incomplete for ' + package + ': ' + str(error), file=sys.stderr)
        if attempted:
            try:
                device.reload(attempted)
            except Exception as error:
                print('Rollback reload could not be confirmed: ' + str(error), file=sys.stderr)
        raise
    print('Rules read back successfully. Backup: ' + str(backup) + '. Reopen apps and verify the UI.')


def restore(device, backup, supported):
    data = json.loads(backup.read_text(encoding='utf-8'))
    validate_backup(data, supported)
    for package, item in data['apps'].items():
        if device.read(package)['config'] not in (item['original'], item['applied']):
            raise RuntimeError('Current config changed externally; refusing to overwrite ' + package)
    device.stage_helper()
    for package, item in data['apps'].items():
        # Recheck immediately before each write too.
        if device.read(package)['config'] not in (item['original'], item['applied']):
            raise RuntimeError('Config changed during restore: ' + package)
        device.write(package, item['original'])
    device.reload(list(data['apps']))
    print('Backed-up rule JSON restored and read back. System switches/divider positions were not modified.')


def main():
    rules = load_rules()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', help='Explicit device selector when more than one is connected')
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('inspect', 'apply'):
        command = commands.add_parser(name)
        command.add_argument('--app', choices=[r['id'] for r in rules] + ['all'], required=True)
        if name == 'apply':
            command.add_argument('--backup', type=Path, required=True, help='New file, saved before any config update')
    commands.add_parser('restore').add_argument('--backup', type=Path, required=True)
    args = parser.parse_args()
    device = Device(args.adb, args.serial)
    device.check()
    if args.command == 'restore':
        restore(device, args.backup, {r['package'] for r in rules})
        return
    selected = [r for r in rules if args.app in (r['id'], 'all')]
    if args.command == 'inspect':
        for rule in selected:
            row = device.read(rule['package'])
            print(json.dumps({'package': rule['package'], 'switch': device.switch(rule['package']), **row}, ensure_ascii=False, indent=2))
    else:
        apply(device, selected, args.backup)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        sys.exit('Stopped: ' + str(error))
