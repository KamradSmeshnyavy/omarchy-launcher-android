#!/usr/bin/env python3
# omadeck — телефон как пульт для Omarchy (DOT-MATRIX Launcher, docs/deck.md).
#
# Порт deck.py из Nihil-Shell (poligle, MIT — см. NOTICE): тот же протокол v1,
# свои имена (omadeck://pair, _omadeck._tcp, ~/.local/state/omadeck) и вместо
# nihil-ipc — omadeck-ipc (ipc.py), который делает всё командами Omarchy.
# Сверху апстрима: /v1/look и /v1/wallpaper для «следовать компьютеру» и
# объявление в mDNS через avahi-publish.
#
# Телефон говорит с ним по HTTPS в локальной сети. Сертификат свой, сделанный
# при первом запуске (openssl), телефон его пинит: в QR — SHA-256 его открытого
# ключа. Для сопряжения нужен ещё одноразовый код из QR; телефон получает свой
# токен, здесь хранится только его SHA-256, пока телефон не забыт.
#
# Только стандартная библиотека.
#
#   omadeck serve              служба (systemd --user: omadeck.service)
#   omadeck pair [--json]      код для сопряжения: QR в терминале
#   omadeck cancel             код больше не годится
#   omadeck devices [--json]   сопряжённые телефоны
#   omadeck forget ID|all      забыть один или все
#
# Сертификат и телефоны — в $XDG_STATE_HOME/omadeck, команды — на сокете
# $XDG_RUNTIME_DIR/omadeck.sock (один JSON-объект на строку).

import argparse
import base64
import collections
import errno
import fcntl
import functools
import hashlib
import hmac
import html.parser
import http.client
import http.server
import ipaddress
import json
import math
import os
import re
import secrets
import shutil
import signal
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

API = 1
SERVICE = "_omadeck._tcp"
PORT = 64445                    # NIHIL на клавиатуре телефона, как в апстриме
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 8
CODE_LIFETIME = 300             # seconds a code pairs
CODE_ATTEMPTS = 5               # wrong codes before it stops pairing
MAX_BODY = 4096
TASKS_MAX = 256 * 1024     # a phone's whole kanban
COVER_MAX = 4 * 1024 * 1024
CARDS_MAX = 1000
FAIL_WINDOW = 60                # seconds, and how many failures in them
FAIL_LIMIT = 10
SEEN_EVERY = 60                 # seconds between writes of "last seen"
ICON_SIZE = 128
APPS_FOR = 20                   # seconds the shell's list of apps is kept
APP_ID = re.compile(r"[A-Za-z0-9._+-]{1,160}")
# Interfaces the phone can't reach this computer through: containers' and
# virtual machines' bridges and their ends. A VPN (Tailscale, WireGuard) stays.
VIRTUAL = re.compile(r"(docker|br-|virbr|veth|vnet|lxcbr|lxdbr|podman|cni|flannel)")
ACTIONS = {
    "media": ("toggle", "next", "previous"),
    "volume": ("up", "down", "mute"),
    "caffeine": ("on", "off", "toggle"),
    "night": ("on", "off", "toggle"),
}


def now():
    return time.time()


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def home_dir(variable, fallback):
    return os.environ.get(variable) or os.path.join(os.path.expanduser("~"), fallback)


def state_dir():
    return os.path.join(home_dir("XDG_STATE_HOME", ".local/state"), "omadeck")


def cache_dir():
    return os.path.join(home_dir("XDG_CACHE_HOME", ".cache"), "omadeck")


def control_path():
    runtime = os.environ.get("XDG_RUNTIME_DIR") or state_dir()
    return os.path.join(runtime, "omadeck.sock")


def write_private(path, text):
    """Written whole or not at all, only for the user (0600)."""
    folder = os.path.dirname(path)
    os.makedirs(folder, mode=0o700, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=folder, prefix=".deck-")
    try:
        with os.fdopen(fd, "w") as file:
            file.write(text)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


# --- The certificate -----------------------------------------------------------

def ensure_certificate(folder, openssl):
    """cert.pem and key.pem in FOLDER, made once: EC P-256, ten years."""
    cert = os.path.join(folder, "cert.pem")
    key = os.path.join(folder, "key.pem")
    if os.path.isfile(cert) and os.path.isfile(key):
        return cert, key
    os.makedirs(folder, mode=0o700, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=folder, prefix=".cert-") as work:
        new_cert = os.path.join(work, "cert.pem")
        new_key = os.path.join(work, "key.pem")
        old = os.umask(0o077)
        try:
            subprocess.run([openssl, "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                            "-nodes", "-days", "3650", "-subj", "/CN=omadeck",
                            "-keyout", new_key, "-out", new_cert],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)
        finally:
            os.umask(old)
        os.chmod(new_key, 0o600)
        os.replace(new_key, key)
        os.replace(new_cert, cert)
    return cert, key


def _tlv(data, at):
    """A DER element at AT: its tag, where its value starts and ends."""
    tag = data[at]
    length = data[at + 1]
    at += 2
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[at:at + count], "big")
        at += count
    return tag, at, at + length


def public_key_info(der):
    """The certificate's SubjectPublicKeyInfo, whole, as the phone hashes it
    (Java's PublicKey.getEncoded())."""
    _, start, _ = _tlv(der, 0)
    _, tbs, tbs_end = _tlv(der, start)
    fields = []
    at = tbs
    while at < tbs_end:
        tag, _, end = _tlv(der, at)
        fields.append((tag, at, end))
        at = end
    if fields and fields[0][0] == 0xA0:
        fields = fields[1:]
    # serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo
    if len(fields) < 6 or fields[5][0] != 0x30:
        raise ValueError("not a certificate this can read")
    _, begin, end = fields[5]
    return der[begin:end]


def pin_of(cert_path):
    with open(cert_path) as file:
        der = ssl.PEM_cert_to_DER_cert(file.read())
    return b64url(hashlib.sha256(public_key_info(der)).digest())


# --- The phones ------------------------------------------------------------------

