#!/usr/bin/env python3
"""Reject reused tags, lower versionCode, and private update repositories."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
from release import version


def gh(*args):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


if __name__ == '__main__':
    code, name = version()
    repo = os.environ['GH_REPO']
    details = json.loads(gh('repo', 'view', repo, '--json', 'isPrivate'))
    if details['isPrivate']:
        raise SystemExit('应用内更新需要公开仓库；本应用不会内置 GitHub 访问令牌。')
    releases = json.loads(gh('release', 'list', '--repo', repo, '--limit', '100', '--json', 'tagName,isDraft,isPrerelease'))
    if any(r['tagName'] == 'v' + name for r in releases):
        raise SystemExit('该版本标签已经存在。请递增 versionCode 和 versionName 后发布。')
    published = [r for r in releases if not r['isDraft'] and not r['isPrerelease']]
    if published:
        with tempfile.TemporaryDirectory() as directory:
            gh('release', 'download', '--repo', repo, '--pattern', 'update.json', '--dir', directory)
            old = json.loads((Path(directory) / 'update.json').read_text(encoding='utf-8'))
            if code <= int(old['versionCode']):
                raise SystemExit('versionCode 必须大于当前已发布版本。')
    print('Release configuration valid: v' + name)
