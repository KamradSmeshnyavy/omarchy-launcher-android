#!/usr/bin/env python3
# Тесты сборщика мелодий (omadeck/melody.py): узоры, замены отсутствующих звуков и — если есть
# ffmpeg — сама сборка из звуков пакета dotmatrix: длительность и повторяемость байт в байт.
#
#   python3 -I desktop/tests/test_melody.py

import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "desktop" / "omadeck"))
import melody  # noqa: E402

PACKS = ROOT / "app/src/main/assets/sounds"


def duration(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    ).stdout
    return float(out.strip())


class Patterns(unittest.TestCase):
    def test_lengths(self):
        self.assertEqual(melody.pattern("ringtone")[1], 6400)
        self.assertEqual(melody.pattern("alarm")[1], 8000)
        hits, _ = melody.pattern("alarm")
        gains = [g for _, _, g in hits]
        self.assertEqual(gains, sorted(gains))  # будильник нарастает

    def test_fallback(self):
        sounds = {"open": Path("open.ogg"), "lock": Path("lock.ogg")}
        self.assertEqual(melody.pick(sounds, "notify"), Path("open.ogg"))
        self.assertEqual(melody.pick(sounds, "power"), Path("lock.ogg"))
        self.assertIsNone(melody.pick({}, "notify"))

    def test_nothing_to_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(melody.build({}, "ringtone", Path(tmp) / "r.ogg", ffmpeg="/bin/true"))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "нет ffmpeg")
class Build(unittest.TestCase):
    def test_dotmatrix_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack = Path(tmp) / "dotmatrix"
            pack.mkdir()
            for f in (PACKS / "dotmatrix").glob("*.ogg"):
                if f.stem not in melody.KINDS:
                    shutil.copyfile(f, pack / f.name)
            self.assertEqual(melody.build_all(pack), list(melody.KINDS))
            self.assertAlmostEqual(duration(pack / "ringtone.ogg"), 6.4, delta=0.05)
            self.assertAlmostEqual(duration(pack / "alarm.ogg"), 8.0, delta=0.05)
            self.assertLess(duration(pack / "notification.ogg"), 1.5)
            first = hashlib.sha256((pack / "ringtone.ogg").read_bytes()).hexdigest()
            melody.build_all(pack)
            self.assertEqual(first, hashlib.sha256((pack / "ringtone.ogg").read_bytes()).hexdigest())

    def test_every_shipped_pack_has_melodies(self):
        for pack in sorted(p for p in PACKS.iterdir() if p.is_dir()):
            for kind in melody.KINDS:
                self.assertTrue((pack / f"{kind}.ogg").is_file(), f"{pack.name}/{kind}")


if __name__ == "__main__":
    unittest.main()