class Devices:
    """The phones paired: a name, when it was paired and last seen, and the
    SHA-256 of its token."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.items = []
        self.saved = 0
        try:
            with open(path) as file:
                found = json.load(file)
            self.items = [item for item in found.get("devices", [])
                          if isinstance(item, dict) and isinstance(item.get("token"), str) and isinstance(item.get("id"), str)]
        except (FileNotFoundError, ValueError, AttributeError):
            self.items = []

    def _save(self):
        write_private(self.path, json.dumps({"devices": self.items}, indent=2) + "\n")
        self.saved = now()

    def add(self, name):
        token = secrets.token_urlsafe(32)
        with self.lock:
            ids = {item["id"] for item in self.items}
            device = secrets.token_hex(4)
            while device in ids:
                device = secrets.token_hex(4)
            moment = int(now())
            self.items.append({"id": device, "name": name, "token": digest(token), "paired": moment, "seen": moment})
            self._save()
        return device, token

    def find(self, token):
        if not token:
            return None
        wanted = digest(token)
        found = None
        with self.lock:
            for item in self.items:
                if hmac.compare_digest(item["token"], wanted):
                    found = item
        return found

    def touch(self, device):
        with self.lock:
            device["seen"] = int(now())
            if now() - self.saved >= SEEN_EVERY:
                self._save()

    def flush(self):
        with self.lock:
            if self.items or os.path.exists(self.path):
                self._save()

    def forget(self, device):
        with self.lock:
            kept = [item for item in self.items if device != "all" and item["id"] != device]
            if len(kept) == len(self.items):
                return False
            self.items = kept
            self._save()
        return True

    def listing(self):
        with self.lock:
            return [{key: item.get(key) for key in ("id", "name", "paired", "seen")} for item in self.items]


class Pairing:
    """The one code that pairs, while Preferences shows it: once, for a few
    minutes, and not after a few wrong ones."""

    def __init__(self):
        self.lock = threading.Lock()
        self.code = None
        self.expires = 0
        self.attempts = 0

    def open(self):
        with self.lock:
            self.code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
            self.expires = now() + CODE_LIFETIME
            self.attempts = 0
            return self.code, self.expires

    def close(self):
        with self.lock:
            self.code = None

    @property
    def open_now(self):
        with self.lock:
            return self.code is not None and now() <= self.expires

    def take(self, code):
        """"ok" (and it no longer pairs), "closed", "expired" or "wrong"."""
        with self.lock:
            if self.code is None:
                return "closed"
            if now() > self.expires:
                self.code = None
                return "expired"
            given = str(code or "").strip().upper()
            if not hmac.compare_digest(given.encode(), self.code.encode()):
                self.attempts += 1
                if self.attempts >= CODE_ATTEMPTS:
                    self.code = None
                return "wrong"
            self.code = None
            return "ok"


class Throttle:
    """Who keeps failing (a token, a code) waits a minute."""

    def __init__(self):
        self.lock = threading.Lock()
        self.failures = {}

    def _recent(self, address):
        cutoff = now() - FAIL_WINDOW
        times = [moment for moment in self.failures.get(address, []) if moment > cutoff]
        if times:
            self.failures[address] = times
        else:
            self.failures.pop(address, None)
        return times

    def blocked(self, address):
        with self.lock:
            return len(self._recent(address)) >= FAIL_LIMIT

    def fail(self, address):
        with self.lock:
            self.failures.setdefault(address, []).append(now())
            self._recent(address)


# --- What it may do -------------------------------------------------------------

def valid_url(url):
    """A web page: http or https, with a host, no spaces, not too long."""
    if not isinstance(url, str) or not 0 < len(url) <= 2048:
        return False
    if any(ord(char) < 0x21 or ord(char) == 0x7F for char in url):
        return False
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.hostname)


def clean_name(name, fallback):
    text = "".join(char for char in str(name or "") if char.isprintable()).strip()
    return text[:40] or fallback


class Session:
    """The shell and the session, from here: everything the deck does."""

    def __init__(self, config):
        self.config = config
        self.lock = threading.Lock()
        self.cached = None
        self.cached_at = 0

    def run(self, command, timeout=6):
        try:
            done = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.SubprocessError):
            return None
        return done.stdout.strip() if done.returncode == 0 else None

    cover_cache = None

    def ipc(self, *arguments):
        return self.run([self.config["ipc"], *arguments])

    def apps(self, fresh=False):
        """What the launcher has: [{ id, name, icon }], by name."""
        with self.lock:
            if not fresh and self.cached is not None and now() - self.cached_at < APPS_FOR:
                return self.cached
        text = self.ipc("apps", "list")
        if text is None:
            return None
        try:
            found = json.loads(text)
        except ValueError:
            return None
        listed = []
        for entry in found if isinstance(found, list) else []:
            if not isinstance(entry, dict):
                continue
            app, name = entry.get("id"), entry.get("name")
            if isinstance(app, str) and APP_ID.fullmatch(app) and isinstance(name, str) and name:
                icon = entry.get("icon") if isinstance(entry.get("icon"), str) else ""
                listed.append({"id": app, "name": name, "icon": icon})
        with self.lock:
            self.cached, self.cached_at = listed, now()
        return listed

    def app(self, app):
        """One of them, by id; asking the shell again when it is new."""
        if not isinstance(app, str) or not APP_ID.fullmatch(app):
            return None
        for fresh in (False, True):
            listed = self.apps(fresh)
            if listed is None:
                return None
            for entry in listed:
                if entry["id"] == app:
                    return entry
        return None

    def launch(self, app):
        if self.app(app) is None:
            return "unknown"
        answer = self.ipc("apps", "launch", app)
        return "ok" if answer == "ok" else "unknown" if answer == "unknown" else "shell"

    def open_url(self, url):
        if not valid_url(url):
            return "invalid"
        answer = self.ipc("apps", "url", url)
        return "ok" if answer == "ok" else "invalid" if answer == "invalid" else "shell"

    def media(self, action):
        return self.ipc("media", action) is not None

    def playing(self):
        text = self.ipc("media", "now")
        if not text:
            return None
        try:
            found = json.loads(text)
        except ValueError:
            return None
        if not isinstance(found, dict):
            return None
        # The cover's address stays here: the phone gets a token that changes with it.
        art = str(found.pop("art", "") or "")
        found["cover"] = hashlib.sha256(art.encode()).hexdigest()[:16] if art else ""
        return found

    def cover(self, token):
        """The cover of what plays as (bytes, type), when [token] is still its token."""
        text = self.ipc("media", "now")
        try:
            art = str(json.loads(text or "null").get("art") or "")
        except (ValueError, AttributeError):
            return None
        if not art or hashlib.sha256(art.encode()).hexdigest()[:16] != token:
            return None
        with self.lock:
            if self.cover_cache and self.cover_cache[0] == art:
                return self.cover_cache[1]
        found = None
        try:
            if art.startswith("file://"):
                local = urllib.parse.unquote(urllib.parse.urlsplit(art).path)
                if os.path.getsize(local) <= COVER_MAX:
                    with open(local, "rb") as file:
                        found = as_image(file.read(), self.config.get("rsvg", "rsvg-convert"))
            elif valid_url(art):
                found = as_image(fetch(art, COVER_MAX)[1], self.config.get("rsvg", "rsvg-convert"))
        except (OSError, ValueError, urllib.error.URLError, http.client.HTTPException):
            found = None
        with self.lock:
            self.cover_cache = (art, found)
        return found

    def tasks(self, board=None):
        """The desktop's kanban (Tasks.qml); with a phone's [board], merged first."""
        text = self.ipc("tasks", "state") if board is None else self.ipc("tasks", "sync", json.dumps(board))
        try:
            found = json.loads(text or "")
        except ValueError:
            return None
        return found if isinstance(found, dict) else None

    def volume(self, action=None):
        """The output's volume, after turning it with the keys' own script
        (it shows the OSD here): { level, muted }."""
        if action is not None and self.ipc("volume", action) is None:
            return None
        text = self.run([self.config["wpctl"], "get-volume", "@DEFAULT_AUDIO_SINK@"])
        found = re.search(r"Volume:\s*([0-9.]+)", text or "")
        if not found:
            return None
        return {"level": round(float(found.group(1)) * 100), "muted": "[MUTED]" in text}

    def lock_screen(self):
        return self.ipc("session", "lock") is not None

    def toggles(self):
        """Caffeine and night light as the quick settings have them:
        { caffeine, night }, empty when the shell can't say."""
        try:
            found = json.loads(self.ipc("toggles", "state") or "")
        except ValueError:
            return {}
        if not isinstance(found, dict):
            return {}
        return {key: found[key] for key in ("caffeine", "night") if isinstance(found.get(key), bool)}

    def switch(self, kind, action):
        """Caffeine or night light on, off or over; whether it is on now, None
        when the shell didn't answer."""
        if self.ipc(kind, action) is None:
            return None
        return self.toggles().get(kind)

    def notify(self, title, body):
        command = [self.config["notify"], "-a", "DOT-MATRIX", title]
        if body:
            command.append(body)
        self.run(command)


# --- Icons ------------------------------------------------------------------------

def data_dirs():
    dirs = [home_dir("XDG_DATA_HOME", ".local/share")]
    dirs += [d for d in os.environ.get("XDG_DATA_DIRS", "").split(":") if d]
    user = os.environ.get("USER", "")
    dirs += [os.path.expanduser("~/.nix-profile/share"), f"/etc/profiles/per-user/{user}/share",
             "/run/current-system/sw/share", "/usr/local/share", "/usr/share"]
    seen, ordered = set(), []
    for d in dirs:
        if d not in seen:
            seen.add(d)
            ordered.append(d)
    return ordered


@functools.lru_cache(maxsize=64)
def theme_index(root):
    """index.theme's Inherits and its directories: the scalable ones first,
    then the sizes closest to the phone's 128 px, bigger before smaller."""
    inherits, directories, sizes, kinds = [], [], {}, {}
    section = None
    try:
        with open(os.path.join(root, "index.theme"), encoding="utf-8", errors="replace") as file:
            for raw in file:
                line = raw.strip()
                if line.startswith("[") and line.endswith("]"):
                    section = line[1:-1]
                elif "=" in line:
                    key, value = (part.strip() for part in line.split("=", 1))
                    if section == "Icon Theme" and key == "Inherits":
                        inherits = [name.strip() for name in value.split(",") if name.strip()]
                    elif section == "Icon Theme" and key in ("Directories", "ScaledDirectories"):
                        directories += [name.strip() for name in value.split(",") if name.strip()]
                    elif section and key == "Size" and value.isdigit():
                        sizes[section] = int(value)
                    elif section and key == "Type":
                        kinds[section] = value
    except OSError:
        return (), ()
    unique = list(dict.fromkeys(directories))
    def order(directory):
        size = sizes.get(directory, 0)
        scalable = kinds.get(directory) == "Scalable" or "scalable" in directory
        return (0 if scalable else 1, size < ICON_SIZE, abs(size - ICON_SIZE))
    unique.sort(key=order)
    return tuple(inherits), tuple(unique)


def icon_file(name, theme, dirs=None):
    """The file of an icon by name, as the icon theme spec looks for it:
    the theme, what it inherits, hicolor, then pixmaps."""
    if not name:
        return None
    if os.path.isabs(name):
        return name if os.path.isfile(name) and name.endswith((".png", ".svg")) else None
    if "/" in name or name.startswith("."):
        return None
    dirs = dirs or data_dirs()
    queue, visited = [theme, "hicolor"] if theme else ["hicolor"], set()
    while queue:
        current = queue.pop(0)
        if not current or current in visited:
            continue
        visited.add(current)
        for base in dirs:
            root = os.path.join(base, "icons", current)
            if not os.path.isdir(root):
                continue
            inherits, directories = theme_index(root)
            for directory in directories:
                for extension in (".svg", ".png"):
                    path = os.path.join(root, directory, name + extension)
                    if os.path.isfile(path):
                        return path
            queue[0:0] = [parent for parent in inherits if parent not in visited and parent != "hicolor"]
    for base in dirs:
        for extension in (".png", ".svg"):
            path = os.path.join(base, "pixmaps", name + extension)
            if os.path.isfile(path):
                return path
    return None


