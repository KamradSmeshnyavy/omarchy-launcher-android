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
    python3 -I -m unittest discover -s desktop/tests -t desktop -q

# Debug-APK: app/build/outputs/apk/debug/app-debug.apk
build:
    ./gradlew --no-daemon -q assembleDebug

# Поставить debug-APK на телефон (USB или беспроводная отладка: just pair / just connect)
install: build
    {{adb}} install -r app/build/outputs/apk/debug/app-debug.apk

# Беспроводная отладка: адрес и код из «Для разработчиков → Отладка по Wi-Fi → Подключить по коду»
pair address code:
    {{adb}} pair {{address}} {{code}}

connect address:
    {{adb}} connect {{address}}

# Логи лаунчера
log:
    {{adb}} logcat --pid=$({{adb}} shell pidof -s io.github.kamradsmeshnyavy.launcher)

lint:
    ./gradlew --no-daemon -q lintDebug
