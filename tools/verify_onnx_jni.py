"""Check the *packaged/minified* APK against its ONNX AAR's Java JNI surface.

No Android dependencies or third-party Python packages. Does not extract files.
Usage: python tools/verify_onnx_jni.py --aar onnxruntime-android.aar --apk app.apk
This is an artifact check, not a replacement for a real session.run smoke test.
"""
import argparse
import hashlib
import io
import json
import struct
import zipfile


def elf_loaded_sections(data):
    """Compare executable/allocated content, not debug tables removed by AGP strip."""
    assert data[:5] == b'\x7fELF\x02' and data[5] == 1, 'Expected little-endian ELF64'
    table = struct.unpack_from('<Q', data, 40)[0]
    entry_size, count, names_index = struct.unpack_from('<HHH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, table + entry_size*i) for i in range(count)]
    names_offset, names_size = sections[names_index][4:6]
    names = data[names_offset:names_offset+names_size]
    result = {}
    for section in sections:
        name_offset, kind, flags, address, offset, size = section[:6]
        if flags & 2:  # SHF_ALLOC; NOBITS represents zero-initialized runtime memory.
            name = names[name_offset:names.index(b'\x00', name_offset)].decode('ascii')
            digest = None if kind == 8 else hashlib.sha256(data[offset:offset+size]).hexdigest()
            result[name] = (kind, flags, address, size, digest)
    assert result, 'No allocated ELF sections'
    return result


def elf_16k_aligned(data):
    """ELF LOAD segment check; APK ZIP alignment is checked separately by zipalign."""
    assert data[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    segments = [struct.unpack_from('<IIQQQQQQ', data, offset + i*size) for i in range(count)]
    loads = [segment for segment in segments if segment[0] == 1]
    return bool(loads) and all(segment[7] >= 16384 and segment[2] % 16384 == segment[3] % 16384
                               for segment in loads)


def class_surface(data):
    pos = 0
    def take(n):
        nonlocal pos
        value = data[pos:pos+n]
        if len(value) != n:
            raise ValueError('Truncated class file')
        pos += n
        return value
    def u2():
        return int.from_bytes(take(2), 'big')
    assert take(4) == b'\xca\xfe\xba\xbe'
    take(4)
    cp = [None] * u2()
    i = 1
    while i < len(cp):
        tag = take(1)[0]
        if tag == 1:
            cp[i] = take(u2()).decode('utf-8', errors='replace')
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            i += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError('Unknown class constant')
        i += 1
    u2()
    name = 'L' + cp[cp[u2()]] + ';'
    u2()
    take(2 * u2())
    result = {'fields': set(), 'methods': set()}
    for kind in ('fields', 'methods'):
        for _ in range(u2()):
            u2()
            member = (cp[u2()], cp[u2()])
            if member[0] != '<clinit>':
                result[kind].add(member)
            for _ in range(u2()):
                u2()
                take(int.from_bytes(take(4), 'big'))
    return name, result


def dex_surface(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Expected ordinary DEX file')
    def u4(pos):
        return struct.unpack_from('<I', data, pos)[0]
    def u2(pos):
        return struct.unpack_from('<H', data, pos)[0]
    def leb(pos):
        value = 0
        for shift in range(0, 35, 7):
            b = data[pos]
            pos += 1
            value |= (b & 127) << shift
            if b < 128:
                return value, pos
        raise ValueError('Invalid LEB128')
    strings = []
    for i in range(u4(56)):
        _, start = leb(u4(u4(60) + 4*i))
        strings.append(data[start:data.index(b'\x00', start)].decode('utf-8', errors='replace'))
    types = [strings[u4(u4(68) + 4*i)] for i in range(u4(64))]
    protos = []
    for i in range(u4(72)):
        start = u4(76) + 12*i
        params = u4(start+8)
        args = '' if params == 0 else ''.join(types[u2(params+4+2*j)] for j in range(u4(params)))
        protos.append('(' + args + ')' + types[u4(start+4)])
    fields = [(strings[u4(u4(84)+8*i+4)], types[u2(u4(84)+8*i+2)]) for i in range(u4(80))]
    methods = [(strings[u4(u4(92)+8*i+4)], protos[u2(u4(92)+8*i+2)]) for i in range(u4(88))]
    result = {}
    for i in range(u4(96)):
        start = u4(100)+32*i
        name = types[u4(start)]
        if not name.startswith('Lai/onnxruntime/'):
            continue
        entry = {'fields': set(), 'methods': set()}
        result[name] = entry
        pos = u4(start+24)
        if pos == 0:
            continue
        counts = []
        for _ in range(4):
            count, pos = leb(pos)
            counts.append(count)
        for group, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, pos = leb(pos)
                index += delta
                _, pos = leb(pos)
                if group < 2:
                    entry['fields'].add(fields[index])
                else:
                    _, pos = leb(pos)
                    entry['methods'].add(methods[index])
    return result


def check(aar_path, apk_path):
    expected = {}
    with zipfile.ZipFile(aar_path) as aar, zipfile.ZipFile(apk_path) as apk:
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            for name in jar.namelist():
                if name.startswith('ai/onnxruntime/') and name.endswith('.class'):
                    clazz, members = class_surface(jar.read(name))
                    expected[clazz] = members
        assert expected, 'No ONNX Java classes in AAR'
        actual = {}
        for name in apk.namelist():
            if name.startswith('classes') and name.endswith('.dex') and '/' not in name:
                for clazz, members in dex_surface(apk.read(name)).items():
                    assert clazz not in actual, 'Duplicate ONNX class'
                    actual[clazz] = members
        missing = []
        for clazz, members in expected.items():
            if clazz not in actual:
                missing.append(clazz + ' class')
                continue
            for kind in ('fields', 'methods'):
                missing.extend(clazz + ' ' + kind + ' ' + name + desc
                               for name, desc in sorted(members[kind] - actual[clazz][kind]))
        libs = []
        for name in apk.namelist():
            if name.startswith('lib/') and name.endswith(('/libonnxruntime.so', '/libonnxruntime4j_jni.so')):
                aar_name = 'jni/' + name[4:]
                packaged_bytes, original_bytes = apk.read(name), aar.read(aar_name)
                packaged = hashlib.sha256(packaged_bytes).hexdigest()
                original = hashlib.sha256(original_bytes).hexdigest()
                equivalent = elf_loaded_sections(packaged_bytes) == elf_loaded_sections(original_bytes)
                libs.append({'name': name, 'sha256': packaged, 'aar_sha256': original,
                             'byte_identical': packaged == original, 'loaded_sections_identical': equivalent})
        abis = {name.split('/')[1] for name in apk.namelist()
                if name.startswith('lib/') and name.endswith('.so')}
        assert abis and abis <= {'arm64-v8a', 'x86_64'}, 'Unsupported or missing APK native ABI'
        expected_libs = {f'lib/{abi}/{library}' for abi in abis
                         for library in ('libonnxruntime.so', 'libonnxruntime4j_jni.so')}
        assert {lib['name'] for lib in libs} == expected_libs, 'Incomplete ONNX library pair for an APK ABI'
        alignment = {name: elf_16k_aligned(apk.read(name)) for name in apk.namelist()
                     if name.startswith('lib/') and name.endswith('.so')}
    return {'java_classes_in_aar': len(expected), 'java_classes_in_apk': len(actual),
            'missing_count': len(missing), 'missing': missing, 'native_libraries': libs,
            'native_16k_alignment': alignment,
            'passed': not missing and all(item['loaded_sections_identical'] for item in libs)
                      and all(alignment.values())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', required=True)
    parser.add_argument('--apk', required=True)
    args = parser.parse_args()
    result = check(args.aar, args.apk)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