def icon_png(path, rsvg, cache):
    """A PNG for the phone: the theme's own, or the SVG drawn at 128 px
    (kept in the cache by file and time)."""
    if path.endswith(".png"):
        return path
    stamp = hashlib.sha256(f"{os.path.realpath(path)}:{os.path.getmtime(path)}:{ICON_SIZE}".encode()).hexdigest()[:24]
    out = os.path.join(cache, stamp + ".png")
    if os.path.isfile(out):
        return out
    os.makedirs(cache, mode=0o700, exist_ok=True)
    temporary = out + ".new"
    try:
        subprocess.run([rsvg, "-w", str(ICON_SIZE), "-h", str(ICON_SIZE), "-a", "-f", "png", "-o", temporary, path],
                       check=True, capture_output=True, timeout=10)
        os.replace(temporary, out)
    except (OSError, subprocess.SubprocessError):
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        return None
    return out


# --- The kanban ------------------------------------------------------------------

def clean_board(body):
    """A phone's kanban, its shape checked: { cards: [...], removed: { id: when } };
    a card's rank, when it has one, a finite number."""
    cards = body.get("cards", [])
    removed = body.get("removed", {})
    if not isinstance(cards, list) or not isinstance(removed, dict) or len(cards) > CARDS_MAX or len(removed) > 10 * CARDS_MAX:
        return None
    clean = []
    for card in cards:
        if not isinstance(card, dict):
            return None
        ident, text, column = card.get("id"), card.get("text"), card.get("column")
        stamps = (card.get("textStamp"), card.get("placeStamp"))
        if not isinstance(ident, str) or not 0 < len(ident) <= 64 or not isinstance(text, str) or not text.strip():
            return None
        if not isinstance(column, int) or isinstance(column, bool) or not 0 <= column <= 2:
            return None
        if not all(isinstance(t, int) and not isinstance(t, bool) and t >= 0 for t in stamps):
            return None
        kept = {"id": ident, "text": " ".join(text.split())[:160], "column": column,
                "textStamp": stamps[0], "placeStamp": stamps[1]}
        rank = card.get("rank")
        if rank is not None:
            if not isinstance(rank, (int, float)) or isinstance(rank, bool) or not math.isfinite(rank):
                return None
            kept["rank"] = rank
        clean.append(kept)
    gone = {}
    for ident, when in removed.items():
        if not isinstance(ident, str) or not 0 < len(ident) <= 64 or not isinstance(when, int) or isinstance(when, bool) or when < 0:
            return None
        gone[ident] = when
    return {"cards": clean, "removed": gone}


# --- The trackpad -----------------------------------------------------------------
#
# GET /v1/trackpad turns the connection into a stream of the phone's fingers
# (docs/deck.md, The trackpad), and here they touch a touchpad of their own,
# made with /dev/uinput as wide and tall as the phone's screen. libinput takes
# it as any touchpad, with the laptop's settings: a tap clicks, two fingers
# right-click and scroll, three middle-click, a pinch zooms, and Hyprland's
# gestures run. It goes when the stream does.

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SYN_REPORT = 0
BTN_TOUCH = 330
# BTN_TOOL_FINGER, DOUBLETAP, TRIPLETAP, QUADTAP and QUINTTAP: one to five fingers.
FINGER_TOOLS = (325, 333, 334, 335, 328)
ABS_X, ABS_Y = 0, 1
ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 47, 53, 54, 57
INPUT_PROP_POINTER = 0
BUS_VIRTUAL = 6
TRACKPAD_NAME = "DOT-MATRIX deck trackpad"
TRACKPAD_PROTOCOL = "omadeck-trackpad"
TRACKPAD_SLOTS = 5
TRACKPAD_IDLE = 4               # seconds without a frame or a keepalive: the phone is gone
TRACKPAD_SETTLE = 0.25          # seconds for libinput to open a new touchpad
TRACKPAD_DELAY = 0.025          # seconds the fingers wait here, to keep the phone's pace
TOUCH_FRAME, KEEPALIVE = 1, 2
MAX_FINGERS = 10


def _ioc(number, size=0):
    """_IOW('U', number, size), or _IO('U', number) without one (asm-generic)."""
    return ((1 << 30) if size else 0) | (size << 16) | (ord("U") << 8) | number


UI_DEV_CREATE = _ioc(1)
UI_DEV_DESTROY = _ioc(2)
UI_DEV_SETUP = _ioc(3, 92)      # struct uinput_setup
UI_ABS_SETUP = _ioc(4, 28)      # struct uinput_abs_setup
UI_SET_EVBIT = _ioc(100, 4)
UI_SET_KEYBIT = _ioc(101, 4)
UI_SET_ABSBIT = _ioc(103, 4)
UI_SET_PROPBIT = _ioc(110, 4)


class Uinput:
    """A touchpad made with /dev/uinput: [width] by [height] units, [resolution]
    of them a millimetre, five fingers and nothing to press, so libinput taps
    to click on its own. It goes with close(), or with this process."""

    def __init__(self, path, width, height, resolution):
        self.width, self.height = width, height
        self.fd = os.open(path, os.O_WRONLY | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            for kind in (EV_KEY, EV_ABS):
                fcntl.ioctl(self.fd, UI_SET_EVBIT, kind)
            for key in (BTN_TOUCH, *FINGER_TOOLS):
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, key)
            fcntl.ioctl(self.fd, UI_SET_PROPBIT, INPUT_PROP_POINTER)
            for code, top in ((ABS_X, width), (ABS_Y, height), (ABS_MT_POSITION_X, width), (ABS_MT_POSITION_Y, height)):
                self.axis(code, top, resolution)
            self.axis(ABS_MT_SLOT, TRACKPAD_SLOTS - 1, 0)
            self.axis(ABS_MT_TRACKING_ID, 65535, 0)
            setup = struct.pack("=4H80sI", BUS_VIRTUAL, 0x6E69, 0x6C64, 1, TRACKPAD_NAME.encode(), 0)
            fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
        except OSError:
            os.close(self.fd)
            raise

    def axis(self, code, top, resolution):
        fcntl.ioctl(self.fd, UI_SET_ABSBIT, code)
        # struct uinput_abs_setup: the code, then input_absinfo's value,
        # minimum, maximum, fuzz, flat and resolution.
        fcntl.ioctl(self.fd, UI_ABS_SETUP, struct.pack("=HH6i", code, 0, 0, 0, top, 0, 0, resolution))

    def write(self, events):
        os.write(self.fd, b"".join(struct.pack("@llHHi", 0, 0, kind, code, value) for kind, code, value in events))

    def close(self):
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        os.close(self.fd)


class Fingers:
    """The phone's fingers as the kernel's multitouch protocol (type B): a slot
    each, the first five, with a tracking id while it touches; and, for readers
    without slots, how many touch and where the first of them is."""

    def __init__(self):
        self.slots = [None] * TRACKPAD_SLOTS    # [finger, tracking id, x, y]
        self.slot = -1
        self.next_id = 0
        self.count = 0
        self.primary = None

    def frame(self, contacts):
        """The events of a frame: [(finger, x, y)], every finger touching now."""
        events = []
        touching = {finger for finger, _, _ in contacts}
        for index, held in enumerate(self.slots):
            if held is not None and held[0] not in touching:
                self.select(events, index)
                events.append((EV_ABS, ABS_MT_TRACKING_ID, -1))
                self.slots[index] = None
        for finger, x, y in contacts:
            index = next((i for i, held in enumerate(self.slots) if held is not None and held[0] == finger), None)
            if index is None:
                index = next((i for i, held in enumerate(self.slots) if held is None), None)
                if index is None:
                    continue        # a sixth finger: a touchpad has five
                self.select(events, index)
                events += [(EV_ABS, ABS_MT_TRACKING_ID, self.next_id), (EV_ABS, ABS_MT_POSITION_X, x), (EV_ABS, ABS_MT_POSITION_Y, y)]
                self.slots[index] = [finger, self.next_id, x, y]
                self.next_id = (self.next_id + 1) % 65536
                continue
            held = self.slots[index]
            if held[2] != x or held[3] != y:
                self.select(events, index)
                if held[2] != x:
                    events.append((EV_ABS, ABS_MT_POSITION_X, x))
                if held[3] != y:
                    events.append((EV_ABS, ABS_MT_POSITION_Y, y))
                held[2], held[3] = x, y
        self.legacy(events)
        events.append((EV_SYN, SYN_REPORT, 0))
        return events

    def release(self):
        """Every finger up, as events; none when none was down."""
        return self.frame([]) if self.count else []

    def select(self, events, index):
        if self.slot != index:
            events.append((EV_ABS, ABS_MT_SLOT, index))
            self.slot = index

    def legacy(self, events):
        held = [slot for slot in self.slots if slot is not None]
        count = len(held)
        if count != self.count:
            if self.count:
                events.append((EV_KEY, FINGER_TOOLS[self.count - 1], 0))
            if count:
                events.append((EV_KEY, FINGER_TOOLS[count - 1], 1))
            if bool(count) != bool(self.count):
                events.append((EV_KEY, BTN_TOUCH, 1 if count else 0))
            self.count = count
        if not held:
            self.primary = None
            return
        # The finger down longest: its tracking id the furthest behind the next one.
        first = max(held, key=lambda slot: (self.next_id - slot[1]) % 65536)
        if (first[2], first[3]) != self.primary:
            if self.primary is None or self.primary[0] != first[2]:
                events.append((EV_ABS, ABS_X, first[2]))
            if self.primary is None or self.primary[1] != first[3]:
                events.append((EV_ABS, ABS_Y, first[3]))
            self.primary = (first[2], first[3])


