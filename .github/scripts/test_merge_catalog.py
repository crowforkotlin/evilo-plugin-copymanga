"""Behaviour of the catalog merge: append, then fit the byte budget by dropping old releases.

Run with:  python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_catalog  # noqa: E402


def release(n, floor_marker=""):
    return {
        "version": f"1.{n}.0",
        "versionCode": 10000 + n,
        "publishedAt": "2026-11-10",
        "manifestUrl": f"https://example.com/r/1.{n}.0/manifest.wlm",
        "sourceRevision": "a" * 39 + str(n % 10),
        "sourceArchiveUrl": f"https://example.com/r/1.{n}.0/sources.zip",
        "sourceArchiveSha256": "a" * 64,
    }


def plugin(count, newest=None, **extra):
    return {
        "id": "com.example.plugin",
        "name": "Example",
        "authors": ["Example"],
        "description": "A plugin.",
        "languages": ["en"],
        "kinds": ["novel"],
        "sourceUrl": "https://example.com/source",
        "availableVersions": [release(n) for n in range(1, count + 1)] + (
            [release(newest)] if newest is not None else []),
        **extra,
    }


def catalog(p):
    return {"name": "Example plugins", "plugins": [p]}


class MergeTest(unittest.TestCase):
    def run_merge(self, old, new, limit=None):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            (directory / "old.json").write_text(json.dumps(catalog(old), indent=2))
            (directory / "new.json").write_text(json.dumps(catalog(new), indent=2))
            argv = ["merge_catalog.py", str(directory / "old.json"),
                    str(directory / "new.json"), str(directory / "out.json")]
            if limit is not None:
                argv.append(str(limit))
            saved = sys.argv
            sys.argv = argv
            try:
                merge_catalog.main(argv)
            finally:
                sys.argv = saved
            return json.loads((directory / "out.json").read_text())

    def test_within_budget_keeps_every_release(self):
        # 998 existing plus the appended 999th.
        merged = self.run_merge(plugin(998), plugin(0, newest=999))
        self.assertEqual(999, len(merged["plugins"][0]["availableVersions"]))
        self.assertEqual(10000 + 999, merged["plugins"][0]["availableVersions"][0]["versionCode"])

    def test_over_budget_drops_the_oldest_and_keeps_the_newest(self):
        merged = self.run_merge(plugin(20000), plugin(0, newest=20001), limit=1_048_576)
        codes = sorted(int(r["versionCode"]) for r in merged["plugins"][0]["availableVersions"])
        self.assertLessEqual(len(json.dumps(merged, indent=2).encode()), 1_048_576)
        self.assertEqual(10000 + 20001, codes[-1], "the newest release must survive")
        self.assertGreater(codes[0], 10001, "the oldest releases must be dropped")

    def test_a_floor_is_never_dropped(self):
        # The floor protects 5001 releases (~2.5 MiB), so the budget must exceed that.
        old = plugin(20000, minimumUsableVersionCode=25000)
        merged = self.run_merge(old, plugin(0, newest=20001, minimumUsableVersionCode=25000), limit=8_388_608)
        codes = sorted(int(r["versionCode"]) for r in merged["plugins"][0]["availableVersions"])
        missing = [c for c in range(25000, 10000 + 20001 + 1) if c not in codes]
        self.assertEqual([], missing, "releases at or above the floor must all remain")

    def test_an_impossible_floor_fails_instead_of_publishing(self):
        old = plugin(20000, minimumUsableVersionCode=10001)
        with self.assertRaises(SystemExit):
            self.run_merge(old, plugin(0, newest=20001, minimumUsableVersionCode=10001), limit=4096)

    def test_reusing_a_version_is_refused(self):
        # The appended release must be older than an existing one.
        with self.assertRaises(SystemExit):
            self.run_merge(plugin(5), plugin(0, newest=2))


if __name__ == "__main__":
    unittest.main()
