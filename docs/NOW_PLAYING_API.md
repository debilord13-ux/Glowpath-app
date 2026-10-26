# GlowPath Now Playing API

Локальный HTTP API для получения текущего трека из GlowPath.

Сервер поднимается автоматически при запуске приложения.  
Порт по умолчанию: **8765**.

Никакой авторизации и API-ключей нет.

---

## Базовый URL

| Где клиент | URL |
|------------|-----|
| На том же телефоне | `http://127.0.0.1:8765` |
| В той же Wi‑Fi сети | `http://<IP-телефона>:8765` |

Узнать IP телефона: Настройки → Wi‑Fi → текущая сеть.

---

## Эндпоинты

### `GET /now` (алиас `/status`)

Текущий трек в JSON (расширенный).

**Пример ответа:**

```json
{
  "title": "90",
  "artist": "Монеточка",
  "album": "Раскраски для взрослых",
  "is_playing": true,
  "has_song": true,
  "duration_ms": 201979,
  "position_ms": 25600,
  "position_raw_ms": 22657,
  "remaining_ms": 176379,
  "progress": 0.1267,
  "position_str": "0:25",
  "duration_str": "3:21",
  "remaining_str": "2:56",
  "current_uri": "content://...",
  "library_size": 159,
  "bridge_queue_size": 0,
  "last_command": "Play / Pause",
  "last_query": "",
  "last_import_uri": "",
  "last_batch_summary": "",
  "updated_at": 1789254998168,
  "server_time": 1789255023000,
  "cover_url": "http://127.0.0.1:8765/cover"
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `title` | string | Название трека |
| `artist` | string | Исполнитель |
| `album` | string | Альбом |
| `is_playing` | boolean | Сейчас играет |
| `has_song` | boolean | Есть загруженный трек |
| `duration_ms` | number | Длительность, мс |
| `position_ms` | number | **Эффективная** позиция (с учётом времени с последнего обновления) |
| `position_raw_ms` | number | Сырая позиция из снапшота |
| `remaining_ms` | number | Сколько осталось |
| `progress` | number | Прогресс 0.0 – 1.0 |
| `position_str` | string | Позиция в виде `m:ss` / `h:mm:ss` |
| `duration_str` | string | Длительность в удобном виде |
| `remaining_str` | string | Остаток в удобном виде |
| `current_uri` | string | URI файла |
| `library_size` | number | Размер библиотеки |
| `bridge_queue_size` | number | Размер очереди bridge |
| `last_command` | string | Последняя команда |
| `last_query` | string | Последний поисковый запрос |
| `last_import_uri` | string | Последний импортированный URI |
| `last_batch_summary` | string | Краткое описание последнего batch |
| `updated_at` | number | Unix timestamp (мс) последнего обновления снапшота |
| `server_time` | number | Текущее время сервера |
| `cover_url` | string | URL обложки |

---

### `GET /cover`

Обложка текущего трека (JPEG).  
Если обложки нет — `404`.

---

### `GET /info`

Информация о сервере.

```json
{
  "api_version": "1.1",
  "server": "GlowPath NowPlayingHttpServer",
  "port": 8765,
  "running": true,
  "timestamp": 1789255023000
}
```

---

### Управление (POST)

| Метод | Путь | Действие |
|-------|------|----------|
| POST | `/control/play_pause` | Play / Pause |
| POST | `/control/next` | Следующий трек |
| POST | `/control/prev` | Предыдущий трек |
| POST | `/control/stop` | Стоп |
| POST / GET | `/control/play_playlist?id={id}` | Запустить плейлист по id |
| POST / GET | `/control/play_playlist?name={имя}` | Запустить плейлист по имени (первое совпадение) |

Все возвращают:

```json
{
  "ok": true,
  "command": "play_pause",
  "message": "Command dispatched"
}
```

---

## Плейлисты (v1.2)

### `GET /playlists`

Список всех пользовательских плейлистов.

```json
{
  "api_version": "1.2",
  "count": 2,
  "playlists": [
    {
      "id": "a1b2c3d4-...",
      "name": "Road trip",
      "description": "",
      "song_count": 12,
      "total_duration_ms": 2418000,
      "total_duration_str": "40:18",
      "is_pinned": true,
      "created_at": 1789254998168,
      "updated_at": 1789255023000,
      "cover_url": "http://127.0.0.1:8765/playlists/a1b2c3d4-.../cover",
      "songs_url": "http://127.0.0.1:8765/playlists/a1b2c3d4-..."
    }
  ],
  "server_time": 1789255023000
}
```

| Поле | Описание |
|------|----------|
| `id` | Идентификатор плейлиста (используется в `/playlists/{id}` и `/control/play_playlist`) |
| `song_count` | Сколько треков реально есть в библиотеке (удалённые не считаются) |
| `total_duration_ms` / `total_duration_str` | Суммарная длительность |
| `is_pinned` | Закреплён ли плейлист |
| `cover_url` / `songs_url` | Готовые ссылки для следующих запросов |

### `GET /playlists/{id}` (алиас `GET /playlist?id={id}`)

Плейлист с полным списком песен — в том же порядке, что и в приложении.

```json
{
  "id": "a1b2c3d4-...",
  "name": "Road trip",
  "song_count": 2,
  "songs": [
    {
      "index": 0,
      "song_id": 12345,
      "title": "4U",
      "artist": "Раковая выухухоль",
      "album": "Audio",
      "duration_ms": 61000,
      "duration_str": "1:01",
      "uri": "content://media/external/audio/...",
      "is_current": true
    },
    {
      "index": 1,
      "song_id": 12346,
      "title": "Animals",
      "artist": "Alex G",
      "album": "Trick",
      "duration_ms": 173000,
      "duration_str": "2:53",
      "uri": "content://media/external/audio/...",
      "is_current": false
    }
  ]
}
```

- `index` — позиция в плейлисте (с нуля).
- `is_current` — играет ли этот трек прямо сейчас.
- Если плейлиста с таким id нет — `404`.

### `GET /playlists/{id}/cover` (алиас `GET /playlist_cover?id={id}`)

Обложка плейлиста (JPEG): кастомная обложка → обложка выбранного/первого трека. Если обложки нет — `404`.

### `GET /search?q={запрос}`

Поиск по библиотеке (тот же скоринг, что и в bridge-провайдере, топ-12):

```json
{
  "api_version": "1.2",
  "query": "animals",
  "count": 1,
  "results": [
    { "song_id": 12346, "title": "Animals", "artist": "Alex G", "score": 260, "...": "..." }
  ]
}
```

---

### `GET /`

Краткая текстовая справка.

---

## Примеры

### curl

```bash
curl http://127.0.0.1:8765/now
curl -o cover.jpg http://127.0.0.1:8765/cover
curl -X POST http://127.0.0.1:8765/control/next