class Pace:
    """When each frame of fingers reaches the touchpad: as far apart as the
    phone saw them, a little later than they left it. libinput reads speed from
    those times, and takes frames the network bunched together for jumps. So a
    frame waits until its time on the phone, plus the quickest any frame came
    lately, plus [delay]; one later still goes as soon as it comes, and those
    after it keep their spacing, at most a tenth shorter, until they are back
    on time."""

    WINDOW = 2.0
    SQUEEZE = 0.9

    def __init__(self, delay=TRACKPAD_DELAY):
        self.delay = delay
        self.quickest = collections.deque()     # (arrived, offset), the offsets rising
        self.last = None                        # (phone, due) of the last frame
        self.raw = None
        self.wraps = 0

    def seconds(self, millis):
        """The phone's milliseconds (32 bits, they wrap) as seconds that go on."""
        if self.raw is not None and millis < self.raw and self.raw - millis > 1 << 31:
            self.wraps += 1
        self.raw = millis
        return (self.wraps * (1 << 32) + millis) / 1000

    def heard(self, phone, arrived):
        """How long the way takes, from a frame or a keepalive: the quickest lately."""
        offset = arrived - phone
        while self.quickest and self.quickest[-1][1] >= offset:
            self.quickest.pop()
        self.quickest.append((arrived, offset))
        while self.quickest[0][0] < arrived - self.WINDOW:
            self.quickest.popleft()

    def due(self, phone, arrived):
        """When a frame the phone made at [phone] and that came at [arrived] goes."""
        self.heard(phone, arrived)
        due = phone + self.quickest[0][1] + self.delay
        if self.last is not None and 0 <= phone - self.last[0] < 0.1:
            due = max(due, self.last[1] + self.SQUEEZE * (phone - self.last[0]))
        due = max(due, arrived)
        self.last = (phone, due)
        return due


def stream(reader, writer, pad, clock=time.monotonic):
    """A phone's frames from [reader] to [pad] at their pace (Pace), its
    keepalives answered on [writer], until it stops or goes quiet; then every
    finger up."""
    fingers = Fingers()
    pace = Pace()
    queue = collections.deque()
    ready = threading.Condition()
    finished = []

    def emit():
        try:
            while True:
                with ready:
                    while not queue and not finished:
                        ready.wait()
                    if not queue:
                        break
                    due, contacts = queue.popleft()
                wait = due - clock()
                if wait > 0:
                    time.sleep(wait)
                pad.write(fingers.frame(contacts))
        except OSError:
            pass
        finally:
            try:
                events = fingers.release()
                if events:
                    pad.write(events)
            except OSError:
                pass

    worker = threading.Thread(target=emit, daemon=True)
    worker.start()
    try:
        while True:
            head = reader.read(5)
            if len(head) < 5:
                break
            arrived = clock()
            kind, millis = struct.unpack("!BI", head)
            phone = pace.seconds(millis)
            if kind == KEEPALIVE:
                pace.heard(phone, arrived)
                writer.write(bytes([KEEPALIVE]))
                continue
            count = reader.read(1) if kind == TOUCH_FRAME else b""
            if len(count) < 1 or count[0] > MAX_FINGERS:
                break
            body = reader.read(5 * count[0])
            if len(body) < 5 * count[0]:
                break
            contacts = []
            for index in range(count[0]):
                finger, x, y = struct.unpack_from("!BHH", body, 5 * index)
                contacts.append((finger, min(x, pad.width), min(y, pad.height)))
            due = pace.due(phone, arrived)
            with ready:
                queue.append((due, contacts))
                ready.notify()
    except (OSError, ValueError):
        pass
    finally:
        with ready:
            finished.append(True)
            ready.notify()
        worker.join(timeout=2)


# --- The webs' icons ------------------------------------------------------------
#
# The phone keeps its own webs, and asks here for their icons: so it talks to
# no one but this computer. A site's icon is looked for on its home page (an
# SVG, the apple-touch-icon, the biggest one it names) and then at
# /favicon.ico; only at public addresses, redirects included, and kept a
# week by site, the misses too.

FAVICON_DAYS = 7
PAGE_MAX = 512 * 1024
FAVICON_MAX = 256 * 1024
FETCH_TIMEOUT = 5
PUBLIC_ONLY = True  # the tests turn it off, to serve their sites from 127.0.0.1
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def public(host):
    """Whether every address of [host] is on the internet: never this
    computer, its network, or anything reserved."""
    if not PUBLIC_ONLY:
        return True
    try:
        found = socket.getaddrinfo(host, None)
    except (OSError, UnicodeError):
        return False
    return bool(found) and all(ipaddress.ip_address(item[4][0].split("%")[0]).is_global for item in found)


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    """A few redirects, each to a public web page."""

    max_redirections = 3

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not valid_url(newurl) or not public(urllib.parse.urlsplit(newurl).hostname):
            raise urllib.error.URLError("redirected somewhere not public")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, limit, whole=True):
    """[url]'s body and where it ended up: [limit] bytes at most, or (not
    [whole]) its first [limit] bytes, enough for a page's <head>."""
    if not valid_url(url) or not public(urllib.parse.urlsplit(url).hostname):
        raise ValueError("not a public web page")
    opener = urllib.request.build_opener(PublicRedirects())
    request = urllib.request.Request(url, headers={"User-Agent": "omadeck (icons)", "Accept": "*/*"})
    with opener.open(request, timeout=FETCH_TIMEOUT) as response:
        data = response.read(limit + 1)
        if len(data) > limit:
            if whole:
                raise ValueError("too big")
            data = data[:limit]
        return response.geturl(), data


class IconLinks(html.parser.HTMLParser):
    """The icons a page names before its <body>, with how good each is."""

    def __init__(self, base):
        super().__init__()
        self.base = base
        self.found = []
        self.done = False

    def handle_starttag(self, tag, attrs):
        if self.done or tag == "body":
            self.done = True
            return
        if tag != "link":
            return
        attrs = {key: value or "" for key, value in attrs}
        rel = set(attrs.get("rel", "").lower().split())
        href = attrs.get("href", "").strip()
        touch = bool(rel & {"apple-touch-icon", "apple-touch-icon-precomposed"})
        if not href or not (touch or "icon" in rel):
            return
        sizes = [int(size) for size in re.findall(r"(\d+)x\d+", attrs.get("sizes", "").lower())]
        if "svg" in attrs.get("type", "").lower() or href.lower().split("?")[0].endswith(".svg"):
            score = 1024
        else:
            score = max(sizes or [180 if touch else 32])
        self.found.append((score, urllib.parse.urljoin(self.base, href)))

    def handle_endtag(self, tag):
        if tag == "head":
            self.done = True


def ico_png(data):
    """The biggest PNG inside an .ico (Windows Vista's and later), or None."""
    try:
        best = None
        for index in range(min(struct.unpack_from("<H", data, 4)[0], 64)):
            width, height, _, _, _, _, size, offset = struct.unpack_from("<BBBBHHII", data, 6 + 16 * index)
            chunk = data[offset:offset + size]
            if chunk.startswith(PNG_MAGIC) and (best is None or (width or 256) * (height or 256) > best[0]):
                best = ((width or 256) * (height or 256), chunk)
        return (best[1], "image/png") if best else None
    except struct.error:
        return None


def as_image(data, rsvg):
    """What the phone can draw, as (bytes, type): PNG, JPEG, GIF and WebP as
    they come, an SVG drawn at 128 px, the biggest PNG inside an .ico. None
    for anything else (an .ico of bitmaps): the phone draws its letter."""
    head = data[:16]
    if head.startswith(PNG_MAGIC):
        return data, "image/png"
    if head.startswith(b"\xff\xd8\xff"):
        return data, "image/jpeg"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return data, "image/gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return data, "image/webp"
    if head[:4] == b"\x00\x00\x01\x00":
        return ico_png(data)
    if b"<svg" in data[:2048].lower():
        try:
            drawn = subprocess.run([rsvg, "-w", str(ICON_SIZE), "-h", str(ICON_SIZE), "-a", "-f", "png"],
                                   input=data, check=True, capture_output=True, timeout=10).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        return (drawn, "image/png") if drawn.startswith(PNG_MAGIC) else None
    return None


