#!/usr/bin/env python3
"""Fast structural checks, no third-party Python dependencies and no APK generation."""
from pathlib import Path
import hashlib
import json
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def check():
    for file in (ROOT / 'app/src/main').rglob('*.xml'):
        ET.parse(file)
    sources = json.loads((ROOT / 'app/src/main/assets/sources.json').read_text(encoding='utf-8'))
    assert len(sources) == 6
    assert len({s['id'] for s in sources}) == len(sources)
    assert [s['id'] for s in sources if s['enabled']] == ['best-fan']
    required = ['gradlew', 'gradlew.bat', 'app/build.gradle', 'version.properties',
                '.github/workflows/check.yml', '.github/workflows/release.yml',
                'tools/New-SigningKey.ps1', 'README.md', 'docs/操作指南.md']
    for path in required:
        assert (ROOT / path).is_file(), path
    with zipfile.ZipFile(ROOT / 'gradle/wrapper/gradle-wrapper.jar') as wrapper:
        assert wrapper.testzip() is None
        assert 'org/gradle/wrapper/GradleWrapperMain.class' in wrapper.namelist()
    from release import version
    from update_catalog import validate
    _, routes = validate((ROOT / 'app/src/main/assets/best_fan_seed.m3u').read_bytes())
    print(f'Bundled best-fan catalog valid: {routes} stream URLs (playback not verified).')
    code, name = version()
    print(f'Project structure valid: {len(sources)} sources, version {name} ({code}).')


if __name__ == '__main__':
    check()
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'tools'), pattern='test_*.py')
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
