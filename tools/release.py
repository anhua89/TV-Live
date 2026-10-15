#!/usr/bin/env python3
"""Prepare a signed build's release assets. Does not upload, build or sign an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from update_catalog import validate

ROOT = Path(__file__).resolve().parents[1]


def version():
    values = {}
    for row in (ROOT / 'version.properties').read_text(encoding='utf-8').splitlines():
        if '=' in row and not row.startswith('#'):
            key, value = row.split('=', 1)
            values[key.strip()] = value.strip()
    code = int(values['versionCode'])
    name = values['versionName']
    if code < 1 or not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', name):
        raise ValueError('versionCode 必须为正整数；versionName 如 1.0 或 1.0.1')
    return code, name


def prepare(apk, output, repository):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', repository):
        raise ValueError('GITHUB_REPOSITORY 必须为 用户名/仓库名')
    code, name = version()
    tag = f'v{name}'
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'JianshiTV-{name}.apk'
    if apk.resolve() != target.resolve():
        shutil.copyfile(apk, target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {
        'applicationId': 'cn.anhuatv.live.v1',
        'versionCode': code,
        'versionName': name,
        'minSdk': 21,
        'apkUrl': f'https://github.com/{repository}/releases/download/{tag}/{target.name}',
        'sha256': checksum,
        'size': target.stat().st_size,
        'notes': (ROOT / 'release-notes.md').read_text(encoding='utf-8').strip(),
    }
    (output / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    seed = ROOT / 'app/src/main/assets/best_fan_seed.m3u'
    validate(seed.read_bytes())
    shutil.copyfile(seed, output / 'channels.m3u')
    shutil.copyfile(ROOT / 'app/src/main/assets/catalog_metadata.json', output / 'catalog_metadata.json')
    return tag, name, manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'release-output')
    args = parser.parse_args()
    tag, name, manifest = prepare(args.apk, args.output, os.environ.get('GITHUB_REPOSITORY', ''))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
            stream.write(f'release_tag={tag}\nversion_name={name}\n')
    print(f'Prepared {tag}: {manifest["size"]} bytes, SHA-256 {manifest["sha256"]}')