def favicon(url, rsvg, cache):
    """The icon of [url]'s site as (bytes, type), or None."""
    parts = urllib.parse.urlsplit(url)
    origin = f"{parts.scheme}://{parts.netloc}".lower()
    path = os.path.join(cache, hashlib.sha256(origin.encode()).hexdigest()[:24])
    try:
        if time.time() - os.path.getmtime(path) < FAVICON_DAYS * 86400:
            with open(path, "rb") as file:
                kept = file.read()
            return as_image(kept, rsvg) if kept else None
    except OSError:
        pass
    failed = (OSError, ValueError, urllib.error.URLError, http.client.HTTPException)
    candidates = []
    try:
        where, page = fetch(origin + "/", PAGE_MAX, whole=False)
        links = IconLinks(where)
        links.feed(page.decode("utf-8", "replace"))
        candidates = [href for _, href in sorted(links.found, key=lambda item: -item[0])]
    except failed:
        pass
    found = None
    for href in candidates[:4] + [origin + "/favicon.ico"]:
        try:
            found = as_image(fetch(href, FAVICON_MAX)[1], rsvg)
        except failed:
            continue
        if found:
            break
    os.makedirs(cache, mode=0o700, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=cache, prefix=".icon-")
    with os.fdopen(fd, "wb") as file:
        file.write(found[0] if found else b"")
    os.replace(temporary, path)
    return found


# --- Where it is -----------------------------------------------------------------

def addresses(ip):
    """This computer's IPv4 addresses on its networks, the one with the
    default route first: where the phone looks for it. Containers' and
    virtual machines' bridges are left out (VIRTUAL)."""
    found = []
    if ip:
        try:
            route = json.loads(subprocess.run([ip, "-j", "-4", "route", "get", "192.0.2.1"],
                                              capture_output=True, text=True, timeout=5).stdout or "[]")
            if route and route[0].get("prefsrc"):
                found.append(route[0]["prefsrc"])
        except (OSError, ValueError, subprocess.SubprocessError, AttributeError, IndexError):
            pass
        try:
            links = json.loads(subprocess.run([ip, "-j", "-4", "addr", "show", "up", "scope", "global"],
                                              capture_output=True, text=True, timeout=5).stdout or "[]")
            for link in links:
                if VIRTUAL.match(str(link.get("ifname", ""))):
                    continue
                for info in link.get("addr_info", []):
                    if info.get("family") == "inet" and info.get("local"):
                        found.append(info["local"])
        except (OSError, ValueError, subprocess.SubprocessError, AttributeError):
            pass
    if not found:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                probe.connect(("192.0.2.1", 9))
                found.append(probe.getsockname()[0])
        except OSError:
            pass
    return [address for address in dict.fromkeys(found) if not address.startswith("127.")]


def pair_uri(name, port, hosts, code, pin):
    query = urllib.parse.urlencode({"v": API, "name": name, "hosts": ",".join(hosts), "port": port,
                                    "code": code, "pin": pin}, safe=",")
    return "omadeck://pair?" + query


# --- Оформление компьютера: «следовать компьютеру» на телефоне ----------------------

def omarchy_paths():
    config = home_dir("XDG_CONFIG_HOME", ".config")
    state = home_dir("XDG_STATE_HOME", ".local/state")
    return {
        "current": os.path.join(state, "omarchy", "current"),
        "omarchy": os.path.join(config, "omarchy"),
    }


def _read_json(path):
    try:
        with open(path) as file:
            found = json.load(file)
        return found if isinstance(found, dict) else {}
    except (OSError, ValueError):
        return {}


HEX = re.compile(r"#?([0-9a-fA-F]{6})")


def _colors_toml(path):
    """Плоский colors.toml темы Omarchy: ключ = "#rrggbb" (tomllib не нужен)."""
    found, mode = {}, None
    try:
        with open(path, encoding="utf-8", errors="replace") as file:
            for line in file:
                key, _, value = line.partition("=")
                key, value = key.strip(), value.strip().strip('"\'')
                if key == "mode" and value in ("dark", "light"):
                    mode = value
                elif re.fullmatch(r"[a-z_0-9]+", key):
                    match = HEX.fullmatch(value)
                    if match:
                        found[key] = "#" + match.group(1).lower()
    except OSError:
        pass
    return found, mode


LOOK_FILES = ("style.json", "glyph.json", "sounds.json", "dotmatrix.json", "widgets.json", "fonts.json", "bar-skin.json", "lock-design.json")


def look_stamp(paths=None):
    """Меняется, когда меняется что-то из оформления: телефон перечитывает /v1/look."""
    paths = paths or omarchy_paths()
    parts = []
    for name in ("theme.name", "theme.generation", "background"):
        target = os.path.join(paths["current"], name)
        try:
            parts.append(f"{name}:{os.stat(target).st_mtime_ns}:{os.path.realpath(target)}")
        except OSError:
            parts.append(name)
    for name in LOOK_FILES:
        try:
            parts.append(f"{name}:{os.stat(os.path.join(paths['omarchy'], name)).st_mtime_ns}")
        except OSError:
            parts.append(name)
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]


