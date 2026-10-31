# Around Recorder

Android-приложение для **непрерывной 24/7 записи** звука окружения.

## Возможности

- Непрерывная запись с микрофона через Foreground Service
- Автоматическое сохранение отрывками по **15 минут** (файлы `.m4a`)
- Файлы складываются в папку приложения:  
  `Android/data/com.vegspro.aroundrecorder/files/AroundRecordings`
- Уведомление с кнопкой «Стоп»
- Автозапуск после перезагрузки (опционально)
- Подсказка отключить оптимизацию батареи
- **ИИ-анализ** записи через Google Gemini API (расшифровка / описание звуков)

## Как пользоваться

1. Установите APK
2. Разрешите микрофон и уведомления
3. Нажмите «Начать запись 24/7»
4. При желании отключите оптимизацию батареи
5. Для ИИ: получите бесплатный ключ на [aistudio.google.com/apikey](https://aistudio.google.com/apikey) и вставьте в приложении (кнопка с ключом)

## Технические детали

- Kotlin + Jetpack Compose + Material 3
- MediaRecorder (AAC, 44.1 kHz, 128 kbps)
- minSdk 26, targetSdk 35
- Gemini 1.5 Flash (аудио через inline_data)

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
