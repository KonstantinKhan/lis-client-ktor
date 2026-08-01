# Установка единицы измерения (Unit/Measure) на связи "Состоит из ..."

## Контекст

Объекты миграции связаны линками "Состоит из ..." с количественной характеристикой
(`minQuantity`/`maxQuantity`) — уже реализовано в проекте. У связи также есть единица измерения
(`Unit`, например "м2") и величина (`Measure`, например "Площадь"). Задача: по внешним правилам
(источник — вне API; во внешнем файле есть только единица измерения, величины там нет) сравнить
текущий `unitId` связи с целевым и, при расхождении, проставить новое значение через API Loodsman,
либо оставить дефолт, назначенный API при создании связи.

В API нет отдельного поля для прямой установки `idMeasure` на связи — `Measure` не назначается
напрямую, он выводится сервером из выбранного `Unit` (каждый `Unit` принадлежит ровно одной
`Measure`). Чтобы поменять величину связи, меняется `unitId` — `idMeasure`/`measure` на связи
меняются как следствие.

Источник — `swagger.lapis` (корень проекта) + ручная проверка через Swagger UI.

## Цепочка вызовов

| # | Метод | DTO | Назначение |
|---|-------|-----|-----------|
| 1 | `GET /api/v4/ObjectInfo/get-linked-fast` | вход: `idVersion`, `linkType` / выход: `[GetLinkedFastOutputDto_V4]` | текущее состояние связей заданного родителя (baseline для сравнения) |
| 2 | `GET /api/v4/Measure/units-by-designation` | вход: `designation` / выход: `[MeasureUnitOutputDto]` | резолв обозначения единицы из внешнего правила → `idUnit` |
| 2* | `GET /api/v4/MetaData/get-measure-list-for-link` | вход: `parentType`, `childType`, `linkType` / выход: `[GetMeasureListForLinkOutputDto]` | опциональный safety-check: подтвердить, что величина, пришедшая вместе с `Unit`, вообще допустима для этой пары типов + типа связи |
| 3 | — | — | сравнить `idUnit` из шага 1 с `id` из шага 2 |
| 4 | `PUT /api/v4/EditObject/up-link` | `UpLinkInputDto` | применить новый `unitId` (при расхождении) |
| 5 | `GET /api/v4/ObjectInfo/get-linked-fast` | — | верификация результата |

### Шаг 1 — получить текущее состояние связи

```
GET /api/v4/ObjectInfo/get-linked-fast?idVersion=<parentVersionId>&linkType=<linkTypeName>
```

Ответ — `[GetLinkedFastOutputDto_V4]`: массив прямых связей ОДНОГО родителя, отфильтрованных по
типу связи. Каждый элемент содержит `idLink`, `product`, `version`, `minQuantity`, `maxQuantity`,
`idUnit`, `idMeasure`, `unit`, `measure`. Нужный `idLink` находится по `product`/`version`
дочернего объекта.

Это точечный запрос по объекту — заметно меньше "шума", чем у батч-варианта ниже, т.к. не нужно
искать свой объект среди объединённого ответа по группе объектов.

**Альтернатива (batch по многим объектам сразу)**, если понадобится обработать пачку связей за
один вызов, а не по одной:

```
GET /api/v4/ObjectInfo/get-linked-objects-for-objects?objectsIds=<id1,id2,...>&linksTypesIds=<...>&inverse=<bool>
```

Тот же набор данных (`GetLinkedObjectsForObjectsOutputDto_V4`), но на вход даётся список id
объектов; в ответе далее нужно фильтровать по `idParent`/`idChild`. Использовать как опциональный
batch-путь, не основной.

### Шаг 2 — резолв `idUnit` из внешнего правила

```
GET /api/v4/Measure/units-by-designation?designation=<значение из внешнего файла>
```

Ответ — `[MeasureUnitOutputDto]`: `id` (= искомый `idUnit`), `designation`, `measureId`,
`measureName`, `isBasic`, `fromBasicFactor`.

⚠️ Обозначение может быть неуникальным между разными величинами. Если `units-by-designation`
вернул больше одной записи — решение, какую брать, нужно принимать по `measureId`/`measureName`
дополнительным правилом; во внешнем файле пользователя данных о величине нет, поэтому это
открытый вопрос (см. ниже), а не то, что можно разрешить автоматически без дополнительных данных.

**Опциональный safety-check** (не обязательный шаг основного потока):

```
GET /api/v4/MetaData/get-measure-list-for-link?parentType=<...>&childType=<...>&linkType=<...>
```

Ответ — `[GetMeasureListForLinkOutputDto]` (`idMeasure`, `name`, `default`) — список величин,
допустимых для данной пары типов + типа связи. Можно свериться, что `measureId` из шага 2 входит
в этот список.

### Шаг 3 — сравнить с текущим значением

`idUnit` из шага 1 vs `id` (unit) из шага 2.
- Равны → пропустить связь, оставить дефолт API.
- Не равны → шаг 4.

### Шаг 4 — применить новое значение

```
PUT /api/v4/EditObject/up-link
```

`UpLinkInputDto`, реальный проверенный payload:

```json
{
  "parentType": "Сборочная единица",
  "parentProduct": "078.505.9.0100.00",
  "parentVersion": "3.0",
  "childType": "Материал по КД",
  "childProduct": "Лента М-0,5х20 ГОСТ 3560-73",
  "linkId": 26318,
  "minQuantity": 2,
  "maxQuantity": 2,
  "unitId": "VA5801DBC0F8F4E6FADEB252E9BEC29A1",
  "delLink": false,
  "linkType": "Состоит из ..."
}
```

⚠️ **`delLink` обязательно `false` явно** — если не передать `false` (или передать `true`),
связь удаляется.

`minQuantity`/`maxQuantity` рекомендуется всегда эхо́ить текущими значениями из шага 1 (если
правило их не меняет) — в проверенном на практике payload они идут вместе с `unitId` в одном и том
же вызове, отдельного PATCH только для единицы измерения нет.

### Шаг 5 — верификация

Повторный `GET /api/v4/ObjectInfo/get-linked-fast` на того же родителя — сверить `idUnit`/`unit`/
`idMeasure`/`measure` по нужному `idLink` с ожидаемым результатом.

## Открытые вопросы / на что обратить внимание

- **Коллизия `designation`** в `Measure/units-by-designation` между разными величинами — при
  отсутствии данных о величине во внешнем файле разрешать неоднозначность нечем; нужно либо
  дополнительное правило, либо считать коллизии редким случаем и обрабатывать вручную/с ошибкой.
- **`MetaData/get-measure-list-for-link`** как safety-проверка — опциональна, не блокирует
  основной поток; включать по решению на этапе реализации.
- **`childVersion`** присутствует в `UpLinkInputDto` как опциональное поле, но в проверенном на
  практике payload не понадобился.

## Источник

`swagger.lapis`, методы:
- `ObjectInfo/get-linked-fast`
- `ObjectInfo/get-linked-objects-for-objects` (batch-альтернатива)
- `Measure/units-by-designation`
- `MetaData/get-measure-list-for-link` (опциональный safety-check)
- `EditObject/up-link`

Документ справочный, кода на Kotlin не содержит и не меняет рабочий код проекта.
