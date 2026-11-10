"""Мелодии звонка, будильника и уведомления из коротких звуков пакета (ffmpeg).

Звуки пакетов Customizer — щелчки по 0,04–0,55 с; звонку и будильнику нужно звучать долго.
Здесь из них собирается узор с паузами: звонок ~6,4 с (два такта, телефон его зацикливает),
будильник ~8 с с нарастающей громкостью, уведомление — один звук с хвостом тишины.

Чего в пакете нет, берётся ближайшее (как в omarchy-sound). Вывод детерминированный
(ffmpeg -fflags +bitexact): повторная сборка не меняет ни байта.
"""

import shutil
import subprocess
from pathlib import Path

# Чем заменить отсутствующий звук — по порядку.
FALLBACK = {
    "notify": ("notify", "special", "open", "menu", "switch"),
    "special": ("special", "notify", "open", "menu"),
    "power": ("power", "lock", "critical", "open", "notify"),
    "critical": ("critical", "power", "special", "notify"),
}

KINDS = ("ringtone", "alarm", "notification")


def pick(sounds, name):
    """Путь к звуку [name] или его замене из [sounds] (имя без расширения → Path); None — нечем."""
    for alt in FALLBACK.get(name, (name,)):
        if alt in sounds:
            return sounds[alt]
    return None


def pattern(kind):
    """Узор мелодии: [(звук, начало в мс, громкость)], длина в мс."""
    if kind == "ringtone":
        bar = [("notify", 0, 1.0), ("special", 400, 0.8), ("notify", 800, 1.0), ("power", 1600, 0.9)]
        hits = bar + [(s, t + 3200, g) for s, t, g in bar]
        return hits, 6400
    if kind == "alarm":
        hits = []
        for i in range(16):
            hits.append(("power" if i % 2 == 0 else "critical", i * 500, round(0.35 + 0.65 * i / 15, 3)))
        return hits, 8000
    if kind == "notification":
        return [("notify", 0, 1.0)], 0
    raise ValueError(kind)


def build(sounds, kind, out, ffmpeg=None):
    """Собрать мелодию [kind] из [sounds] ({имя: Path}) в [out] (.ogg). False — нечем или нет ffmpeg."""
    ffmpeg = ffmpeg or shutil.which("ffmpeg")
    if not ffmpeg:
        return False
    hits, length = pattern(kind)
    files = []
    for name, start, gain in hits:
        path = pick(sounds, name)
        if path is None:
            continue
        files.append((path, start, gain))
    if not files:
        return False
    cmd = [ffmpeg, "-v", "error", "-y"]
    for path, _, _ in files:
        cmd += ["-i", str(path)]
    parts = []
    for i, (_, start, gain) in enumerate(files):
        parts.append(f"[{i}:a]aformat=sample_rates=44100:channel_layouts=mono,adelay={start}:all=1,volume={gain}[a{i}]")
    mix = "".join(f"[a{i}]" for i in range(len(files)))
    tail = f"apad=whole_dur={length / 1000}" if length else "apad=pad_dur=0.25"
    parts.append(f"{mix}amix=inputs={len(files)}:normalize=0:dropout_transition=0,{tail},alimiter=limit=0.95[out]")
    cmd += [
        "-filter_complex", ";".join(parts), "-map", "[out]",
        "-c:a", "libvorbis", "-q:a", "4", "-ar", "44100", "-ac", "1",
        "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1",
        str(out),
    ]
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    return subprocess.run(cmd, capture_output=True).returncode == 0


def build_all(folder, ffmpeg=None):
    """Мелодии для пакета в каталоге [folder] (рядом со звуками); возвращает собранные виды."""
    folder = Path(folder)
    sounds = {p.stem: p for p in folder.glob("*.ogg") if p.stem not in KINDS}
    done = []
    for kind in KINDS:
        if build(sounds, kind, folder / f"{kind}.ogg", ffmpeg):
            done.append(kind)
    return done
