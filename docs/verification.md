# Проверка работы

Выводы команд с реального прогона. Ничего не выдумано.

## Запуск

```
docker compose up --build -d
```

```
Volume notes-platform-java_db_data Created
Network notes-platform-java_app_net Created
Container notes-db Started
Container notes-users Started
Container notes-tags Started
Container notes-notes Started
Container notes-search Started
Container notes-gateway Started
```

## docker compose ps

```
NAME          IMAGE                       STATUS
notes-db      postgres:16                 Up (healthy)
notes-gateway notes-platform-java-gateway  Up, 0.0.0.0:8080->80/tcp
notes-notes   notes-platform-java-notes    Up
notes-search  notes-platform-java-search   Up
notes-tags    notes-platform-java-tags     Up
notes-users   notes-platform-java-users    Up
```

## Health-чеки через gateway

```
users  -> {"status":"ok","service":"users"}
tags   -> {"service":"tags","status":"ok"}
notes  -> {"status":"ok","service":"notes"}
search -> {"service":"search","status":"ok"}
```

## Сценарий (scripts/check.ps1)

```
=== create user ===
user 1 alice

=== create notes with tags ===
note 1 tags=devops,study
note 2 tags=sql,study

=== read note and list ===
note 1 title: Docker basics
user notes: 2

=== search by text ===
total=1 author=alice facets=devops,study
by tag study: total=2

=== tag statistics ===
devops = 1
sql = 1
study = 2

=== update and delete ===
updated title: Docker basics v2
note 2 deleted

=== errors ===
duplicate: 409 {"error":"username 'alice' already exists"}
unknown user: 404 {"error":"user 999999 not found"}
```

В логах видно межсервисные вызовы:

```
[users] created user 1 alice
[tags] resolved [devops, study]
[notes] created note 1 for user 1 tags=[TagRef[id=1, name=devops], TagRef[id=2, name=study]]
[tags] resolved [study, sql]
[notes] created note 2 for user 2 tags=[TagRef[id=1, name=study], TagRef[id=3, name=sql]]
[search] notes request: http://notes:8080/notes/search?q=docker&user_id=1&tag=
[search] found 1 notes, facets=[Facet[tag=study, count=1], Facet[tag=devops, count=1]]
[notes] updated note 1
[notes] deleted note 2
```

## Сохранение данных после рестарта БД

```
docker compose restart db
note 1: Docker basics v2
users: 1
tags: 4
```

## Содержимое базы

```bash
docker compose exec db psql -U postgres -d app -c "select * from users"
docker compose exec db psql -U postgres -d app -c "select * from notes"
docker compose exec db psql -U postgres -d app -c "select * from tags"
docker compose exec db psql -U postgres -d app -c "select * from note_tags"
```
