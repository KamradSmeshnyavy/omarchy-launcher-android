#!/usr/bin/env python3
"""Темы Omarchy, стили и настройки Customizer → app/src/main/assets/looks.

Запуск: just export (или python3 -I tools/export-looks.py). Вывод детерминированный:
повторный экспорт без изменений в источниках не меняет ни байта.

Источники:
  темы      /usr/share/omarchy/themes/*, ~/.config/omarchy/themes/* (свои перекрывают стоковые)
            colors.toml, а у старых тем без него — alacritty.toml
  стили     <customizer>/styles/*.json
  настройки <customizer>/settings.json (только таблицы, которые имеют смысл на телефоне)
  перевод   <customizer>/i18n/ru.json (только строки настроек и стилей)
  звуки     <customizer>/sounds/<пакет>/*.ogg
  шрифты    <customizer>/fonts и ~/.local/share/fonts/dotmatrix-build (только OFL)
"""

import argparse
import colorsys
import json
import os
import shutil
import subprocess
import sys
import tomllib
from pathlib import Path

HOME = Path.home()
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
CUSTOMIZER = HOME / "dotfiles/apps/omarchy-shell/plugins/kamradsmeshnyavy.customizer"
THEME_DIRS = [Path("/usr/share/omarchy/themes"), HOME / ".config/omarchy/themes"]
FONT_BUILD = HOME / ".local/share/fonts/dotmatrix-build"

# Таблицы Customizer, которые переносятся на телефон, и ключи в них, которые там ни к чему.
TABLES = {
    "GLYPH_TWEAKS": set(),
    "SOUND_TWEAKS": {"windowSounds"},
    "DOTIFY_TWEAKS": set(),
    "DOTMATRIX_TWEAKS": set(),
    "LAUNCHER_TWEAKS": set(),
    "STYLE_TWEAKS": {
        "styleGeometry", "styleAnims", "styleOpacity", "styleShader", "styleBar", "styleLock",
        "styleWallpaperFx", "styleOsd", "styleNotifications",
    },
    "PRESET_TWEAKS": {
        "presetGeometry", "presetAnims", "presetOpacity", "presetShader", "presetBar", "presetLock",
        "presetWallpaperFx", "presetOsd", "presetNotifications",
    },
    # Виджеты рабочего стола: на телефоне есть часы, «сейчас играет» и todo (доска deck).
    "WIDGET_TWEAKS": {
        "desktopWidgets", "edgeHover", "edgeMargin", "screens", "clockAnchor", "mediaAnchor", "visualizer",
        "visualizerStyle", "visualizerBars", "visualizerHeight", "visualizerAlpha", "visualizerIdle",
        "sysinfo", "sysinfoAnchor", "network", "networkAnchor", "todoAnchor", "quote", "quoteAnchor",
        "photo", "photoAnchor", "photoDir", "photoInterval",
    },
}

# Строки, которых нет в Customizer, но есть у телефона: (таблица, строка, перевод подписи и значений).
EXTRA_ITEMS = {
    "LAUNCHER_TWEAKS": [
        {"key": "deskLayout", "type": "enum", "values": ["stack", "grid"],
         "label": "Desk layout (stack — cards top to bottom, grid — cells as in Android launchers)"},
        {"key": "drawerSkin", "type": "enum", "values": ["grid", "compact", "list", "dotmatrix"],
         "label": "Drawer skin (grid — as in NIHIL, compact, list, dotmatrix — mono icons, dot names)"},
    ],
}
EXTRA_RU = {
    "Drawer skin (grid — as in NIHIL, compact, list, dotmatrix — mono icons, dot names)":
        "Скин ящика (grid — как в NIHIL, compact — плотнее, list — списком, dotmatrix — моно-значки и имена точками)",
    "grid": "сетка", "compact": "плотно", "list": "список", "stack": "стопка",
    "Desk layout (stack — cards top to bottom, grid — cells as in Android launchers)":
        "Раскладка столов (стопка — карточки сверху вниз, сетка — клетки, как в лаунчерах Android)",
}

# Что из стиля нужно лаунчеру.
STYLE_KEYS = ("name", "name_ru", "note", "note_ru", "look", "theme", "glyph", "fonts", "sounds", "launcher", "barSkin", "barSkinData")
STYLE_WIDGET_KEYS = ("widgetStyle", "clockStyle", "media")

