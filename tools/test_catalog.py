from pathlib import Path
import tempfile
import unittest
from update_catalog import refresh, validate


def catalog(prefix='new'):
    return ('#EXTM3U\n' + ''.join(f'#EXTINF:-1,CCTV{i}\nhttps://example.com/{prefix}/{i}.m3u8\n' for i in range(1, 21))).encode()


class CatalogUpdateTest(unittest.TestCase):
    def test_refresh_saves_valid_snapshot_and_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            updated, count = refresh(folder, download=lambda: catalog())
            self.assertTrue(updated)
            self.assertEqual(20, count)
            self.assertTrue((Path(folder) / 'catalog_metadata.json').is_file())

    def test_network_failure_retains_previous_snapshot(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'best_fan_seed.m3u'
            old = catalog('old'); target.write_bytes(old)
            def unavailable():
                raise TimeoutError('fixture: origin unreachable')
            self.assertEqual((False, 20), refresh(folder, download=unavailable))
            self.assertEqual(old, target.read_bytes())

    def test_html_response_does_not_replace_valid_seed(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'best_fan_seed.m3u'
            target.write_bytes(catalog('old'))
            self.assertEqual((False, 20), refresh(folder, download=lambda: b'<html>blocked</html>'))
            self.assertIn(b'/old/', target.read_bytes())

    def test_no_valid_seed_and_no_network_fails_clearly(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(RuntimeError):
                refresh(folder, download=lambda: b'not a playlist')

    def test_rejects_truncated_catalog(self):
        with self.assertRaises(ValueError):
            validate(b'#EXTM3U\n#EXTINF:-1,one\nhttps://example.com/live\n')

    def test_utf8_bom_supported(self):
        self.assertEqual(20, validate(b'\xef\xbb\xbf' + catalog())[1])
