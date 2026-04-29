# Гайд для разработчиков

## Настройка окружения

### 1. `local.properties`

Файл `local.properties` **не хранится в git** (он в `.gitignore`). Создайте его вручную:

```bash
echo "sdk.dir=/opt/android-sdk" > V2rayNG/local.properties
# или
echo "sdk.dir=/home/$USER/Android/Sdk" > V2rayNG/local.properties
```

---

## Проверка перед пушем (обязательно!)

Сборка на GitHub Actions занимает ~5 минут. Чтобы не тратить время зря, запускайте lint локально **до** `git push`.

### Быстрая проверка (lint, ~1-2 мин):
```bash
cd V2rayNG
./gradlew lintFdroidRelease
```

### Полная проверка (компиляция Kotlin + lint, ~3-5 мин):
```bash
cd V2rayNG
./gradlew compileFdroidReleaseKotlin lintFdroidRelease
```

---

## Частые ошибки lint

### `ExtraTranslation` — строка есть в переводе, но нет в дефолтном файле

**Проблема:** добавили строку в `values-ru/strings.xml`, но забыли добавить в `values/strings.xml`.

**Правило:** каждая новая строка должна появляться **сначала** в `values/strings.xml`, потом в переводах.

```
values/strings.xml          ← обязательно (дефолтный)
values-ru/strings.xml       ← перевод (опционально)
values-fa/strings.xml       ← перевод (опционально)
```

---

## Релиз (теги)

GitHub Actions запускает сборку при пуше тега `v*.*.*`:

```bash
git tag v1.0.2
git push origin v1.0.2
```

> ⚠️ Перед тегом убедитесь, что lint прошёл локально!