# Шрифты: (файл в assets/fonts, источник). Только свободные (OFL), без Ndot/NType.
# DotMatrix LED (7,6 МБ) не берём: «точки везде» на телефоне — Lumen.
FONTS = [
    ("DotMatrixDot.ttf", CUSTOMIZER / "fonts/DotMatrixDot.ttf"),
    ("DotMatrixMono-Regular.ttf", FONT_BUILD / "DotMatrixMono-Regular.ttf"),
    ("DotMatrixMono-Bold.ttf", FONT_BUILD / "DotMatrixMono-Bold.ttf"),
    ("DotMatrixPixel-Regular.ttf", FONT_BUILD / "DotMatrixPixel-Regular.ttf"),
    ("DotMatrixLumenSans-Regular.ttf", FONT_BUILD / "DotMatrixLumenSans-Regular.ttf"),
    ("DotMatrixLumenMono-Regular.ttf", FONT_BUILD / "DotMatrixLumenMono-Regular.ttf"),
    ("Geist.ttf", HOME / ".local/share/fonts/geist/Geist[wght].ttf"),
    ("Caveat.ttf", CUSTOMIZER / "fonts/Caveat.ttf"),
    ("Pangolin-Regular.ttf", CUSTOMIZER / "fonts/Pangolin-Regular.ttf"),
    ("PixelifySans.ttf", CUSTOMIZER / "fonts/PixelifySans.ttf"),
    ("OFL-MatrixSans.txt", FONT_BUILD / "OFL-MatrixSans.txt"),
    ("OFL-DotMatrix.txt", FONT_BUILD / "OFL.txt"),
    ("OFL-Customizer.txt", CUSTOMIZER / "fonts/OFL.txt"),
    ("OFL-Geist.txt", HOME / ".local/share/fonts/geist/OFL.txt"),
]

ROLE_KEYS = ("background", "foreground", "accent", "muted", "urgent", "selection", "cursor")
NAMED = ("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")


def norm(c):
    """#rgb / #rrggbb / 0xrrggbb → #rrggbb (нижний регистр) или None."""
    if not isinstance(c, str):
        return None
    c = c.strip().strip("'\"")
    if c.startswith("0x"):
        c = "#" + c[2:]
    if not c.startswith("#"):
        return None
    h = c[1:]
    if len(h) == 3:
        h = "".join(ch * 2 for ch in h)
    if len(h) == 8:
        h = h[:6]
    if len(h) != 6 or any(ch not in "0123456789abcdefABCDEF" for ch in h):
        return None
    return "#" + h.lower()


def luminance(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def mix(a, b, t):
    pa = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    pb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02x}" for x, y in zip(pa, pb))


