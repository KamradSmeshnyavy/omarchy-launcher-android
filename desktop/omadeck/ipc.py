#!/usr/bin/env python3
# omadeck-ipc — то, что deck.py делает на компьютере, командами Omarchy.
#
# В Nihil-Shell это был nihil-ipc (IPC шелла NIHIL); здесь — Omarchy 4.5:
#   apps list | launch ID | url URL   .desktop из XDG; uwsm-app; omarchy-launch-browser
#   media toggle | next | previous    omarchy-shell media playPause | next | previous
#   media now                         omarchy-shell media status → { title, artist, player, playing, art }
#   volume up | down | mute           omarchy-audio-output-volume raise | lower | mute-toggle (с OSD)
#   session lock                      omarchy-system-lock
#   toggles state                     { caffeine, night }
#   caffeine on | off | toggle        omarchy-toggle-idle stay-awake | allow-idle | toggle
#   night on | off | toggle           omarchy-toggle-nightlight (--status)
#   tasks state | sync BOARD          доска задач; несделанные/сделанные — в todo-виджете
#                                     (~/.config/omarchy/widgets-todo.json)
#
# Ответ — в stdout, ошибка — ненулевой код. Только стандартная библиотека.

import configparser
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time

APP_ID = re.compile(r"[A-Za-z0-9._+-]{1,160}")
COLUMNS = 3
KEEP_REMOVED = 60 * 24 * 60 * 60 * 1000


def home_dir(variable, fallback):
    return os.environ.get(variable) or os.path.join(os.path.expanduser("~"), fallback)


def run(command, timeout=8):
    try:
        done = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout.strip() if done.returncode == 0 else None


def detach(command):
    """Запустить и не ждать: приложение живёт своей жизнью."""
    try:
        subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        return True
    except OSError:
        return False


def write_atomic(path, text, mode=0o600):
    folder = os.path.dirname(path)
    os.makedirs(folder, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=folder, prefix=".omadeck-")
    try:
        with os.fdopen(fd, "w") as file:
            file.write(text)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


# --- Приложения --------------------------------------------------------------------

def application_dirs():
    dirs = [home_dir("XDG_DATA_HOME", ".local/share")]
    dirs += (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":")
    dirs += [os.path.join(home_dir("XDG_DATA_HOME", ".local/share"), "flatpak/exports/share"), "/var/lib/flatpak/exports/share"]
    return [os.path.join(d, "applications") for d in dict.fromkeys(dirs) if d]


def desktop_entries():
    """Что показывает лаунчер Omarchy: видимые Type=Application, первые по XDG перекрывают."""
    desktops = set((os.environ.get("XDG_CURRENT_DESKTOP") or "Hyprland").split(":"))
    seen, found = set(), []
    for base in application_dirs():
        if not os.path.isdir(base):
            continue
        for root, _, files in os.walk(base):
            for name in sorted(files):
                if not name.endswith(".desktop"):
                    continue
                rel = os.path.relpath(os.path.join(root, name), base)
                app = rel[:-len(".desktop")].replace(os.sep, "-")
                if app in seen:
                    continue
                seen.add(app)
                parser = configparser.RawConfigParser(interpolation=None, strict=False)
                try:
                    parser.read(os.path.join(root, name), encoding="utf-8")
                    entry = parser["Desktop Entry"]
                except (configparser.Error, KeyError, UnicodeDecodeError, OSError):
                    continue
                if entry.get("Type", "Application") != "Application":
                    continue
                if entry.get("NoDisplay", "false").lower() == "true" or entry.get("Hidden", "false").lower() == "true":
                    continue
                only = {d for d in entry.get("OnlyShowIn", "").split(";") if d}
                never = {d for d in entry.get("NotShowIn", "").split(";") if d}
                if (only and not only & desktops) or never & desktops:
                    continue
                label = entry.get("Name", "").strip()
                if not label or not APP_ID.fullmatch(app):
                    continue
                found.append({"id": app, "name": label, "icon": entry.get("Icon", "").strip()})
    return sorted(found, key=lambda e: e["name"].lower())


