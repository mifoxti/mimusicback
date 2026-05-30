# Нагрузочное тестирование MiMusic (k6, без Grafana)

**Grafana не обязательна.** Инструмент называется «Grafana k6», но отчёт для диплома — это вывод в **терминале** после `k6 run` (как на рисунке 3.6–3.8).

## Установка k6 (Windows)

```powershell
winget install GrafanaLabs.k6
```

Если winget не сработает — скачайте установщик и запустите вручную:
https://dl.k6.io/msi/k6-latest-amd64.msi

После установки **закройте и снова откройте** PowerShell, затем: `k6 version`

Если `k6` не находится (старая сессия PowerShell), обновите PATH в текущем окне:

```powershell
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
k6 version
```

Или вызывайте напрямую: `& "C:\Program Files\k6\k6.exe" run scenario1-auth-profile.js`

Проверка: `k6 version`

## Перед запуском

1. Запущен сервер: `.\gradlew.bat run` (порт **8080**).
2. PostgreSQL с данными и **тестовый пользователь** для логина.
3. Переменные (PowerShell):

```powershell
$env:BASE_URL = "http://127.0.0.1:8080"
$env:K6_PASSWORD = "12345678"
$env:K6_MULTI_USER = "1"
```

**Важно:** при входе сервер хранит **одну сессию на пользователя** (новый `POST /login` удаляет старый токен). Если все VU ходят как `test2`, запросы с Bearer получают **401** — так и было во 2-м сценарии (0% на `/recommendations/tracks`).

Перед тестом создайте аккаунты `loadtest1` … `loadtest30` (по одному на VU):

```powershell
# Если скрипт не запускается (ExecutionPolicy), один раз в этом окне:
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

.\seed-load-users.ps1 -Count 30 -Password "12345678"
```

Или без изменения политики:

```powershell
powershell -ExecutionPolicy Bypass -File ".\seed-load-users.ps1" -Count 30 -Password "12345678"
```

Один пользователь (только 1 VU): `$env:K6_MULTI_USER = "0"` и `$env:K6_LOGIN = "test2"`.

## Сценарии

| Файл | Аналог Englio | Эндпоинты |
|------|---------------|-----------|
| `scenario1-auth-profile.js` | Авторизация + профиль | `POST /login`, `GET /me`, `GET /tracks` |
| `scenario2-recommendations.js` | «Тяжёлый» модуль (ИИ → рекомендации) | `GET /recommendations/tracks` |
| `scenario3-catalog-social.js` | Учебная сессия → каталог + соц. | `GET /tracks`, `GET /friends`, `GET /notifications/unread-count` |

## Запуск (скриншот = полный вывод в консоли)

```powershell
cd D:\Programming\MiMusic\mimusicback-master\load-tests

k6 run scenario1-auth-profile.js
k6 run scenario2-recommendations.js
k6 run scenario3-catalog-social.js
```

Успех: зелёные `✓` у checks, `http_req_failed: 0.00%`, пороги `p(95)` не красные.

## Альтернативы без k6

| Инструмент | Скриншот | Grafana |
|------------|----------|---------|
| **k6** (рекомендуется) | Терминал | Не нужна |
| **Apache JMeter** | GUI «Summary Report» | Не нужна |
| **Artillery** | Терминал + `report.html` | Не нужна |
| **hey** / **bombardier** | Терминал (кратко) | Не нужна |
