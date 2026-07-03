# USB Radio — Android-приложение

Приложение для управления USB AM/FM/DAB радиоприёмником через Android (USB OTG).

Протокол и нативные библиотеки восстановлены из оригинального приложения `dab2_V3.12.20250603` (`com.hyinfo.dab`).

## Поддерживаемые устройства

| VID    | PID    | Описание              |
|--------|--------|-----------------------|
| 0x2E88 | 0x4605 | Основной радиомодуль   |
| 0x0483 | 0x5740 | Альтернативный (CDC)   |

## Возможности

- Подключение по USB OTG с запросом разрешения
- Управление диапазонами AM / FM / DAB
- Настройка частоты (Tune +/-, Seek +/-)
- Автосканирование станций
- Список найденных станций
- Воспроизведение аудио через USB isochronous (нативные `libUSBAudio.so`, `libusb100.so`)

## Сборка

```bash
cd android
pip install lief   # нужен для выравнивания .so под 16 KB (arm64)
./gradlew assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Требования: JDK 17+, Android SDK 34, Python 3 + `lief`.

### Совместимость с 16 KB page size (Android 15+)

Нативные библиотеки из dab2 (`libUSBAudio.so`, `libusb100.so`) пересобраны с ELF-выравниванием **16 KB** для `arm64-v8a`. Перед сборкой запускается `scripts/align_native_libs.py`.

### Xiaomi Mi 9T (и аналогичные устройства)

- Архитектура: **arm64-v8a** (также включён armeabi-v7a)
- Android 9+ (minSdk 24)
- Нужен **USB OTG**-кабель/переходник
- `useLegacyPackaging` включён — библиотеки извлекаются при установке (стабильнее на 4 KB устройствах)

## Установка

1. Включите **USB OTG** на Android-устройстве.
2. Подключите радиоприёмник.
3. Установите APK и запустите приложение.
4. Разрешите доступ к USB-устройству при запросе.

## Протокол управления

Управление идёт по **USB CDC ACM** (115200 8N1). Кадры:

```
FA 55 [seq] [len_hi] [len_lo] [payload...] [checksum]
```

Контрольная сумма — двоичное дополнение суммы байт payload (с индекса 5).

Аудиопоток передаётся отдельным USB Audio интерфейсом через libusb (isochronous).

## Структура проекта

```
android/
  app/src/main/java/com/sgladkovsky/radio/   — UI и сервис
  app/src/main/java/com/sgladkovsky/radio/protocol/ — протокол
  app/src/main/java/au/id/jms/usbaudio/      — JNI обёртка аудио
  app/src/main/jniLibs/                      — нативные библиотеки из dab2
```

## Исходное приложение

В репозитории сохранён оригинальный APK: `dab2_V3.12.20250603.apk.1.1.1.1`
