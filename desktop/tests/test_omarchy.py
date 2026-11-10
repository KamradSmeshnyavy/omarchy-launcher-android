#!/usr/bin/env python3
# Тесты того, что omadeck добавляет к апстриму: оформление компьютера для телефона
# (/v1/look, /v1/wallpaper) и адаптер Omarchy (ipc.py): доска задач и todo-виджет.
# Всё во временных каталогах (XDG_CONFIG_HOME / XDG_STATE_HOME), настоящие файлы
# не трогаются.
#
#   python3 -I desktop/tests/test_omarchy.py

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "omadeck"))
import deck  # noqa: E402
import ipc  # noqa: E402


class Sandbox(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="omadeck-test-")
        self.saved = {k: os.environ.get(k) for k in ("XDG_CONFIG_HOME", "XDG_STATE_HOME")}
        os.environ["XDG_CONFIG_HOME"] = os.path.join(self.root, "config")
        os.environ["XDG_STATE_HOME"] = os.path.join(self.root, "state")
        self.omarchy = os.path.join(self.root, "config", "omarchy")
        self.current = os.path.join(self.root, "state", "omarchy", "current")
        os.makedirs(os.path.join(self.current, "theme"))
        os.makedirs(self.omarchy)

    def tearDown(self):
        for k, v in self.saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(self.root)

    def write(self, path, text):
        with open(path, "w") as file:
            file.write(text)


class LookTest(Sandbox):
    def test_look_is_theme_style_and_settings(self):
        self.write(os.path.join(self.current, "theme.name"), "nothing-steel\n")
        self.write(os.path.join(self.current, "theme", "colors.toml"),
                   '# comment\nmode = "dark"\naccent = "#d71921"\nbackground = "#000000"\nforeground = "#DDE3E8"\nbogus = "red"\n')
        self.write(os.path.join(self.omarchy, "style.json"),
                   json.dumps({"id": "dotmatrix", "material": "solid", "cornerScale": 1, "motion": 1, "elevation": 0, "typeface": "profile"}))
        self.write(os.path.join(self.omarchy, "glyph.json"), json.dumps({"glyphEnabled": True, "glyphColor": "dotmatrix.fg"}))
        self.write(os.path.join(self.omarchy, "widgets.json"), json.dumps({"clockStyle": "big", "edgeHover": True}))
        self.write(os.path.join(self.omarchy, "fonts.json"), json.dumps({"profile": "dots-all"}))
        look = deck.current_look()
        self.assertEqual(look["theme"]["id"], "nothing-steel")
        self.assertEqual(look["theme"]["mode"], "dark")
        self.assertEqual(look["theme"]["colors"], {"accent": "#d71921", "background": "#000000", "foreground": "#dde3e8"})
        self.assertEqual(look["style"], {"id": "dotmatrix"})
        self.assertEqual(look["look"]["material"], "solid")
        self.assertTrue(look["glyph"]["glyphEnabled"])
        # Только то, что нужно телефону.
        self.assertEqual(look["widgets"], {"clockStyle": "big"})
        self.assertEqual(look["fonts"], "dots-all")
        self.assertRegex(look["stamp"], r"^[0-9a-f]{16}$")

    def test_stamp_changes_with_the_look(self):
        self.write(os.path.join(self.omarchy, "style.json"), "{}")
        before = deck.look_stamp()
        self.assertEqual(before, deck.look_stamp())
        os.utime(os.path.join(self.omarchy, "style.json"), ns=(1, 1))
        self.assertNotEqual(before, deck.look_stamp())

    def test_light_mode_from_background_when_unsaid(self):
        self.write(os.path.join(self.current, "theme", "colors.toml"), 'background = "#fafafa"\nforeground = "#111111"\n')
        self.assertEqual(deck.current_look()["theme"]["mode"], "light")

    def test_no_theme_no_palette(self):
        self.assertIsNone(deck.current_look()["theme"])

    @unittest.skipUnless(shutil.which("magick"), "needs ImageMagick")
    def test_wallpaper_is_cropped_to_the_phone(self):
        picture = os.path.join(self.root, "wall.png")
        subprocess.run(["magick", "-size", "400x200", "xc:#336699", picture], check=True)
        os.symlink(picture, os.path.join(self.current, "background"))
        self.assertRegex(deck.current_look()["wallpaper"], r"^[0-9a-f]{16}$")
        data = deck.wallpaper(90, 160, "magick")
        self.assertTrue(data.startswith(b"\xff\xd8"))
        size = subprocess.run(["magick", "identify", "-format", "%wx%h", "-"], input=data, capture_output=True, check=True).stdout
        self.assertEqual(size, b"90x160")

    def test_no_wallpaper(self):
        self.assertIsNone(deck.wallpaper(90, 160, "magick"))


