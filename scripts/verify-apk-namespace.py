"""Inspect actual DEX class definitions and strings in a built Huevia APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


def class_descriptors(data):
    if not data.startswith(b'dex\n') or len(data) < 112:
        raise ValueError('Not a supported DEX file')
    if struct.unpack_from('<I', data, 40)[0] != 0x12345678:
        raise ValueError('Unsupported DEX endianness')
    string_count, string_offset = struct.unpack_from('<II', data, 56)
    type_count, type_offset = struct.unpack_from('<II', data, 64)
    class_count, class_offset = struct.unpack_from('<II', data, 96)

    def descriptor(class_index):
        if class_index >= type_count:
            raise ValueError('Invalid class type index')
        string_index = struct.unpack_from('<I', data, type_offset + class_index * 4)[0]
        if string_index >= string_count:
            raise ValueError('Invalid descriptor string index')
        offset = struct.unpack_from('<I', data, string_offset + string_index * 4)[0]
        # A DEX string begins with its ULEB128 UTF-16 length. Descriptors here use ASCII.
        for _ in range(5):
            value = data[offset]
            offset += 1
            if value < 128:
                break
        else:
            raise ValueError('Invalid descriptor length')
        return data[offset:data.index(b'\0', offset)].decode('utf-8')

    return {descriptor(struct.unpack_from('<I', data, class_offset + index * 32)[0])
            for index in range(class_count)}


def verify(apk):
    required = {
        'Lcc/huevia/MainActivity;',
        'Lcc/huevia/DiagnosticsActivity;',
        'Lcc/huevia/connection/CameraConnectionService;',
        'Lcc/huevia/updates/UpdatePackageProvider;',
    }
    own, legacy, dex_files = set(), set(), []
    legacy_strings = 0
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            if not re.fullmatch(r'classes(?:[1-9][0-9]*)?\.dex', info.filename):
                continue
            if info.file_size > 64 * 1024 * 1024:
                raise ValueError('DEX exceeds verification size limit')
            data = archive.read(info)
            classes = class_descriptors(data)
            app_classes = {name for name in classes if name.startswith('Lcc/huevia/')}
            old_classes = {name for name in classes if name.startswith('Lcom/nikonpreset/usbprobe/')}
            old_strings = data.count(b'com.nikonpreset.usbprobe') + data.count(b'com/nikonpreset/usbprobe')
            own.update(app_classes)
            legacy.update(old_classes)
            legacy_strings += old_strings
            dex_files.append({'name': info.filename, 'classCount': len(classes),
                              'applicationClassCount': len(app_classes),
                              'legacyClassCount': len(old_classes), 'legacyStringOccurrences': old_strings})
    if not dex_files or not required.issubset(own) or legacy or legacy_strings:
        raise ValueError(f'Namespace verification failed: missing={sorted(required - own)}, '
                         f'legacyClasses={sorted(legacy)}, legacyStrings={legacy_strings}')
    return {'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'packageName': 'cc.huevia', 'applicationClassCount': len(own),
            'legacyClassCount': len(legacy), 'legacyStringOccurrences': legacy_strings,
            'requiredClasses': sorted(required), 'dexFiles': dex_files}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify(args.apk)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
