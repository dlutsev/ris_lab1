# Lab1 CrackHash

РИС для брутфорса строки по MD5 hash

## Структура (что для чего) (Gradle)

| Модуль   | Назначение                                                                                  |
|----------|---------------------------------------------------------------------------------------------|
| `common` | Общие DTO для обмена manager - worker (`WorkerTaskRequest`, `WorkerTaskResponse`)           |
| `manager` | REST API для клиента, планировщик очереди, кэш, таймауты, вызовы воркеров, все основное тут |
| `worker`  | Приём задачи, перебор в своей части пространства слов, callback в manager                   |

## Сборка/запуск:

Сборка:
```bash
./gradlew :manager:bootJar :worker:bootJar
```
Запуск через docker compose:
```bash
docker compose up -d
```
## Методы

1. **Клиент -> Manager**  
   `POST /api/hash/crack` с `{ "hash", "maxLength" }`, ответ `{ "requestId" }`. 
2. 

   `GET /api/hash/status?requestId=...`, в ответ `{ "status", "data" }`.

3. **Manager -> Worker**  
   `POST /internal/api/worker/hash/crack/task` — тело `WorkerTaskRequest` (JSON).

4. **Worker -> Manager**  
   `PATCH /internal/api/manager/hash/crack/request` — тело `WorkerTaskResponse` (JSON).

В worker для `PATCH` используется Apache HttpClient (корректная поддержка метода `PATCH`).

## Конфигурация Manager

Файл: `manager/src/main/resources/application.yml`

| Параметр | Описание                                                                        |
|----------|---------------------------------------------------------------------------------|
| `server.port` | Очевидно, что порт на каком поднимется сервис manager (по умолчанию 8080)       |
| `crackhash.worker-count` | Число воркеров (2 по умолчанию)                                                 |
| `crackhash.worker-base-urls` | Список URL воркеров через запятую (например `http://worker1:8080,http://worker2:8080`) |
| `crackhash.request-ttl-millis` | Лимит времени **перебора на воркерах** (отсчёт с момента старта задач на worker, ожидание в очереди не входит); по истечении — `ERROR`, слот освобождается |
| `crackhash.alphabet` | Алфавит для перебора (строка символов, по дефолту строчные английские буквы + цифры) |
| `crackhash.queue-capacity` | Максимум запросов в очереди ожидания (когда активен уже один запрос)            |
| `crackhash.cache-capacity` | Размер кэша по паре `(hash, maxLength)` для готовых результатов                 |

Параллельное выполнение запросов захардкожено: одновременно обрабатывается только один запрос на worker (`MAX_CONCURRENT_REQUESTS = 1` в коде).

Переопределение из окружения в docker compose, например:

- `CRACKHASH_REQUEST_TTL_MILLIS=30000`
- `CRACKHASH_WORKER_COUNT=2`
- `CRACKHASH_WORKER_BASE_URLS=http://worker1:8080,http://worker2:8080`

Spring Boot мапит `CRACKHASH_*` на `crackhash.*` в relaxed binding.

## Конфигурация Worker

Файл: `worker/src/main/resources/application.yml`

| Параметр | Описание                                                                  |
|----------|---------------------------------------------------------------------------|
| `server.port` | Порт внутри контейнера (опять 8080)                                       |
| `crackhash.manager-callback-url` | URL для `PATCH` с результатом части (в Docker: `http://manager:8080/...`) |

## Статусы ответа клиенту (`GET /api/hash/status`)

| Статус | Значение |
|--------|----------|
| `IN_PROGRESS` | Запрос ещё в работе или в очереди |
| `READY` | Все части успешно, есть ответы |
| `PARTIAL_RESULT` | Есть ответы, но часть воркеров/частей завершилась с ошибкой |
| `ERROR` | Ошибка, таймаут, переполнение очереди, запрос не найден |

## Docker Compose

Файл `docker-compose.yml` поднимает `manager` и два контейнера `worker` на основе **одного образа** `crackhash-worker:latest`.

```bash
docker compose build
docker compose up
```

- Manager: `http://localhost:8080`
- Worker1 (опционально с хоста): `http://localhost:8081`
- Worker2: `http://localhost:8082`

Пример запроса:

```bash
curl -X POST "http://localhost:8080/api/hash/crack" ^
  -H "Content-Type: application/json" ^
  -d "{\"hash\":\"<md5_hex>\",\"maxLength\":5}"

curl "http://localhost:8080/api/hash/status?requestId=<uuid>"
```
А лучше используйте Postman
## Дополнительно

- Очередь планировщика хранит только `PendingCrackRequest` (`requestId`, `hash`, `maxLength`); объект `CrackHashRequestInfo` (счётчики частей, ответы, TTL перебора) создаётся при фактическом старте задач на воркерах.
- Кэш по `(hash, maxLength)` ограничен по размеру; при вытеснении из кэша новый запрос с тем же `hash`/`maxLength` снова пойдёт в перебор.
- После таймаута перебора manager переводит запрос в `ERROR` и освобождает слот; задачи на worker при этом теоретически могут ещё выполняться — отдельный мониторинг не делается.