def card(ident, text, column, t, p, rank=None):
    c = {"id": ident, "text": text, "column": column, "textStamp": t, "placeStamp": p}
    if rank is not None:
        c["rank"] = rank
    return c


class BoardTest(Sandbox):
    def todo(self):
        with open(ipc.todo_path()) as file:
            return json.load(file)

    def state(self):
        return ipc.load_board()[0]

    def test_merge_is_the_same_both_ways(self):
        a = {"cards": [card("x", "old", 0, 1, 1), card("y", "y", 0, 1, 1)], "removed": {}}
        b = {"cards": [card("x", "new", 2, 5, 6)], "removed": {"y": 3}}
        self.assertEqual(ipc.merge(a, b, 10), ipc.merge(b, a, 10))
        merged = ipc.merge(a, b, 10)
        self.assertEqual([(c["id"], c["text"], c["column"]) for c in merged["cards"]], [("x", "new", 2)])
        self.assertEqual(merged["removed"], {"y": 3})

    def test_removal_loses_to_a_later_edit(self):
        a = {"cards": [card("x", "again", 0, 9, 1)], "removed": {}}
        b = {"cards": [], "removed": {"x": 5}}
        self.assertEqual(len(ipc.merge(a, b, 10)["cards"]), 1)

    def test_first_run_keeps_the_widget(self):
        os.makedirs(self.omarchy, exist_ok=True)
        self.write(ipc.todo_path(), json.dumps([{"text": "reddit", "done": False}, {"text": "milk", "done": True}]))
        self.assertEqual(ipc.main(["tasks", "state"]), 0)
        self.assertEqual(sorted((c["text"], c["column"]) for c in self.state()["cards"]), [("milk", 2), ("reddit", 0)])
        self.assertEqual(sorted(i["text"] for i in self.todo()), ["milk", "reddit"])

    def test_phone_changes_reach_the_widget_and_back(self):
        self.write(ipc.todo_path(), json.dumps([{"text": "reddit", "done": False}]))
        ipc.main(["tasks", "state"])
        board = self.state()
        ident = board["cards"][0]["id"]
        phone = {"cards": [card(ident, "reddit", 2, 1, 10 ** 13), card("p", "from phone", 0, 10 ** 13, 10 ** 13)], "removed": {}}
        ipc.main(["tasks", "sync", json.dumps(phone)])
        self.assertEqual(self.todo(), [{"text": "from phone", "done": False}, {"text": "reddit", "done": True}])
        # Галочку сняли и строку удалили в виджете.
        self.write(ipc.todo_path(), json.dumps([{"text": "reddit", "done": False}]))
        ipc.main(["tasks", "state"])
        board = self.state()
        self.assertEqual([(c["text"], c["column"]) for c in board["cards"]], [("reddit", 0)])
        self.assertIn("p", board["removed"])

    def test_bad_sync_is_refused(self):
        self.assertEqual(ipc.main(["tasks", "sync", "not json"]), 2)
        self.assertEqual(ipc.main(["tasks", "drop"]), 2)


class AppsTest(Sandbox):
    def test_desktop_entries_as_the_launcher_shows_them(self):
        apps = os.path.join(self.root, "data", "applications")
        os.makedirs(os.path.join(apps, "sub"))
        self.write(os.path.join(apps, "kitty.desktop"), "[Desktop Entry]\nType=Application\nName=kitty\nIcon=kitty\nExec=kitty\n")
        self.write(os.path.join(apps, "hidden.desktop"), "[Desktop Entry]\nType=Application\nName=Hidden\nNoDisplay=true\n")
        self.write(os.path.join(apps, "gnome.desktop"), "[Desktop Entry]\nType=Application\nName=Gnome only\nOnlyShowIn=GNOME;\n")
        self.write(os.path.join(apps, "sub", "app.desktop"), "[Desktop Entry]\nType=Application\nName=Sub\n")
        self.write(os.path.join(apps, "link.desktop"), "[Desktop Entry]\nType=Link\nName=Link\n")
        saved = {k: os.environ.get(k) for k in ("XDG_DATA_HOME", "XDG_DATA_DIRS", "XDG_CURRENT_DESKTOP")}
        os.environ.update({"XDG_DATA_HOME": os.path.join(self.root, "data"), "XDG_DATA_DIRS": os.path.join(self.root, "none"),
                           "XDG_CURRENT_DESKTOP": "Hyprland"})
        try:
            found = ipc.desktop_entries()
        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
        self.assertEqual([(a["id"], a["name"]) for a in found], [("kitty", "kitty"), ("sub-app", "Sub")])

    def test_urls_are_checked_before_anything_runs(self):
        self.assertEqual(ipc.apps(["url", "file:///etc/passwd"]), 0)
        self.assertEqual(ipc.apps(["url", "https://a b"]), 0)
        self.assertEqual(ipc.apps(["launch", "../../bin/sh"]), 0)


if __name__ == "__main__":
    unittest.main()
