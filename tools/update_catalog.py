#!/usr/bin/env python3
"""Refresh bundled best-fan content in GitHub builds; retain a valid seed on failure."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = 'https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all.m3u8'
MAX_BYTES = 8 * 1024 * 1024


def validate(data):
    if len(data) > MAX_BYTES:
        raise ValueError('Catalog exceeds 8 MiB')
    text = data.decode('utf-8-sig')
    if not text.lstrip().startswith('#EXTM3U'):
        raise ValueError('Expected an Extended M3U catalog, not a web page')
    count = sum(line.strip().startswith(('http://', 'https://', 'rtsp://')) for line in text.splitlines())
    if count < 20:
        raise ValueError('Catalog has too few stream URLs')
    return text, count


def fetch():
    request = urllib.request.Request(SOURCE_URL, headers={'User-Agent': 'JianshiTV-CatalogUpdater'})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read(MAX_BYTES + 1)


def refresh(assets, download=fetch):
    assets = Path(assets)
    target = assets / 'best_fan_seed.m3u'
    last_error = None
    for _ in range(2):
        try:
            text, count = validate(download())
            data = text.encode('utf-8')
            assets.mkdir(parents=True, exist_ok=True)
            temporary = assets / 'best_fan_seed.m3u.tmp'
            temporary.write_bytes(data)
            temporary.replace(target)
            metadata = {'source': SOURCE_URL, 'fetchedAt': datetime.now(timezone.utc).isoformat(),
                        'streamCount': count, 'sha256': hashlib.sha256(data).hexdigest()}
            (assets / 'catalog_metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            return True, count
        except Exception as error:
            last_error = error
    if target.is_file():
        _, count = validate(target.read_bytes())
        print('Remote refresh unavailable; keeping a valid bundled catalog:', type(last_error).__name__)
        return False, count
    raise RuntimeError('No valid bundled catalog is available') from last_error


if __name__ == '__main__':
    updated, count = refresh(ROOT / 'app/src/main/assets')
    print(('Refreshed' if updated else 'Kept') + f' bundled catalog: {count} stream URLs; playback is not verified.')
