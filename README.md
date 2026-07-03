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
./gradlew assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Требования: JDK 17+, Android SDK 34.

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