# плейлисты
curl http://127.0.0.1:8765/playlists
curl http://127.0.0.1:8765/playlists/a1b2c3d4-...
curl -o playlist_cover.jpg http://127.0.0.1:8765/playlists/a1b2c3d4-.../cover
curl -X POST "http://127.0.0.1:8765/control/play_playlist?name=Road%20trip"
curl "http://127.0.0.1:8765/search?q=animals"
```

### JavaScript / fetch

```js
const res = await fetch("http://127.0.0.1:8765/now");
const track = await res.json();
console.log(`${track.artist} — ${track.title}`);
console.log(`${track.position_str} / ${track.duration_str} (${(track.progress * 100).toFixed(1)}%)`);
```

### Python

```python
import requests

r = requests.get("http://127.0.0.1:8765/now", timeout=5)
data = r.json()
print(f"{data['artist']} — {data['title']}")
print(f"{data['position_str']} / {data['duration_str']} ({data['progress']*100:.1f}%)")
```

### Hikka / Heroku модуль

В конфиге модуля:

```
api_base = http://127.0.0.1:8765
```

(если userbot на том же телефоне, например Termux)

---

## ExteraGram bridge (альтернатива)

Помимо HTTP, в приложении есть ContentProvider:

| URI | Назначение |
|-----|------------|
| `content://com.musicplayer.bridge/status` | Статус трека (Cursor) |
| `content://com.musicplayer.bridge/cover/current` | Обложка текущего трека |
| `content://com.musicplayer.bridge/search?q=...` | Поиск по библиотеке (Cursor) |
| `content://com.musicplayer.bridge/playlists` | Все плейлисты (Cursor) |
| `content://com.musicplayer.bridge/playlists/{id}/songs` | Треки плейлиста по порядку (Cursor) |
| `content://com.musicplayer.bridge/playlists/{id}/cover` | Обложка плейлиста (файл-стрим) |

Authority: `com.musicplayer.bridge`

Колонки `playlists`: `playlist_id, name, description, song_count, total_duration_ms, is_pinned, created_at, updated_at, cover_uri`.

Колонки `playlists/{id}/songs`: `track_index, song_id, title, artist, album, duration_ms, uri, cover_uri`.

Подходит только для кода, который выполняется **на том же устройстве** внутри кастомного клиента Telegram — именно этот путь использует плагин Now Card.

---

## Замечания

- Сервер работает, только пока процесс приложения жив (приложение открыто или играет в фоне).
- CORS: `Access-Control-Allow-Origin: *` — можно дергать из WebView / браузера.
- Данные берутся из того же snapshot, что и ExteraGram bridge.
- Порт сейчас фиксированный: `8765`.
- `position_ms` — **эффективная** позиция. Модулю не нужно самому считать прошедшее время.

---

## Для разработчиков плагинов / модулей

Минимальный сценарий «now playing» карточки:

1. `GET /now` → title, artist, is_playing, position_str, duration_str, progress  
2. `GET /cover` → картинка  
3. Собрать сообщение / баннер  

Поллинг раз в 8–15 секунд обычно достаточно для авто-обновления.

Вопросы и PR — в Issues репозитория.
