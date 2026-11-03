"""Inspect the built APK, including DEX class definitions, for modern-only packaging."""
import struct
import sys
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def dex_types_and_classes(data):
    require(data.startswith(b'dex\n'), 'Expected a standard DEX file')
    string_count, string_offset, type_count, type_offset = struct.unpack_from('<4I', data, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', data, string_offset + index * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types = [strings[struct.unpack_from('<I', data, type_offset + index * 4)[0]] for index in range(type_count)]
    count, offset = struct.unpack_from('<2I', data, 96)
    classes = {}
    for index in range(count):
        cls, _, parent = struct.unpack_from('<3I', data, offset + index * 32)
        classes[types[cls]] = None if parent == 0xffffffff else types[parent]
    return types, classes


def verify_apk(path, expected_scope):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate APK zip entries')
        require('assets/xposed_init' not in names, 'Legacy entry is still packaged')
        prefix = 'META-INF/xposed/'
        entry = archive.read(prefix + 'java_init.list').decode().strip()
        require(entry == 'dev.tabletcompletion.keyboard.Entry', 'Incorrect modern entrypoint')
        props = dict(line.split('=', 1) for line in archive.read(prefix + 'module.prop').decode().splitlines()
                     if line and not line.startswith('#'))
        require(props.get('minApiVersion') == props.get('targetApiVersion') == '102', 'API must be 102 only')
        require(props.get('staticScope') == 'true', 'Expected a fixed app scope')
        require(props.get('autoHotReload') == 'false', 'Automatic hot reload is not implemented')
        scopes = archive.read(prefix + 'scope.list').decode().splitlines()
        require(set(scopes) == expected_scope and len(scopes) == len(expected_scope), 'Scope differs from app rules')
        manifest = archive.read('AndroidManifest.xml')
        require(manifest[:4] == b'\x03\x00\x08\x00', 'Manifest is not compiled Android binary XML')
        for marker in ('xposedminversion', 'xposedmodule', 'xposeddescription'):
            require(marker.encode() not in manifest and marker.encode('utf-16le') not in manifest,
                    'Legacy module metadata remains: ' + marker)
        defined = {}
        for name in names:
            if name.startswith('classes') and name.endswith('.dex'):
                types, classes = dex_types_and_classes(archive.read(name))
                require(not any('de/robv/android/xposed/' in t for t in types), 'Legacy API references remain in DEX')
                require(not any(t.startswith('Lio/github/libxposed/') for t in classes), 'Framework API bundled into module')
                defined.update(classes)
        require(defined.get('Ldev/tabletcompletion/keyboard/Entry;') == 'Lio/github/libxposed/api/XposedModule;',
                'Entrypoint does not extend the modern XposedModule')
    print('APK verification passed: API 102 metadata, scope, entrypoint, no legacy API or bundled framework')


if __name__ == '__main__':
    from rule_config import load_rules
    verify_apk(sys.argv[1], {app['package'] for app in load_rules()})
