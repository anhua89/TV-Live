import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import release


class VersionTest(unittest.TestCase):
    def parse(self, content):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'version.properties').write_text(content, encoding='utf-8')
            with patch.object(release, 'ROOT', root):
                return release.version()

    def test_version_increments_independently_from_name(self):
        self.assertEqual((2, '1.1'), self.parse('versionCode=2\nversionName=1.1\n'))

    def test_reject_zero_version(self):
        with self.assertRaises(ValueError):
            self.parse('versionCode=0\nversionName=1.0\n')

    def test_reject_shell_text_in_version_name(self):
        with self.assertRaises(ValueError):
            self.parse('versionCode=2\nversionName=1.1; echo bad\n')

    def test_three_part_name(self):
        self.assertEqual((9, '1.2.3'), self.parse('# hi\nversionCode = 9\nversionName=1.2.3\n'))

    def test_bad_repository_rejected_before_touching_files(self):
        for repository in ('', 'owner/repo/extra', 'owner/repo;bad', '../foo'):
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                release.prepare(Path('does-not-exist'), Path('never-created'), repository)


if __name__ == '__main__':
    unittest.main()
