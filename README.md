<p align="center"><a href="README.ru.md">🇷🇺 Русский</a></p>

# DOT-MATRIX Launcher

A home screen for Android that dresses like your [Omarchy](https://omarchy.org) desktop: the
same themes, the same Customizer styles and settings, the same Glyph light, and a deck that
drives the computer from the phone.

It is a fork of [NIHIL Launcher](https://github.com/poligle/Nihil-Launcher) by poligle (MIT),
whose monochrome dot-matrix design is kept as the **DOT-MATRIX** style. Everything else grew
around it: any Omarchy theme, eight more styles, Customizer's settings, a server for Omarchy
(`omadeck`) and "follow the computer".

No ads, no tracking, no account. Kotlin on the Android framework alone — no AndroidX, no
Compose, no libraries. It talks to nothing but your own computer, and only once paired.

## What it has

- **Omarchy themes.** All of them, built into the APK from `~/.config/omarchy/themes` and the
  stock ones (`colors.toml`, or `alacritty.toml` for older themes): palette, light or dark,
  starred themes first. A style's light and dark pair follows Android's dark mode.
- **Customizer styles.** Flat, Glass, Fluent, DOT-MATRIX, Brutalism, Neon, Neumorphism, Paper,
  Retro: material, shadows, corners, motion, typeface, Glyph and sounds — with "Apply from
  styles" deciding which parts a style changes, as on the desktop.
- **Customizer settings.** Its own tables (Glyph, sounds, Dotify, DOT-MATRIX palette, widgets,
  launcher, styles, presets) drawn from Customizer's `settings.json` schema: toggles, choices,
  LED sliders, theme-role swatches, search across all of them, long press for the style's value.
  Your own presets save the whole look.
- **Fonts as profiles.** stock, nothing, pixel, lumen, dots-all, paper, retro — DotMatrix
  Dot/Mono/Pixel/Lumen with Cyrillic.
- **Home widgets.** The clock as `big`, `stacked`, `terminal` or `minimal`, 24-hour and
  seconds; now playing (any player); the to-do list shared with the computer.
- **Drawer skins.** grid (NIHIL's), compact, list, dotmatrix; Customizer's backdrop lets the
  wallpaper through.
- **Glyph light.** NIHIL's light round the frame on notifications, calls and charging, in the
  colour, thickness and speed Customizer sets.
- **Sounds.** Customizer's packs on opening, closing, menus, toggles, Glyph and lock.
- **Wallpaper studio.** Dotify (the LED matrix, with Customizer's pitch, gamma, unlit LEDs and
  colours) or Recolor (a photo in the theme's palette, like lutgen).
- **The deck.** Paired with `omadeck` on the computer, the phone opens its apps and webs,
  plays and skips its music, turns its volume, locks it, toggles caffeine and night light,
  syncs tasks and is its trackpad.
- **Follow the computer.** Theme, style, Glyph, sounds, widgets, fonts and wallpaper as they
  are on the paired Omarchy, whenever they change there.
- **From NIHIL:** one search field for apps, `>` actions and `=` a calculator with units,
  bases, derivatives, integrals and equations; the frame and the drawer growing from it;
  battery as ten LEDs; the QR reader written from scratch.
- English, Russian (and Spanish from NIHIL for its own screens).

## Build

```sh
just export     # themes, styles and Customizer settings → app/src/main/assets/looks
just test       # JVM tests and the deck server's tests
just build      # app/build/outputs/apk/debug/app-debug.apk
just install    # onto the phone (USB, or just adb-pair / just adb-connect over Wi-Fi)
```

Needs JDK 17+ and the Android SDK (`ANDROID_HOME`, default `~/Android/Sdk`, platform 35,
build-tools 35.0.0). `just export` reads the themes and Customizer from this machine; the
wallpaper thumbnails it makes stay local (they are other people's pictures) — without them
the gallery shows palette stripes.

## The computer: omadeck

```sh
just server-install    # omadeck in ~/.local/bin, omadeck.service in systemd --user
just server-firewall   # 64445/tcp in ufw for private networks (LAN, Tailscale)
just server-pair       # the QR to scan: Settings → Computer → Pair
```

`omadeck` is NIHIL's `nihil-deck` (Python, standard library only) with an Omarchy adapter:
apps from XDG through `uwsm-app`, `omarchy-launch-browser`, `omarchy-shell media`,
`omarchy-audio-output-volume`, `omarchy-system-lock`, `omarchy-toggle-idle` and
`-nightlight`; the trackpad through `/dev/uinput` (the `uinput` group). HTTPS on the local
network with a self-signed certificate the phone pins from the QR; a one-time code; tokens
kept only as hashes. The protocol is in [docs/deck.md](docs/deck.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Only the deck: the paired computer on your network. |
| `CAMERA` | Only to read the computer's pairing QR. |
| `SET_WALLPAPER` | The studio and "follow the computer". |
| `EXPAND_STATUS_BAR` | Swipe down opens the notification shade. |
| `REQUEST_DELETE_PACKAGES` | "Uninstall" in an app's menu. |
| Notification access (optional) | Glyph light, unread LEDs and now playing (the track from media sessions). Never reads notification text. |
| Accessibility (optional) | Double tap to lock, Glyph light over other apps. Reads nothing. |

## Credits

- [NIHIL Launcher](https://github.com/poligle/Nihil-Launcher) and the deck from
  [Nihil-Shell](https://github.com/poligle/Nihil-Shell) by poligle — MIT.
- Omarchy themes are their authors'; only their colours are in the APK.
- Fonts under the SIL Open Font License 1.1 — see [NOTICE](NOTICE).

MIT — see [LICENSE](LICENSE).