def apps(argv):
    command = argv[0] if argv else ""
    if command == "list":
        print(json.dumps(desktop_entries()))
        return 0
    if command == "launch" and len(argv) == 2:
        app = argv[1]
        if not APP_ID.fullmatch(app) or not any(e["id"] == app for e in desktop_entries()):
            print("unknown")
            return 0
        print("ok" if detach(["uwsm-app", "--", app + ".desktop"]) else "unknown")
        return 0
    if command == "url" and len(argv) == 2:
        url = argv[1]
        if not re.match(r"https?://", url) or len(url) > 2048 or any(ch.isspace() for ch in url):
            print("invalid")
            return 0
        print("ok" if detach(["omarchy-launch-browser", url]) else "invalid")
        return 0
    return 2


# --- Медиа, громкость, блокировка, переключатели -----------------------------------

def media(argv):
    command = argv[0] if argv else ""
    if command == "now":
        text = run(["omarchy-shell", "media", "status"])
        try:
            status = json.loads(text or "")
        except ValueError:
            return 1
        if not isinstance(status, dict) or not status.get("hasMedia"):
            print("")
            return 0
        print(json.dumps({
            "title": status.get("title") or "",
            "artist": status.get("artist") or "",
            "player": status.get("identity") or "",
            "playing": bool(status.get("playing")),
            "art": status.get("artUrl") or "",
        }))
        return 0
    method = {"toggle": "playPause", "next": "next", "previous": "previous"}.get(command)
    if method is None:
        return 2
    return 0 if run(["omarchy-shell", "media", method]) is not None else 1


def volume(argv):
    action = {"up": "raise", "down": "lower", "mute": "mute-toggle"}.get(argv[0] if argv else "")
    if action is None:
        return 2
    return 0 if run(["omarchy-audio-output-volume", action]) is not None else 1


def session(argv):
    if argv == ["lock"]:
        return 0 if detach(["omarchy-system-lock"]) else 1
    return 2


def caffeine_on():
    return os.path.isfile(os.path.join(home_dir("XDG_STATE_HOME", ".local/state"), "omarchy/indicators/stay-awake"))


def night_on():
    try:
        return bool(json.loads(run(["omarchy-toggle-nightlight", "--status"]) or "{}").get("enabled"))
    except (ValueError, AttributeError):
        return None


def toggles(argv):
    if argv != ["state"]:
        return 2
    state = {"caffeine": caffeine_on()}
    night = night_on()
    if night is not None:
        state["night"] = night
    print(json.dumps(state))
    return 0


def caffeine(argv):
    action = {"on": "stay-awake", "off": "allow-idle", "toggle": "toggle"}.get(argv[0] if argv else "")
    if action is None:
        return 2
    return 0 if run(["omarchy-toggle-idle", action]) is not None else 1


def night(argv):
    action = argv[0] if argv else ""
    if action not in ("on", "off", "toggle"):
        return 2
    now = night_on()
    if action == "toggle" or (action == "on") != bool(now):
        return 0 if run(["omarchy-toggle-nightlight"]) is not None else 1
    return 0


# --- Доска задач --------------------------------------------------------------------
#
# Те же правила, что core/TaskBoard.kt телефона и TaskMerge.js апстрима: у каждой карточки
# побеждает последний текст и последнее место, удаление — всё, что было до него.
# Доска лежит в ~/.local/state/omadeck/tasks.json, а её отражение — в todo-виджете
# десктопа: несделанные (колонки 0 и 1) и сделанные (2). Правки в виджете (новая строка,
# галочка, удаление) становятся правками доски при следующем обращении.

def board_path():
    return os.path.join(home_dir("XDG_STATE_HOME", ".local/state"), "omadeck", "tasks.json")


def todo_path():
    return os.path.join(home_dir("XDG_CONFIG_HOME", ".config"), "omarchy", "widgets-todo.json")


def now_ms():
    return int(time.time() * 1000)


def position(card):
    rank = card.get("rank")
    return rank if isinstance(rank, (int, float)) else float(card["placeStamp"])


def ordered(cards):
    return sorted(cards, key=lambda c: (c["column"], position(c), c["id"]))


def combine(x, y):
    if x["textStamp"] != y["textStamp"]:
        text = x if x["textStamp"] > y["textStamp"] else y
    else:
        text = x if x["text"] >= y["text"] else y
    if x["placeStamp"] != y["placeStamp"]:
        place = x if x["placeStamp"] > y["placeStamp"] else y
    elif ("rank" in x) != ("rank" in y):
        place = x if "rank" in x else y
    elif x["column"] != y["column"]:
        place = x if x["column"] > y["column"] else y
    else:
        place = y if "rank" in x and "rank" in y and y["rank"] > x["rank"] else x
    card = {"id": x["id"], "text": text["text"], "column": place["column"],
            "textStamp": text["textStamp"], "placeStamp": place["placeStamp"]}
    if "rank" in place:
        card["rank"] = place["rank"]
    return card


