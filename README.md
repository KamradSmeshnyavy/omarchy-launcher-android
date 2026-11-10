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
Compose, no libraries. It talks to your own computer once paired, and to Open-Meteo for the
weather while that is on — nothing else.

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
- **Desks.** Several home screens, as Hyprland workspaces: swipe sideways or tap the bar.
  Lay them out as a grid (cells, as in Android launchers) or a stack of cards — Customizer's
  choice; long press anything for size, place, settings or another desk. In the grid the desk
  fits the screen (no scrolling, so swipe up always opens the drawer) and widgets — app widgets
  too, within the app's limits — resize by dragging the handles of their frame. Desks are
  renamed, reordered and deleted in Customizer → Desks or from the long-press menu.
- **The bar.** Android's status bar can't be restyled, so on the home screen the launcher hides it
  and draws its own in any of the 24 Omarchy bar skins (the style's, the computer's or your own):
  desks in 22 label styles, weather, time, battery. Swipe from the edge for the system one.
- **Widgets.** Thirty-five of its own, each in the DOT-MATRIX voice (5×7 LED digits, dots, LED bars)
  or the style's: dot clock, analog in dots, world clock, month, alarm, weather with a forecast,
  battery ring, Bluetooth devices, cover in LEDs, photo frame, quote, to-do, events, the
  computer (CPU, memory, battery, temperature, controls), toggles, compass, steps, countdown,
  screen time; and in the spirit of Nothing OS's set, drawn its own way: big clock, big date,
  stopwatch, timer, sunrise and sunset, air quality, moon phase, recorder, camera, torch, ring
  mode, storage, data this month, favourite contacts, search — and any app's widget, in the style's card with Material You colours taken from
  the theme, tinted to the theme or to mono if you like.
- **Lock screen.** The 30 Omarchy lock designs as the lock-screen wallpaper (Android draws the
  clock there) and as the screensaver, with a live clock.
- **Home widgets.** The clock as `big`, `stacked`, `terminal` or `minimal`, 24-hour and
  seconds; the weather in it (Open-Meteo, for the computer's place or a city of your own;
  tap it for the weather app); now playing (any player); the to-do list shared with the computer.
- **Theme wallpapers.** The current Omarchy theme's wallpapers, from the paired computer, cut
  to the phone's screen — as they are, in LEDs or recoloured; optionally put on with the theme.
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
omadeck pair --image   # the same QR as a picture
```

The phone also pairs from the `omadeck://pair?…` link printed under the QR (opened on the
phone, e.g. sent over KDE Connect), after asking.

`omadeck` is NIHIL's `nihil-deck` (Python, standard library only) with an Omarchy adapter:
apps from XDG through `uwsm-app`, `omarchy-launch-browser`, `omarchy-shell media`,
`omarchy-audio-output-volume`, `omarchy-system-lock`, `omarchy-toggle-idle` and
`-nightlight`; the trackpad through `/dev/uinput` (the `uinput` group). HTTPS on the local
network with a self-signed certificate the phone pins from the QR; a one-time code; tokens
kept only as hashes. The protocol is in [docs/deck.md](docs/deck.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | The deck (the paired computer on your network) and, when it is on, the weather from Open-Meteo (only coordinates are sent). |
| `CAMERA` | Only to read the computer's pairing QR. |
| `SET_WALLPAPER` | The studio and "follow the computer". |
| `EXPAND_STATUS_BAR` | Swipe down opens the notification shade. |
| `REQUEST_DELETE_PACKAGES` | "Uninstall" in an app's menu. |
| `BLUETOOTH_CONNECT`, `READ_CALENDAR`, `READ_CONTACTS`, `ACTIVITY_RECOGNITION`, usage access | Only for the Bluetooth, events, contacts, steps, screen-time and data widgets, asked when you add or tap one. |
| `SET_ALARM` | The timer widget starts the Clock app's timer, so it rings. |
| `ACCESS_NETWORK_STATE` | "Follow the computer" checks it only on Wi-Fi. |
| Notification access (optional) | Glyph light, unread LEDs and now playing (the track from media sessions). Never reads notification text. |
| Accessibility (optional) | Double tap to lock, Glyph light over other apps. Reads nothing. |

## Credits

- [NIHIL Launcher](https://github.com/poligle/Nihil-Launcher) and the deck from
  [Nihil-Shell](https://github.com/poligle/Nihil-Shell) by poligle — MIT.
- Omarchy themes are their authors'; only their colours are in the APK.
- Fonts under the SIL Open Font License 1.1 — see [NOTICE](NOTICE).

MIT — see [LICENSE](LICENSE).
