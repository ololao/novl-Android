# NOVL Android (v0.1)

Клиентское приложение Android для туннелирования трафика через протокол NOVL. Реализовано на базе интерфейса WireGuard (Apache-2.0) и нативного модуля `libnovl.so` (Rust).

---

## Архитектура и стек

* **Сетевой движок (Rust / ARM64-v8a)**:
  * Проксирование L3 TUN в L4 SOCKS5 через стек `tun2proxy`.
  * Маскировка `TLS-Chameleon` с генерацией TLS 1.3 ClientHello и криптографической аутентификацией сессий через BLAKE3.
  * Фрагментация первого пакета и джиттер 1 мс для обхода L7 DPI.
  * Инкапсуляция UDP-over-TCP (UoT) для DNS, аудио/видеовызовов и мессенджеров.
  * Фиксированный размер TCP MSS (1240 байт) для предотвращения фрагментации в сотовых сетях.
* **Защита сокетов (`VpnService.protect`)**:
  * Служебные сетевые сокеты защищены от рекурсивной маршрутизации в собственный TUN-интерфейс через прямые вызовы JNI.
* **Исключение локальных приложений**:
  * Поддержка Split Tunneling: прямой маршрут для сервисов РФ (банки, Госуслуги, маркетплейсы) в обход туннеля через `VpnService.Builder.addDisallowedApplication`.
* **Модуль OTA-обновлений**:
  * Фоновый опрос JSON-манифеста версий с поддержкой зеркал (VPS и Codeberg).
  * Скачивание обновлений и установка через системный `PackageInstaller`.

---

## Сборка

### Окружение:
* Android SDK (API 34+)
* Android NDK (r26+ / r28+)
* Rust toolchain с таргетом `aarch64-linux-android`
* JDK 21

### 1. Сборка библиотеки ядра:
```bash
cd Projects/novl
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libnovl.so ../novl-android/tunnel/src/main/jniLibs/arm64-v8a/libwg-go.so
```

### 2. Сборка APK:
```bash
cd Projects/novl-android
./gradlew :ui:assembleRelease
```

### 3. Подпись релизным ключом:
```bash
apksigner sign --ks release.keystore \
  --ks-pass pass:novl123 \
  --ks-key-alias novl \
  --key-pass pass:novl123 \
  --out novl-release.apk \
  ui/build/outputs/apk/release/ui-release-unsigned.apk
```