def current_look(paths=None):
    """Тема, стиль Customizer и его настройки, как их читает Look на телефоне."""
    paths = paths or omarchy_paths()
    try:
        with open(os.path.join(paths["current"], "theme.name")) as file:
            theme_id = file.read().strip()
    except OSError:
        theme_id = ""
    colors, mode = _colors_toml(os.path.join(paths["current"], "theme", "colors.toml"))
    if mode is None and colors.get("background"):
        r, g, b = (int(colors["background"][i:i + 2], 16) for i in (1, 3, 5))
        mode = "light" if (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.5 else "dark"
    style = _read_json(os.path.join(paths["omarchy"], "style.json"))
    widgets = _read_json(os.path.join(paths["omarchy"], "widgets.json"))
    fonts = _read_json(os.path.join(paths["omarchy"], "fonts.json"))
    look = {key: style[key] for key in ("material", "elevation", "cornerScale", "motion", "typeface") if key in style}
    background = os.path.join(paths["current"], "background")
    try:
        real = os.path.realpath(background)
        wall = hashlib.sha256(f"{real}:{os.stat(real).st_mtime_ns}".encode()).hexdigest()[:16]
    except OSError:
        wall = ""
    return {
        "stamp": look_stamp(paths),
        # Меняется только с обоями: телефон качает /v1/wallpaper, когда он другой.
        "wallpaper": wall,
        "theme": {"id": theme_id, "name": theme_id.replace("-", " ").title(), "mode": mode or "dark", "colors": colors} if colors else None,
        "style": {"id": str(style.get("id", ""))},
        "look": look,
        "glyph": _read_json(os.path.join(paths["omarchy"], "glyph.json")),
        "sounds": _read_json(os.path.join(paths["omarchy"], "sounds.json")),
        "dotmatrix": _read_json(os.path.join(paths["omarchy"], "dotmatrix.json")),
        "widgets": {key: widgets[key] for key in ("widgetStyle", "clockStyle", "clock", "clock24", "clockSeconds", "media", "todo", "todoMax", "tint") if key in widgets},
        "fonts": str(fonts.get("profile", "")),
        # Скин бара и дизайн блокировки — для бара и блокировки на телефоне.
        "bar": _read_json(os.path.join(paths["omarchy"], "bar-skin.json")),
        "lock": _read_json(os.path.join(paths["omarchy"], "lock-design.json")),
    }


def wallpaper(width, height, magick, paths=None):
    """Текущие обои Omarchy, обрезанные под экран телефона (cover), JPEG."""
    paths = paths or omarchy_paths()
    source = os.path.realpath(os.path.join(paths["current"], "background"))
    if not os.path.isfile(source):
        return None
    return fit_picture(source, width, height, magick)


def weather_location():
    """Где компьютер смотрит погоду: ~/.local/state/omarchy/settings/weather.json
    (omarchy-weather-location), а без него — geo.omarchy.org, как виджет Omarchy."""
    state = home_dir("XDG_STATE_HOME", ".local/state")
    found = _read_json(os.path.join(state, "omarchy", "settings", "weather.json"))
    source = "omarchy"
    if not found.get("latitude") and not found.get("longitude"):
        try:
            with urllib.request.urlopen(urllib.request.Request("https://geo.omarchy.org/", headers={"User-Agent": "omadeck"}), timeout=4) as answer:
                found = json.loads(answer.read(4096))
            source = "geo"
        except (OSError, ValueError, urllib.error.URLError, http.client.HTTPException):
            return None
    try:
        latitude, longitude = float(found["latitude"]), float(found["longitude"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        return None
    country = str(found.get("country") or "")
    return {"name": str(found.get("name") or "")[:80], "latitude": latitude, "longitude": longitude, "source": source,
            "country": country if re.fullmatch(r"[A-Za-z]{2}", country) else ""}


def _cpu_times():
    try:
        with open("/proc/stat") as file:
            parts = [int(x) for x in file.readline().split()[1:]]
        idle = parts[3] + (parts[4] if len(parts) > 4 else 0)
        return sum(parts), idle
    except (OSError, ValueError, IndexError):
        return None


def system_stats(sample=0.25, root="/"):
    """Процессор (за [sample] с), память, батарея и температура компьютера, из /proc и /sys."""
    found = {"name": socket.gethostname()}
    first = _cpu_times()
    time.sleep(sample)
    second = _cpu_times()
    if first and second and second[0] > first[0]:
        busy = 1 - (second[1] - first[1]) / (second[0] - first[0])
        found["cpu"] = round(max(0.0, min(1.0, busy)) * 100)
    try:
        info = {}
        with open("/proc/meminfo") as file:
            for line in file:
                key, _, value = line.partition(":")
                info[key] = int(value.split()[0])
        found["memory"] = round((1 - info["MemAvailable"] / info["MemTotal"]) * 100)
    except (OSError, ValueError, KeyError, ZeroDivisionError):
        pass
    supply = os.path.join(root, "sys/class/power_supply")
    try:
        for entry in sorted(os.listdir(supply)):
            if not entry.startswith("BAT"):
                continue
            with open(os.path.join(supply, entry, "capacity")) as file:
                found["battery"] = int(file.read().strip())
            try:
                with open(os.path.join(supply, entry, "status")) as file:
                    found["charging"] = file.read().strip() in ("Charging", "Full")
            except OSError:
                pass
            break
    except (OSError, ValueError):
        pass
    hottest = None
    thermal = os.path.join(root, "sys/class/thermal")
    try:
        for entry in os.listdir(thermal):
            if not entry.startswith("thermal_zone"):
                continue
            try:
                with open(os.path.join(thermal, entry, "type")) as file:
                    kind = file.read().strip()
                with open(os.path.join(thermal, entry, "temp")) as file:
                    value = int(file.read().strip()) / 1000
            except (OSError, ValueError):
                continue
            if kind in ("x86_pkg_temp", "TCPU", "cpu-thermal", "k10temp", "acpitz") and 0 < value < 130:
                hottest = value if hottest is None else max(hottest, value)
    except OSError:
        pass
    if hottest is not None:
        found["temperature"] = round(hottest)
    return found


THEME_ID = re.compile(r"[A-Za-z0-9._-]{1,80}")
PICTURE = (".png", ".jpg", ".jpeg", ".webp", ".gif")


# Звуки темы Omarchy (freedesktop-имена, как их ищет omarchy-sound) → события пакета телефона.
THEME_SOUNDS = {
    "notify": ("message-new-instant", "message", "bell"),
    "critical": ("battery-low", "dialog-warning", "suspend-error"),
    "volume": ("audio-volume-change",),
    "special": ("screen-capture", "camera-shutter", "complete"),
    "lock": ("service-login", "desktop-login"),
    "close": ("service-logout", "device-removed", "power-unplug"),
    "switch": ("device-added",),
    "charge": ("power-plug",),
    "open": ("window-attention", "complete"),
}
SOUND_EXTS = ("oga", "ogg", "wav", "flac")


def theme_sound_dirs(paths=None):
    """Где omarchy-sound ищет звуки: тема, затем стандартная тема freedesktop."""
    paths = paths or omarchy_paths()
    return [
        os.path.join(paths["current"], "theme", "apps", "sounds"),
        os.path.join(paths["omarchy"], "app-defaults", "sounds"),
        "/usr/share/sounds/freedesktop/stereo",
    ]


def theme_sounds(paths=None, dirs=None):
    """{событие пакета: путь} — первый найденный файл по freedesktop-именам из THEME_SOUNDS."""
    dirs = dirs if dirs is not None else theme_sound_dirs(paths)
    found = {}
    for event, names in THEME_SOUNDS.items():
        for name in names:
            path = next((os.path.join(d, f"{name}.{ext}") for d in dirs for ext in SOUND_EXTS
                         if os.path.isfile(os.path.join(d, f"{name}.{ext}"))), None)
            if path:
                found[event] = path
                break
    return found


def sounds_stamp(found):
    parts = sorted(f"{k}:{os.path.realpath(v)}:{os.stat(v).st_mtime_ns}" for k, v in found.items())
    return hashlib.sha256("\n".join(parts).encode()).hexdigest()[:16]


def theme_melodies(found, cache=None):
    """Звонок, будильник и уведомление из звуков темы (тот же сборщик, что у пакетов); {вид: путь}."""
    if not found:
        return {}
    here = os.path.dirname(os.path.realpath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import melody  # noqa: E402  (общий с экспортом пакетов)
    folder = os.path.join(cache or cache_dir(), "sounds", sounds_stamp(found))
    out = {}
    for kind in melody.KINDS:
        target = os.path.join(folder, f"{kind}.ogg")
        if not os.path.isfile(target):
            from pathlib import Path
            melody.build({k: Path(v) for k, v in found.items()}, kind, target)
        if os.path.isfile(target):
            out[kind] = target
    return out


def current_theme(paths=None):
    paths = paths or omarchy_paths()
    try:
        with open(os.path.join(paths["current"], "theme.name")) as file:
            return file.read().strip()
    except OSError:
        return ""


def background_dirs(theme):
    """Где лежат обои темы, как их ищет переключатель обоев Omarchy: свои
    (~/.config/omarchy/backgrounds/ТЕМА), затем самой темы — своей и стоковой."""
    config = home_dir("XDG_CONFIG_HOME", ".config")
    return [os.path.join(config, "omarchy", "backgrounds", theme),
            os.path.join(config, "omarchy", "themes", theme, "backgrounds"),
            os.path.join("/usr/share/omarchy/themes", theme, "backgrounds")]


def backgrounds(theme):
    """Обои темы: [(имя, путь)], по имени; имя без каталогов, первое по порядку побеждает."""
    if not THEME_ID.fullmatch(theme or "") or theme.startswith("."):
        return []
    found = {}
    for folder in background_dirs(theme):
        try:
            names = sorted(os.listdir(folder))
        except OSError:
            continue
        for name in names:
            path = os.path.join(folder, name)
            if name.lower().endswith(PICTURE) and not name.startswith(".") and os.path.isfile(path):
                found.setdefault(name, path)
    return sorted(found.items())


def fit_picture(source, width, height, magick, cache=None):
    """[source], обрезанная под width × height (cover), JPEG; кэш по пути, mtime и размеру."""
    try:
        stamp = os.stat(source).st_mtime_ns
    except OSError:
        return None
    key = hashlib.sha256(f"{os.path.realpath(source)}:{stamp}:{width}x{height}".encode()).hexdigest()[:32]
    cached = os.path.join(cache, key + ".jpg") if cache else None
    if cached and os.path.isfile(cached):
        with open(cached, "rb") as file:
            return file.read()
    try:
        done = subprocess.run([magick, source + "[0]", "-auto-orient", "-resize", f"{width}x{height}^",
                               "-gravity", "center", "-extent", f"{width}x{height}", "-strip", "-quality", "88", "jpeg:-"],
                              stdin=subprocess.DEVNULL, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0 or not done.stdout:
        return None
    if cached:
        try:
            os.makedirs(cache, mode=0o700, exist_ok=True)
            write_private(cached + ".tmp", "")
            with open(cached + ".tmp", "wb") as file:
                file.write(done.stdout)
            os.replace(cached + ".tmp", cached)
        except OSError:
            pass
    return done.stdout


# --- The service ------------------------------------------------------------------

class Deck:
    """What the HTTPS side and the control socket share."""

    def __init__(self, config, folder, pin):
        self.config = config
        self.pin = pin
        self.name = config.get("name") or socket.gethostname()
        self.port = int(config.get("port", PORT))
        self.devices = Devices(os.path.join(folder, "devices.json"))
        self.pairing = Pairing()
        self.throttle = Throttle()
        self.session = Session(config)
        self.strings = config.get("strings", {})
        self.make_trackpad = lambda width, height, resolution: Uinput(config.get("uinput", "/dev/uinput"), width, height, resolution)
        self.streams = {}
        self.streams_lock = threading.Lock()

    def control(self, request):
        op = request.get("op") if isinstance(request, dict) else None
        if op == "pair":
            code, expires = self.pairing.open()
            hosts = addresses(self.config.get("ip"))
            return {"uri": pair_uri(self.name, self.port, hosts, code, self.pin), "code": code,
                    "expires": int(expires), "hosts": hosts, "port": self.port, "pin": self.pin}
        if op == "cancel":
            self.pairing.close()
            return {"ok": True}
        if op == "devices":
            # Whether the code still pairs, so Preferences knows when its QR
            # stops being any use (used, expired, or five wrong codes).
            return {"devices": self.devices.listing(), "pairing": self.pairing.open_now}
        if op == "forget":
            return {"ok": self.devices.forget(str(request.get("id", "")))}
        if op == "status":
            return {"port": self.port, "pin": self.pin, "devices": len(self.devices.listing()),
                    "pairing": self.pairing.open_now}
        return {"error": "unknown"}

    def claim(self, device, connection):
        """[connection] is [device]'s trackpad now: a stream it had before ends."""
        with self.streams_lock:
            before = self.streams.get(device)
            self.streams[device] = connection
        if before is not None and before is not connection:
            try:
                socket.socket.shutdown(before, socket.SHUT_RDWR)
            except OSError:
                pass

    def unclaim(self, device, connection):
        with self.streams_lock:
            if self.streams.get(device) is connection:
                del self.streams[device]

    def paired(self, name):
        title = self.strings.get("deckPaired", "%1 can now use this computer as a deck").replace("%1", name)
        self.session.notify(title, self.strings.get("deckPairedBody", ""))


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "omadeck/" + str(API)
    sys_version = ""

    # TLS here, in this connection's thread, with its timeout.
    def setup(self):
        self.request.do_handshake()
        super().setup()

    def log_message(self, format, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        # When this answer ends the connection (429, a body it won't read),
        # the phone hears it and doesn't send the next request down it.
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def png(self, path):
        with open(path, "rb") as file:
            self.image(file.read(), "image/png")

    def image(self, data, kind):
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "private, max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def read_body(self, limit=MAX_BODY):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if length < 0 or length > limit:
            self.close_connection = True
            return None
        data = self.rfile.read(length) if length else b""
        if not data:
            return {}
        try:
            body = json.loads(data)
        except ValueError:
            return None
        return body if isinstance(body, dict) else None

    def do_GET(self):
        self.route("GET", {})

    def do_POST(self):
        # A phone's kanban can be more than any other request.
        body = self.read_body(TASKS_MAX if urllib.parse.urlsplit(self.path).path == "/v1/tasks" else MAX_BODY)
        if body is None:
            self.reply(400, {"error": "body"})
            return
        self.route("POST", body)

    def route(self, method, body):
        deck = self.server.deck
        address = self.client_address[0]
        path = urllib.parse.urlsplit(self.path).path
        if deck.throttle.blocked(address):
            self.close_connection = True
            self.reply(429, {"error": "wait"})
            return
        if method == "GET" and path == "/v1/hello":
            self.reply(200, {"deck": API, "name": deck.name})
            return
        if method == "POST" and path == "/v1/pair":
            self.pair(deck, address, body)
            return
        auth = self.headers.get("Authorization", "")
        device = deck.devices.find(auth[7:].strip() if auth.startswith("Bearer ") else "")
        if device is None:
            deck.throttle.fail(address)
            self.reply(401, {"error": "unpaired"})
            return
        deck.devices.touch(device)
        self.paired_route(deck, device, method, path, body)

    def pair(self, deck, address, body):
        result = deck.pairing.take(body.get("code"))
        if result != "ok":
            deck.throttle.fail(address)
            self.reply({"closed": 409, "expired": 410, "wrong": 403}[result], {"error": result})
            return
        name = clean_name(body.get("device"), "Android")
        device, token = deck.devices.add(name)
        deck.paired(name)
        self.reply(200, {"token": token, "device": device, "name": deck.name})

    def trackpad(self, deck, device):
        """The phone's fingers on a touchpad here, for as long as it streams
        them (docs/deck.md, The trackpad)."""
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        try:
            width, height, resolution = (int(query.get(key, ["0"])[0]) for key in ("w", "h", "r"))
        except ValueError:
            width = height = resolution = 0
        sized = 5 <= resolution <= 100 and all(10 * resolution <= side <= min(500 * resolution, 65535) for side in (width, height))
        if not sized or self.headers.get("Upgrade", "").strip().lower() != TRACKPAD_PROTOCOL:
            self.reply(400, {"error": "invalid"})
            return
        try:
            pad = deck.make_trackpad(width, height, resolution)
        except OSError as error:
            self.reply(503, {"error": "access" if error.errno in (errno.EACCES, errno.EPERM) else "trackpad"})
            return
        deck.claim(device["id"], self.connection)
        try:
            time.sleep(TRACKPAD_SETTLE)
            self.send_response(101)
            self.send_header("Upgrade", TRACKPAD_PROTOCOL)
            self.send_header("Connection", "Upgrade")
            self.end_headers()
            self.close_connection = True
            self.connection.settimeout(TRACKPAD_IDLE)
            try:
                self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            stream(self.rfile, self.wfile, pad)
        finally:
            deck.unclaim(device["id"], self.connection)
            pad.close()

    def paired_route(self, deck, device, method, path, body):
        session = deck.session
        if method == "GET" and path == "/v1/apps":
            listed = session.apps()
            if listed is None:
                self.reply(503, {"error": "shell"})
                return
            self.reply(200, {"apps": [{"id": a["id"], "name": a["name"]} for a in listed]})
        elif method == "GET" and path.startswith("/v1/apps/") and path.endswith("/icon"):
            app = session.app(urllib.parse.unquote(path[len("/v1/apps/"):-len("/icon")]))
            found = icon_file(app["icon"], deck.config.get("iconTheme", "")) if app else None
            picture = icon_png(found, deck.config.get("rsvg", "rsvg-convert"), os.path.join(cache_dir(), "icons")) if found else None
            if picture is None:
                self.reply(404, {"error": "icon"})
            else:
                self.png(picture)
        elif method == "GET" and path == "/v1/favicon":
            url = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("url", [""])[0]
            if not valid_url(url):
                self.reply(400, {"error": "invalid"})
                return
            found = favicon(url, deck.config.get("rsvg", "rsvg-convert"), os.path.join(cache_dir(), "favicons"))
            if found is None:
                self.reply(404, {"error": "icon"})
            else:
                self.image(*found)
        elif method == "GET" and path == "/v1/cover":
            token = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("c", [""])[0]
            found = session.cover(token) if re.fullmatch(r"[0-9a-f]{16}", token) else None
            if found is None:
                self.reply(404, {"error": "cover"})
            else:
                self.image(*found)
        elif method == "GET" and path == "/v1/tasks":
            board = session.tasks()
            if board is None:
                self.reply(503, {"error": "shell"})
            else:
                self.reply(200, board)
        elif method == "POST" and path == "/v1/tasks":
            board = clean_board(body)
            if board is None:
                self.reply(400, {"error": "invalid"})
                return
            merged = session.tasks(board)
            if merged is None:
                self.reply(503, {"error": "shell"})
            else:
                self.reply(200, merged)
        elif method == "GET" and path == "/v1/state":
            self.reply(200, {"media": session.playing(), "volume": session.volume(), **session.toggles(), "look": look_stamp()})
        elif method == "GET" and path == "/v1/look":
            self.reply(200, current_look())
        elif method == "GET" and path == "/v1/stats":
            self.reply(200, system_stats())
        elif method == "GET" and path == "/v1/weather":
            location = weather_location()
            if location is None:
                self.reply(404, {"error": "location"})
            else:
                self.reply(200, location)
        elif method == "GET" and path == "/v1/sounds":
            found = theme_sounds()
            self.reply(200, {"stamp": sounds_stamp(found), "items": sorted(found) + sorted(theme_melodies(found))})
        elif method == "GET" and path == "/v1/sounds/file":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            name = query.get("name", [""])[0]
            found = theme_sounds()
            # Только имена из списка — никаких путей с телефона.
            source = found.get(name) if name in THEME_SOUNDS else theme_melodies(found).get(name) if name in ("ringtone", "alarm", "notification") else None
            if source is None:
                self.reply(404, {"error": "sound"})
            else:
                with open(source, "rb") as file:
                    self.image(file.read(), "audio/ogg")
        elif method == "GET" and path == "/v1/backgrounds":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            theme = query.get("theme", [""])[0] or current_theme()
            self.reply(200, {"theme": theme, "current": theme == current_theme(),
                             "items": [{"name": name} for name, _ in backgrounds(theme)]})
        elif method == "GET" and path == "/v1/backgrounds/image":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            theme = query.get("theme", [""])[0] or current_theme()
            name = query.get("name", [""])[0]
            try:
                width, height = (int(query.get(key, ["0"])[0]) for key in ("w", "h"))
            except ValueError:
                width = height = 0
            source = dict(backgrounds(theme)).get(name)
            if not (32 <= width <= 4096 and 32 <= height <= 4096):
                self.reply(400, {"error": "invalid"})
                return
            found = fit_picture(source, width, height, deck.config.get("magick", "magick"), os.path.join(cache_dir(), "backgrounds")) if source else None
            if found is None:
                self.reply(404, {"error": "wallpaper"})
            else:
                self.image(found, "image/jpeg")
        elif method == "GET" and path == "/v1/wallpaper":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            try:
                width, height = (int(query.get(key, ["0"])[0]) for key in ("w", "h"))
            except ValueError:
                width = height = 0
            if not (64 <= width <= 4096 and 64 <= height <= 4096):
                self.reply(400, {"error": "invalid"})
                return
            found = wallpaper(width, height, deck.config.get("magick", "magick"))
            if found is None:
                self.reply(404, {"error": "wallpaper"})
            else:
                self.image(found, "image/jpeg")
        elif method == "POST" and path == "/v1/open":
            if "app" in body:
                result = session.launch(body.get("app"))
            elif "url" in body:
                result = session.open_url(body.get("url"))
            else:
                result = "invalid"
            status = {"ok": 200, "unknown": 404, "invalid": 400, "shell": 503}[result]
            self.reply(status, {"ok": True} if result == "ok" else {"error": result})
        elif method == "POST" and path in ("/v1/media", "/v1/volume", "/v1/caffeine", "/v1/night"):
            kind = path[len("/v1/"):]
            action = body.get("action")
            if action not in ACTIONS[kind]:
                self.reply(400, {"error": "action"})
            elif kind in ("caffeine", "night"):
                on = session.switch(kind, action)
                if on is None:
                    self.reply(503, {"error": "shell"})
                else:
                    self.reply(200, {"on": on})
            elif kind == "media":
                if session.media(action):
                    self.reply(200, {"ok": True})
                else:
                    self.reply(503, {"error": "shell"})
            else:
                volume = session.volume(action)
                if volume is not None:
                    self.reply(200, volume)
                else:
                    self.reply(503, {"error": "volume"})
        elif method == "POST" and path == "/v1/lock":
            if session.lock_screen():
                self.reply(200, {"ok": True})
            else:
                self.reply(503, {"error": "lock"})
        elif method == "GET" and path == "/v1/trackpad":
            self.trackpad(deck, device)
        elif method == "POST" and path == "/v1/unpair":
            deck.devices.forget(device["id"])
            self.reply(200, {"ok": True})
        else:
            self.reply(404, {"error": "path"})


class DeckServer(http.server.ThreadingHTTPServer):
    """HTTPS on both IPv6 and IPv4 where it can."""

    daemon_threads = True
    timeout_seconds = 15

    def __init__(self, address, deck, context):
        self.deck = deck
        self.context = context
        host, port = address
        self.address_family = socket.AF_INET6 if ":" in host else socket.AF_INET
        super().__init__((host, port), Handler)

    # As HTTPServer's, without asking DNS for a name it does not use.
    def server_bind(self):
        if self.address_family == socket.AF_INET6:
            try:
                self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            except OSError:
                pass
        socketserver.TCPServer.server_bind(self)
        self.server_name = "omadeck"
        self.server_port = self.server_address[1]

    def get_request(self):
        connection, address = self.socket.accept()
        connection.settimeout(self.timeout_seconds)
        return self.context.wrap_socket(connection, server_side=True, do_handshake_on_connect=False), address

    def handle_error(self, request, client_address):
        error = sys.exc_info()[1]
        if not isinstance(error, (ssl.SSLError, ConnectionError, TimeoutError, socket.timeout)):
            print(f"omadeck: {client_address[0]}: {error!r}", file=sys.stderr)


class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        line = self.rfile.readline(65536)
        try:
            request = json.loads(line)
        except ValueError:
            request = None
        reply = self.server.deck.control(request)
        self.wfile.write((json.dumps(reply) + "\n").encode())


class ControlServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True

    def __init__(self, path, deck):
        self.deck = deck
        if os.path.exists(path):
            os.unlink(path)
        old = os.umask(0o177)
        try:
            super().__init__(path, ControlHandler)
        finally:
            os.umask(old)


def tls_context(cert, key):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    return context


def make_server(config, folder, host="::"):
    cert, key = ensure_certificate(folder, config.get("openssl", "openssl"))
    deck = Deck(config, folder, pin_of(cert))
    context = tls_context(cert, key)
    try:
        server = DeckServer((host, deck.port), deck, context)
    except OSError:
        if host != "::":
            raise
        server = DeckServer(("0.0.0.0", deck.port), deck, context)
    deck.port = server.server_address[1]
    return server


def serve(config):
    server = make_server(config, state_dir())
    deck = server.deck
    path = control_path()
    control = ControlServer(path, deck)
    threading.Thread(target=control.serve_forever, daemon=True).start()

    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # mDNS (_omadeck._tcp, TXT v=1): телефон находит компьютер, когда его адрес сменился.
    # Нужен avahi-daemon; без него телефон ищет по адресам из QR.
    announce = None
    try:
        announce = subprocess.Popen([config.get("avahi", "avahi-publish"), "-s", deck.name, SERVICE, str(deck.port), f"v={API}"],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        print("omadeck: no avahi-publish, no mDNS", file=sys.stderr)
    print(f"omadeck: on port {deck.port}, {len(deck.devices.listing())} phone(s) paired", file=sys.stderr)
    try:
        server.serve_forever()
    finally:
        if announce is not None:
            announce.terminate()
        control.shutdown()
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
        deck.devices.flush()


# --- The commands -----------------------------------------------------------------

def ask(request, path=None):
    """One request to the service, on its socket."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(5)
        connection.connect(path or control_path())
        connection.sendall((json.dumps(request) + "\n").encode())
        data = b""
        while not data.endswith(b"\n"):
            chunk = connection.recv(65536)
            if not chunk:
                break
            data += chunk
    return json.loads(data)


def qr_rows(text):
    """qrencode -t ASCII's QR, as rows of 0 and 1 (two characters a module,
    "#" for the dark ones)."""
    lines = text.split("\n")
    while lines and lines[-1].strip() == "":
        lines.pop()
    while lines and lines[0].strip() == "":
        lines.pop(0)
    size = len(lines)
    rows = []
    for line in lines:
        line = line.ljust(size * 2)
        rows.append("".join("1" if line[2 * i] == "#" else "0" for i in range(size)))
    if size < 21 or size > 177 or (size - 21) % 4 != 0:
        raise ValueError(f"not a QR: {size} rows")
    return rows


def terminal_qr(rows, margin=2):
    """QR в терминал: два ряда модулей на строку (▀ ▄ █ и пробел), чёрное на белом через
    ANSI, тихая зона [margin] модулей."""
    if not rows:
        return ""
    size = len(rows)
    grid = [[False] * (size + 2 * margin) for _ in range(margin)]
    grid += [[False] * margin + [m == "1" for m in row] + [False] * margin for row in rows]
    grid += [[False] * (size + 2 * margin) for _ in range(margin)]
    if len(grid) % 2:
        grid.append([False] * (size + 2 * margin))
    lines = []
    for y in range(0, len(grid), 2):
        line = ""
        for top, bottom in zip(grid[y], grid[y + 1]):
            line += {(True, True): "█", (True, False): "▀", (False, True): "▄", (False, False): " "}[(top, bottom)]
        lines.append("\x1b[30;107m" + line + "\x1b[0m")
    return "\n".join(lines)


def qr(uri, qrencode):
    done = subprocess.run([qrencode, "-t", "ASCII", "-m", "0", "-l", "M", uri],
                          check=True, capture_output=True, text=True, timeout=10)
    return qr_rows(done.stdout)


def main():
    parser = argparse.ArgumentParser(prog="omadeck", description="Телефон как пульт для этого компьютера (docs/deck.md).")
    parser.add_argument("--config", help="configuration written by home/deck/default.nix")
    parser.add_argument("command", nargs="?", default="devices", choices=["serve", "pair", "cancel", "devices", "forget"])
    parser.add_argument("target", nargs="?", help="forget: a phone's id, or all")
    parser.add_argument("--json", action="store_true", help="for the shell")
    parser.add_argument("--image", action="store_true", help="pair: QR картинкой, в просмотрщике")
    options = parser.parse_args()

    config = {}
    if options.config:
        with open(options.config) as file:
            config = json.load(file)
    for key, command in (("ipc", "omadeck-ipc"), ("magick", "magick"), ("avahi", "avahi-publish"),
                         ("notify", "notify-send"), ("openssl", "openssl"), ("qrencode", "qrencode"),
                         ("rsvg", "rsvg-convert"), ("ip", "ip"), ("wpctl", "wpctl")):
        config.setdefault(key, shutil.which(command) or command)

    if options.command == "serve":
        serve(config)
        return 0

    request = {"pair": {"op": "pair"}, "cancel": {"op": "cancel"}, "devices": {"op": "devices"},
               "forget": {"op": "forget", "id": options.target or ""}}[options.command]
    if options.command == "forget" and not options.target:
        print("omadeck forget ID|all", file=sys.stderr)
        return 2
    try:
        reply = ask(request)
    except (OSError, ValueError):
        print("The deck is not running (systemctl --user status omadeck).", file=sys.stderr)
        return 1

    if options.command == "pair":
        try:
            reply["qr"] = qr(reply["uri"], config["qrencode"])
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            reply["qr"] = []
            if options.json:
                reply["error"] = str(error)
        if options.json:
            print(json.dumps(reply))
        elif options.image:
            # Картинкой: PNG в $XDG_RUNTIME_DIR, открывается просмотрщиком изображений.
            picture = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir(), "omadeck-pair.png")
            subprocess.run([config["qrencode"], "-t", "PNG", "-s", "12", "-m", "4", "-o", picture, reply["uri"]],
                           check=True, timeout=10)
            subprocess.Popen(["xdg-open", picture], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
            print(picture)
            print(reply["uri"])
        else:
            # Полублоки, явно чёрное на белом и с белой рамкой: вдвое меньше по высоте и
            # не выворачивается тёмной темой терминала (тогда камера видит негатив).
            print(terminal_qr(reply.get("qr") or []))
            print(reply["uri"])
            print(f"Valid until {time.strftime('%H:%M', time.localtime(reply['expires']))}, once.")
        return 0
    if options.command == "devices" and not options.json:
        for device in reply.get("devices", []):
            seen = time.strftime("%Y-%m-%d %H:%M", time.localtime(device.get("seen") or 0))
            print(f"{device['id']}  {device['name']}  (last seen {seen})")
        return 0
    if options.json or options.command == "devices":
        print(json.dumps(reply))
    elif options.command == "forget" and not reply.get("ok"):
        print(f"No phone {options.target}.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
