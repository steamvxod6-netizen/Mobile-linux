# MobileLinux

Мини-терминал на Java для Android (запускается в JavaDroid, Termux и любой среде с Java 11+).
Ранняя версия — возможны ошибки.

A small terminal written in Java for Android (JavaDroid, Termux, or anything with Java 11+). Early version — bugs are possible.

## Запуск / Run

```
java MobileLinux.java
```

В Termux сначала: `pkg install openjdk-17`.

## Команды / Commands

| Команда | Что делает |
|---|---|
| `creato <файл>` | создаёт файл (любое расширение: `.py`, `.js`, `.cpp`, `.java`…) и открывает редактор |
| `go <папка или файл>` | войти в папку или открыть файл; `go ..` — выйти на уровень выше; `go` — в начальную папку |
| `download pkt python` | устанавливает пакет python (Jython 2.7.4) |
| `download pkt javascript` | устанавливает пакет javascript (Rhino 1.7.15) |
| `start python <файл.py>` | ищет файл и запускает его |
| `start javascript <файл.js>` | ищет файл и запускает его (`start js …` тоже работает) |
| `python <файл.py>` | запускает Python напрямую |
| `search [слово]` | поиск пакетов |
| `list` | установленные пакеты |
| `show <пакет>` | информация о пакете |
| `remove <пакет>` | удалить пакет |
| `help` | список команд |
| `exit` | выход |

## Редактор creato

Внизу экрана всегда есть подсказка.

- **`,,`** (две запятые) — включить режим команд
- в режиме команд: **Q** — сохранить, **X** — выйти, **N** — новый файл
- **`..`** (две точки) — вернуться к тексту
- Работают также стрелки, Home, End, PgUp, PgDn

## Пакеты

Пакеты скачиваются с Maven Central (`repo1.maven.org`), нужен интернет. Если рядом лежит
файл пакета (`jython-standalone*.jar` или `rhino-1*.jar`), он будет использован вместо скачивания.

- **python** — [Jython](https://www.jython.org/) 2.7.4. Это **Python 2.7** на Java: `print("текст")`
  работает, f-строк нет. Если в файле есть не-латинские буквы, первой строкой добавь:
  `# -*- coding: utf-8 -*-`
- **javascript** — [Rhino](https://mozilla.github.io/rhino/) 1.7.15 (JavaScript на Java). Вывод —
  через `console.log("текст")` (внутри Rhino есть ещё `print`).

## Лицензия / License

MIT, см. файл `LICENSE`. Jython и Rhino распространяются на собственных условиях
(Rhino — MPL 2.0); они не входят в этот репозиторий и скачиваются отдельно.