def merge(a, b, now):
    mine = {c["id"]: c for c in a.get("cards", [])}
    theirs = {c["id"]: c for c in b.get("cards", [])}
    ra, rb = a.get("removed", {}), b.get("removed", {})
    cards, removed = [], {}
    for ident in dict.fromkeys([*mine, *theirs, *ra, *rb]):
        x, y = mine.get(ident), theirs.get(ident)
        card = combine(x, y) if x and y else x or y
        gone = max(ra.get(ident, -1), rb.get(ident, -1))
        if card and gone < max(card["textStamp"], card["placeStamp"]):
            cards.append(card)
        elif gone >= 0 and now - gone < KEEP_REMOVED:
            removed[ident] = gone
    return {"cards": ordered(cards), "removed": removed}


def load_board():
    try:
        with open(board_path()) as file:
            found = json.load(file)
        mirror = found.get("mirror", [])
        return {"cards": found.get("cards", []), "removed": found.get("removed", {})}, mirror
    except (OSError, ValueError, AttributeError):
        return {"cards": [], "removed": {}}, None


def read_todo():
    try:
        with open(todo_path()) as file:
            found = json.load(file)
        return [{"text": str(i.get("text", "")).strip(), "done": bool(i.get("done"))}
                for i in found if isinstance(i, dict) and str(i.get("text", "")).strip()]
    except (OSError, ValueError, TypeError):
        return None


def mirror_of(board):
    return [{"text": c["text"], "done": c["column"] == COLUMNS - 1} for c in ordered(board["cards"])]


def from_todo(board, last_mirror, todo, now):
    """Что поменяли в виджете с прошлого отражения → правки доски. В первый раз
    (отражения ещё нет) всё из виджета становится карточками: виджет не стирается."""
    if todo is None or todo == last_mirror:
        return board
    cards = {c["id"]: dict(c) for c in board["cards"]}
    removed = dict(board["removed"])
    by_text = {}
    for c in ordered(cards.values()):
        by_text.setdefault(c["text"], c["id"])
    kept = set()
    for item in todo:
        ident = by_text.get(item["text"])
        if ident is None or ident in kept:
            ident = hashlib.sha1(f"{item['text']}:{now}:{len(cards)}".encode()).hexdigest()[:12]
            cards[ident] = {"id": ident, "text": item["text"][:160], "column": COLUMNS - 1 if item["done"] else 0,
                            "textStamp": now, "placeStamp": now}
        else:
            card = cards[ident]
            done = card["column"] == COLUMNS - 1
            if item["done"] != done:
                card["column"] = COLUMNS - 1 if item["done"] else 0
                card["placeStamp"] = now
                card.pop("rank", None)
        kept.add(ident)
    for ident in list(cards):
        if ident not in kept:
            del cards[ident]
            removed[ident] = now
    return {"cards": ordered(cards.values()), "removed": removed}


def save_board(board):
    mirror = mirror_of(board)
    write_atomic(board_path(), json.dumps({**board, "mirror": mirror}))
    if read_todo() != mirror:
        write_atomic(todo_path(), json.dumps(mirror, ensure_ascii=False, indent=2) + "\n", mode=0o600)


def tasks(argv):
    now = now_ms()
    board, last_mirror = load_board()
    board = from_todo(board, last_mirror, read_todo(), now)
    if argv and argv[0] == "sync" and len(argv) == 2:
        try:
            phone = json.loads(argv[1])
        except ValueError:
            return 2
        board = merge(board, phone, now)
    elif argv != ["state"]:
        return 2
    else:
        board = merge(board, {"cards": [], "removed": {}}, now)
    save_board(board)
    print(json.dumps({**board, "now": now}))
    return 0


COMMANDS = {
    "apps": apps, "media": media, "volume": volume, "session": session, "toggles": toggles,
    "caffeine": caffeine, "night": night, "tasks": tasks,
}


def main(argv):
    if not argv or argv[0] not in COMMANDS:
        print("omadeck-ipc apps|media|volume|session|toggles|caffeine|night|tasks …", file=sys.stderr)
        return 2
    return COMMANDS[argv[0]](argv[1:])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