def saturation(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return colorsys.rgb_to_hls(r, g, b)[2]


def from_colors_toml(path):
    data = tomllib.loads(path.read_text(encoding="utf-8", errors="replace"))
    out = {k: norm(v) for k, v in data.items() if norm(v)}
    mode = data.get("mode") if data.get("mode") in ("dark", "light") else None
    for i in range(16):
        key = f"color{i}"
        if key in out:
            continue
        name = NAMED[i % 8]
        alias = name if i < 8 else f"bright_{name}"
        if out.get(alias):
            out[key] = out[alias]
    for i, name in enumerate(NAMED):
        out.setdefault(name, out.get(f"color{i}"))
        out.setdefault(f"bright_{name}", out.get(f"color{i + 8}"))
    return {k: v for k, v in out.items() if v}, mode


def from_alacritty(path):
    data = tomllib.loads(path.read_text(encoding="utf-8", errors="replace"))
    colors = data.get("colors", {})
    out = {}
    primary = colors.get("primary", {})
    out["background"] = norm(primary.get("background"))
    out["foreground"] = norm(primary.get("foreground"))
    for group, prefix, base in (("normal", "", 0), ("bright", "bright_", 8)):
        for i, name in enumerate(NAMED):
            c = norm(colors.get(group, {}).get(name))
            if c:
                out[prefix + name] = c
                out[f"color{base + i}"] = c
    cursor = norm(colors.get("cursor", {}).get("cursor"))
    if cursor:
        out["cursor"] = cursor
    sel = norm(colors.get("selection", {}).get("background"))
    if sel:
        out["selection"] = sel
    return {k: v for k, v in out.items() if v}, None


def complete(c, mode):
    """Роли, которых нет в теме, выводятся так же, как это делает шелл Omarchy."""
    bg = c.get("background")
    fg = c.get("foreground")
    if not bg or not fg:
        return None
    mode = mode or ("light" if luminance(bg) > 0.5 else "dark")
    if not c.get("accent"):
        # Курсор, если он цветной, иначе синий из палитры.
        cursor = c.get("cursor")
        c["accent"] = cursor if cursor and saturation(cursor) > 0.25 else c.get("blue") or fg
    c.setdefault("muted", c.get("bright_black") or mix(bg, fg, 0.45))
    c.setdefault("urgent", c.get("red") or "#d71921")
    c.setdefault("selection", c.get("selection_background") or mix(bg, c["accent"], 0.3))
    c.setdefault("cursor", fg)
    return mode


def thumbnail(theme_dir, out_path):
    """Первые обои темы (или её preview) → маленький webp для галереи."""
    picks = []
    bgdir = theme_dir / "backgrounds"
    if bgdir.is_dir():
        picks = sorted(p for p in bgdir.iterdir() if p.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp"))
    for name in ("preview.png", "preview.jpg", "theme.png"):
        if (theme_dir / name).is_file():
            picks.insert(0, theme_dir / name)
    if not picks:
        return False
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["magick", str(picks[0]) + "[0]", "-auto-orient", "-thumbnail", "216x384^", "-gravity", "center",
           "-extent", "216x384", "-strip", "-quality", "62", "-define", "webp:method=6", f"webp:{out_path}"]
    return subprocess.run(cmd, capture_output=True).returncode == 0


def label(slug):
    return " ".join(w.capitalize() for w in slug.replace("_", "-").split("-") if w)


def export_themes(out, with_thumbs):
    # Ключ — имя каталога в списке (симлинк в ~/.config/omarchy/themes носит имя темы).
    found = {}
    for base in THEME_DIRS:
        if not base.is_dir():
            continue
        for entry in sorted(base.iterdir()):
            if entry.is_dir():
                found[entry.name] = entry
    themes = []
    thumbs = out / "thumbs"
    if with_thumbs and thumbs.exists():
        shutil.rmtree(thumbs)
    for slug in sorted(found):
        d = found[slug]
        colors, mode = None, None
        if (d / "colors.toml").is_file():
            try:
                colors, mode = from_colors_toml(d / "colors.toml")
            except (tomllib.TOMLDecodeError, OSError):
                colors = None
        if not colors and (d / "alacritty.toml").is_file():
            try:
                colors, mode = from_alacritty(d / "alacritty.toml")
            except (tomllib.TOMLDecodeError, OSError):
                colors = None
        if not colors:
            print(f"  пропуск {slug}: нет цветов", file=sys.stderr)
            continue
        mode = complete(colors, mode)
        if not mode:
            print(f"  пропуск {slug}: нет background/foreground", file=sys.stderr)
            continue
        entry = {"id": slug, "name": label(slug), "mode": mode, "colors": dict(sorted(colors.items()))}
        if with_thumbs and thumbnail(d, thumbs / f"{slug}.webp"):
            entry["thumb"] = f"thumbs/{slug}.webp"
        elif not with_thumbs and (thumbs / f"{slug}.webp").is_file():
            entry["thumb"] = f"thumbs/{slug}.webp"
        themes.append(entry)
    return themes


def export_styles():
    styles = []
    for f in sorted((CUSTOMIZER / "styles").glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        s = {"id": f.stem}
        for k in STYLE_KEYS:
            if k in d:
                s[k] = d[k]
        look = dict(s.get("look", {}))
        # Текстура — путь на ПК; в APK лежит своя копия под тем же именем.
        if look.get("texture"):
            look["texture"] = "textures/" + Path(look["texture"]).name
        s["look"] = look
        w = d.get("widgets", {})
        s["widgets"] = {k: w[k] for k in STYLE_WIDGET_KEYS if k in w}
        styles.append(s)
    return styles


BAR_SKINS = HOME / "dotfiles/apps/omarchy-shell/plugins/kamradsmeshnyavy.bar/skins"
LOCK_DESIGNS = HOME / "dotfiles/apps/omarchy-shell/plugins/kamradsmeshnyavy.lock/designs/designs.json"


def export_bars():
    """Скины бара Omarchy (kamradsmeshnyavy.bar/skins): токены как есть, по id."""
    skins = []
    for f in sorted(BAR_SKINS.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        d.pop("texture", None)
        skins.append({"id": f.stem, **d})
    return skins


def export_locks():
    """Каталог дизайнов экрана блокировки (kamradsmeshnyavy.lock/designs/designs.json)."""
    if not LOCK_DESIGNS.is_file():
        return []
    keep = ("id", "name", "description", "description_ru", "tags")
    return [{k: d[k] for k in keep if k in d} for d in json.loads(LOCK_DESIGNS.read_text(encoding="utf-8"))]


def export_settings():
    data = json.loads((CUSTOMIZER / "settings.json").read_text(encoding="utf-8"))
    tables = []
    for t in data["tables"]:
        if t["id"] not in TABLES:
            continue
        drop = TABLES[t["id"]]
        items = [i for i in t["items"] if i.get("key") not in drop]
        # Секции, оставшиеся без строк, уходят.
        clean = []
        for i, item in enumerate(items):
            if "section" in item:
                nxt = items[i + 1] if i + 1 < len(items) else None
                if not nxt or "section" in nxt:
                    continue
            clean.append(item)
        clean += EXTRA_ITEMS.get(t["id"], [])
        tables.append({"id": t["id"], "category": t.get("category"), "file": Path(t.get("file", "")).name,
                       "items": clean})
    return {"version": data.get("version"), "tables": tables}


def export_i18n(settings, styles):
    ru = json.loads((CUSTOMIZER / "i18n/ru.json").read_text(encoding="utf-8"))
    wanted = set()
    for t in settings["tables"]:
        for item in t["items"]:
            for k in ("label", "section"):
                if item.get(k):
                    wanted.add(item[k])
            for v in item.get("values", []):
                wanted.add(v)
    ru = {**ru, **EXTRA_RU}
    return {k: ru[k] for k in sorted(wanted) if k in ru}


def export_sounds(out):
    dest = out.parent / "sounds"
    if dest.exists():
        shutil.rmtree(dest)
    packs = []
    for pack in sorted((CUSTOMIZER / "sounds").iterdir()):
        if not pack.is_dir():
            continue
        files = sorted(p for p in pack.glob("*.ogg"))
        if not files:
            continue
        (dest / pack.name).mkdir(parents=True)
        for f in files:
            shutil.copyfile(f, dest / pack.name / f.name)
        packs.append({"id": pack.name, "events": [f.stem for f in files]})
    return packs


def export_fonts():
    dest = ASSETS / "fonts"
    dest.mkdir(parents=True, exist_ok=True)
    for name, src in FONTS:
        if src.is_file():
            shutil.copyfile(src, dest / name)
        else:
            print(f"  нет шрифта {src}", file=sys.stderr)


def export_textures():
    dest = ASSETS / "looks/textures"
    dest.mkdir(parents=True, exist_ok=True)
    src = CUSTOMIZER / "textures"
    if src.is_dir():
        for f in sorted(src.glob("*.png")):
            shutil.copyfile(f, dest / f.name)


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=False) + "\n"
    if not path.is_file() or path.read_text(encoding="utf-8") != text:
        path.write_text(text, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-thumbs", action="store_true", help="не пересобирать миниатюры (быстро)")
    args = ap.parse_args()
    if not CUSTOMIZER.is_dir():
        sys.exit(f"нет Customizer: {CUSTOMIZER}")
    out = ASSETS / "looks"
    themes = export_themes(out, not args.no_thumbs)
    styles = export_styles()
    settings = export_settings()
    write(out / "themes.json", {"themes": themes})
    write(out / "styles.json", {"styles": styles})
    write(out / "settings.json", settings)
    write(out / "i18n/ru.json", export_i18n(settings, styles))
    write(out / "sounds.json", {"packs": export_sounds(out)})
    write(out / "bars.json", {"skins": export_bars()})
    write(out / "locks.json", {"designs": export_locks()})
    export_fonts()
    export_textures()
    print(f"тем: {len(themes)}, стилей: {len(styles)}, таблиц настроек: {len(settings['tables'])}")


if __name__ == "__main__":
    main()
