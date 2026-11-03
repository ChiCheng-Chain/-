"""Shared, strictly validated app policy for the APK and native configuration tool."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_rules(path=ROOT / 'rules/apps.json'):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if data.get('schemaVersion') != 1 or not isinstance(data.get('apps'), list):
        raise ValueError('Unsupported rule schema')
    seen_ids, seen_packages = set(), set()
    for app in data['apps']:
        if not re.fullmatch(r'[a-z][a-z0-9_]*', app['id']) or app['id'] in seen_ids:
            raise ValueError('Invalid or duplicate app id')
        for field in ('package', 'homeActivity', 'searchActivity'):
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', app[field]):
                raise ValueError('Invalid class/package in ' + field)
        if app['package'] in seen_packages:
            raise ValueError('Duplicate package')
        if not re.fullmatch(r'[1-9][0-9]*\|[1-9][0-9]*', app['defaultRatio']):
            raise ValueError('Invalid split ratio')
        for field in ('draggable', 'manualKeyboard', 'searchLaunchFallback'):
            if type(app[field]) is not bool:
                raise ValueError(field + ' must be boolean')
        seen_ids.add(app['id'])
        seen_packages.add(app['package'])
    if not data['apps']:
        raise ValueError('At least one app rule is required')
    return data['apps']
