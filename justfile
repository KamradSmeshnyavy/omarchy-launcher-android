# DOT-MATRIX Launcher: команды разработки.

export ANDROID_HOME := env_var_or_default("ANDROID_HOME", env_var("HOME") + "/Android/Sdk")
adb := ANDROID_HOME + "/platform-tools/adb"

default:
    @just --list

# Темы Omarchy, стили и настройки Customizer → assets (--no-thumbs: без миниатюр, быстро)
export *args:
    python3 -I tools/export-looks.py {{args}}

# JVM-тесты приложения и тесты сервера deck
test:
    ./gradlew --no-daemon -q test
    python3 -I desktop/tests/test_deck.py
    python3 -I desktop/tests/test_omarchy.py
    python3 -I desktop/tests/test_melody.py

# Debug-APK: app/build/outputs/apk/debug/app-debug.apk
build:
    ./gradlew --no-daemon -q assembleDebug

# Поставить debug-APK на телефон (USB или беспроводная отладка: just adb-pair / just adb-connect)
install: build
    {{adb}} install -r app/build/outputs/apk/debug/app-debug.apk

# Беспроводная отладка: адрес и код из «Для разработчиков → Отладка по Wi-Fi → Подключить по коду»
adb-pair address code:
    {{adb}} pair {{address}} {{code}}

adb-connect address:
    {{adb}} connect {{address}}

# Логи лаунчера
log:
    {{adb}} logcat --pid=$({{adb}} shell pidof -s io.github.kamradsmeshnyavy.launcher)

lint:
    ./gradlew --no-daemon -q lintDebug

# --- Пульт на компьютере (omadeck) -------------------------------------------------

# Ссылки omadeck/omadeck-ipc в ~/.local/bin и служба systemd --user (запуск вместе с сессией)
server-install:
    mkdir -p ~/.local/bin ~/.config/systemd/user
    ln -sfn {{justfile_directory()}}/desktop/bin/omadeck ~/.local/bin/omadeck
    ln -sfn {{justfile_directory()}}/desktop/bin/omadeck-ipc ~/.local/bin/omadeck-ipc
    ln -sfn {{justfile_directory()}}/desktop/omadeck.service ~/.config/systemd/user/omadeck.service
    systemctl --user daemon-reload
    systemctl --user enable --now omadeck.service
    @echo "Порт 64445/tcp должен быть открыт для локальной сети: just server-firewall"

# Открыть порт пульта в ufw только для частных сетей (LAN и Tailscale), нужен sudo
server-firewall:
    sudo ufw allow from 192.168.0.0/16 to any port 64445 proto tcp comment omadeck
    sudo ufw allow from 10.0.0.0/8 to any port 64445 proto tcp comment omadeck
    sudo ufw allow from 172.16.0.0/12 to any port 64445 proto tcp comment omadeck
    sudo ufw allow from 100.64.0.0/10 to any port 64445 proto tcp comment omadeck

# QR для сопряжения телефона (DOT-MATRIX Launcher → Настройки → Deck → Сканировать)
server-pair:
    omadeck pair

server-status:
    systemctl --user status omadeck.service --no-pager
    omadeck devices
