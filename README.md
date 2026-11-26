# NovaDeck

**Offline Hardware Telemetry, Diagnostics, Storage Analyzer & C++17 NDK Benchmark Suite for Android**  
*(Минималистичный брутальный пульт системной диагностики, анализа памяти и нативного бенчмарка для Android)*

---

## Скачать APK для Android (Download APK)

> **Установка и обновление без удаления старой версии!** Все релизы и встроенное обновление подписываются единым ключом (`io.novadeck.control`), поэтому новая версия устанавливается **прямо поверх старой** с сохранением всех настроек и истории бенчмарков.

- **[Скачать `NovaDeck-release.apk` (прямая ссылка из репозитория — ~2.3 МБ, arm64-v8a + armeabi-v7a)](https://github.com/RoMaSHia/NovaDeck/raw/main/dist/NovaDeck-release.apk)**
- **[Скачать последний релиз из GitHub Releases (`NovaDeck-release.apk`)](https://github.com/RoMaSHia/NovaDeck/releases/latest/download/NovaDeck-release.apk)**
- **[Все релизы и Debug-сборки (GitHub Releases)](https://github.com/RoMaSHia/NovaDeck/releases)**

### Встроенное автообновление поверх текущей версии (In-Place OTA Updater)
На главном экране (**ПУЛЬТ / DECK -> `04 // ОБНОВЛЕНИЕ ПОВЕРХ ВЕРСИИ`**) и в меню **`ОПЦИИ / CONFIG`** доступна кнопка проверки и установки обновлений в один тап:
1. Нажмите **«ПРОВЕРИТЬ»** — приложение проверяет [`version.json`](https://raw.githubusercontent.com/RoMaSHia/NovaDeck/main/version.json) и последний релиз на GitHub (сеть используется только в этот момент).
2. Нажмите **«ОБНОВИТЬ СРАЗУ»** / **«СКАЧАТЬ ПОВЕРХ»** — новый APK скачивается во временный кэш и автоматически запускает системный установщик поверх текущей версии без её удаления.

---

## Русский

### Концепция и оптимизации
**NovaDeck** (`io.novadeck.control`) вдохновлён десктопной утилитой **StellarHub** и оформлен в **минималистичном брутальном тёмном стиле** (`#0A0A0B` матовый карбон, `#121214` анодированные панели, `#E85A2A` сигнальный оранжевый акцент).

- **Приватность по умолчанию:** 0 рекламы, 0 подписок, 0 аналитики и скрытых фоновых запросов (разрешение `INTERNET` используется исключительно при ручном нажатии кнопки проверки/скачивания обновления).
- **Стабильные 60–120 FPS даже на смартфонах с 2–3 ГБ ОЗУ:**
  - **Zero-Allocation Polling (`FloatRingBuffer` + `ZeroAllocLineChart`):** кольцевые буферы примитивов и переиспользуемые `Path` в Compose `Canvas` полностью убирают микрофризы Garbage Collector.
  - **Adaptive Performance Mode (`DECK_FULL` / `ULTRA_LITE`):** автоматически распознаёт `<= 3 ГБ` ОЗУ, `ActivityManager.isLowRamDevice` и режим энергосбережения, отключая лишний overdraw.
  - **R8 Full Mode + Resource Shrinking:** минимальный размер релизного APK (~2.3 МБ с обеими нативными библиотеками).
  - **Мгновенный переключатель языка (RU / EN):** прямо в шапке и настройках через `AppCompatDelegate.setApplicationLocales` без перезагрузки телефона.

### Нативное ядро на C++17 NDK (`app/src/main/cpp/native-core.cpp`)
1. **Низкоуровневая телеметрия (`/proc/stat`, `/proc/meminfo`, `cpufreq`, `thermal_zone`):** Zero-Copy запись прямо в `FloatArray` / `LongArray` с автоматическим безопасным fallback на стандартные Android API при ограничениях SELinux.
2. **Многопоточный анализатор памяти:** пул `pthread` (`opendir`/`readdir`/`lstat`), 7 категорий файлов, Топ-25 самых тяжёлых файлов, поиск точных дубликатов (`>= 64 КБ`) по 64-битному хешу **FNV-1a** и очистка временных файлов.
3. **Бенчмарк и стресс-тест троттлинга:** барьерная синхронизация потоков вне таймера `CLOCK_MONOTONIC`, целочисленный (ALU) и векторный (ARM NEON SIMD) тесты, замер латентности DRAM (pointer chasing), POSIX-тест скорости хранилища (SEQ + Random 4K IOPS) и 30-секундный тест троттлинга с графиком падения производительности.

### 7 модулей
1. **Пульт (Home):** паспорт устройства, аптайм, индекс состояния, живые датчики и встроенный апдейтер.
2. **Монитор:** частоты и нагрузка каждого ядра CPU, ток (мА) / напряжение / мощность / температура батареи, ОЗУ + ZRAM, сеть RX/TX + локальный IP + Wi-Fi RSSI.
3. **Память (Storage Checker):** C++ сканер + MediaStore, категории, крупные файлы, дубликаты и очистка кэша/мусора.
4. **Приложения (App Manager):** пользовательские и системные пакеты, пакетное удаление, переход к кэшу и экспорт APK в `Downloads/NovaDeck_Backups/`.
5. **Тесты (Hardware & Sensor Tester):** битые пиксели, мультитач, стереогенератор частот (80–14000 Гц), микрофон (dBFS), вибромотор, фонарик и живые 60 Гц графики 5 датчиков.
6. **Защита (Privacy & Security Audit):** проверка шифрования, Root/Magisk/SU (C++ + Android), ADB, блокировки, патча безопасности и опасных разрешений приложений.
7. **Бенчмарк:** CPU ALU/NEON, DRAM Latency, Storage I/O, 30с стресс-тест троттлинга и локальная история замеров.

---

## English

### Features & Architecture
- **Direct APK Download:** [`NovaDeck-release.apk`](https://github.com/RoMaSHia/NovaDeck/raw/main/dist/NovaDeck-release.apk) or [GitHub Releases](https://github.com/RoMaSHia/NovaDeck/releases/latest/download/NovaDeck-release.apk) (~2.3 MB universal `arm64-v8a` + `armeabi-v7a`, signed identically across builds for seamless **in-place upgrades** without uninstalling).
- **In-Place OTA Self-Updater:** Built straight into the Home Deck and Config modal (`AppUpdater.kt` + `FileProvider` + `REQUEST_INSTALL_PACKAGES`).
- **60–120 FPS Zero-Allocation Engine:** Primitive `FloatRingBuffer`s + reused Compose `Path`s + `Adaptive Performance Mode` (`DECK_FULL` vs zero-overdraw `ULTRA_LITE` on `<= 3 GB` RAM / Battery Saver) + R8 Full Mode.
- **C++17 NDK Core:** Low-level `/proc` & `/sys` reader with SELinux Android API fallback, multi-threaded POSIX directory walker with FNV-1a duplicate detection, and barrier-synchronized ALU / ARM NEON SIMD / DRAM latency / POSIX Storage I/O / 30s thermal throttling benchmark.

### Local Build
```bash
./gradlew assembleDebug assembleRelease
```
CI/CD (`.github/workflows/build-apk.yml`) automatically compiles both `NovaDeck-debug.apk` and signed `NovaDeck-release.apk` (`arm64-v8a` + `armeabi-v7a`) on every push to `main` and publishes GitHub Releases on `v*` tags.
