# Frontend / UI-01

## Экран чата / UI-02

На `/` и `/widget` подготовлен новый экран: диалоги, история, composer, состояния
ответа, Stop/recovery, известные параметры подбора и продолжения «дешевле / первые
два / 20 штук». Прежняя ping-страница доступна на `/status`.

Для самостоятельного просмотра **когда будет разрешён запуск** из этого worktree:

```bash
cd /private/tmp/hackalem-ui01.NF4q1E/frontend
npm run dev:mock
# открыть http://localhost:5174
```

Это локальный UI simulator с явной меткой демо. Он не вызывает LLM/Cart/API,
не утверждает реальные цены/остатки/совместимость и не заменяет будущие
schema-validated HTTP fixtures. В обычном dev/production до подключения
backend отображается интерфейс с недоступной отправкой, а не fake success.

Реализованы на уровне UI:

- отдельный draft для каждого диалога, sessionStorage истории в текущей вкладке;
- сохранение local submission key и partial reply, ручное продолжение после reload;
- Stop локального playback, независимые буферы диалогов, очистка при смене auth;
- reducer отбрасывает повторные/устаревшие обновления, gap переводит в recovery;
- контролируемые сценарии disconnect, duplicate, expiry→snapshot, 401, 429 и
  потерянный ответ на отправку; они выбираются в шапке только демо-режима;
- постраничное отображение истории, сохранение позиции при чтении/догрузке;
- Enter/Shift+Enter/IME, мобильный drawer, контекст и safe-area composer;
- `renderMessageExtras` в MessageTimeline и `attachmentAction` в Composer —
  точки подключения карточек UI-03 и вложений UI-04.

`components/chat/model.ts` описывает только локальное состояние отображения.
`ChatDriver` — frontend boundary, не ручной HTTP client. В `hooks/useChatWorkspace`
сейчас подключается только dev-only demo driver. Live driver должен использовать
generated endpoints и TanStack Query (`lib/chat-query-keys.ts`), а public DTO
и SSE union — приходить из FOUND-01. Источник terminal, seq/epoch, курсоров и
run snapshot будет серверным; локальное поведение не доказывает server semantics.

UI-02 оставлена `in_progress`: настоящие chat/history/stream/status/cancel,
авторизация, browser acceptance и сборка этого экрана ещё не проверены.
По просьбе пользователя проверки отложены до его сигнала.

Работа ведётся в `codex/ui-01-client-transport`. UI-01 остаётся `in_progress`:
полный контракт FOUND-01 ещё не опубликован. В проверенной `origin/main` на
коммите `a5c0e27` отсутствовал `docs/api/openapi.json`; SDK содержит только ping.
Новые HTTP DTO, ChatEvent union и springdoc snapshot вручную не создаются.

## Реализованная подготовительная часть

- `src/lib/api.ts` настраивает общий generated client и источник session token.
- `src/lib/transport.ts`: REST через axios и SSE через fetch получают актуальный
  Bearer token. Ответ 401 очищает только ту сессию, из которой отправлен запрос.
  При смене сессии отменяются текущие запросы и очищается QueryClient, включая
  mutation cache. Недоступность localStorage в iframe допускает сессию в памяти.
- `src/lib/stream.ts` принимает функцию открытия **generated** SSE endpoint.
  Тип событий выводится из неё; `isTerminal` задаётся по будущему ChatEvent union.
  `session` обязателен: смена посетителя останавливает в том числе reconnect.
  Abort подписки не вызывает cancel run API. Отдельная кнопка Stop — задача UI-02.
- Reconnect восстанавливает `Last-Event-ID`, допускает не более трёх подключений
  за подписку, включая первое. EOF без terminal event является обрывом.
  После исчерпания попыток UI-02 должен получить run snapshot через generated API.
  seq/epoch, dedup событий и snapshot fallback подключаются после FOUND-01.
- Временная политика: retry только для сетевых ошибок и 429/502/503/504;
  backoff с jitter, Retry-After в секундах либо HTTP-date, предел ожидания 30 с.
  Если сервер просит ждать дольше, автоматический retry прекращается.
  401/403/409 и validation errors не повторяются. Mutation retries отключены.
  Политику должен подтвердить D1 при публикации ошибок/лимитов.

Прямые REST-запросы не повторяются транспортом: retry чтений принадлежит TanStack
Query. Generated SSE helper не управляет повторами — это делает `streamEvents`.
Обрабатывать исключение истощённого reconnect как необходимость восстановления,
а не как успешное завершение ответа. Ошибки авторизации требуют новой сессии.

## Режимы

```bash
npm run dev       # настоящий backend, VITE_API_URL как раньше
npm run dev:mock  # отдельный порт 5174, явные HTTP fixtures
```

Mock entry загружается только при `DEV && MODE === 'mock'`. Файл Service Worker
отдаётся dev middleware Vite из установленного MSW; в `public/` его нет.
Production build не подключает mock entry. Неописанные API-запросы в mock mode
дают ошибку вместо обращения к живому backend. Static assets/HMR пропускаются.

Сейчас fixture покрывает только существующий PingResponse через `satisfies`.
`sseResponse` формирует SSE wire format из generated StreamEvent и payload DTO.
Тестовые `/__transport_test__/…` пути используются только для проверки транспорта
и не являются обещанием публичного backend API. Товарные, cart и attachment
fixtures будут добавлены по фактическим типам FOUND-01 и данным DATA-01.

## После handoff от D1

Выполнять генерацию и проверки после разрешения владельца задачи:

```bash
npm run gen -- --input ../docs/api/openapi.json
npm run gen:check
npm run gen                         # обычная генерация из localhost backend
npm run gen:check -- --input http://localhost:8080/v3/api-docs
npm test
npm run build
npm run lint
```

`gen:check` генерирует код во временный каталог и сравнивает все файлы с
`src/client/`, не перезаписывая его. Отсутствующий snapshot или drift даёт
ненулевой exit code; фиктивная схема для зелёной проверки не используется.
После API merge всегда регенерировать SDK, не разрешать generated conflicts руками.

Нужны от D1/D2: springdoc snapshot и contract-profile запуск, security declarations
для REST **и SSE**, ChatEvent discriminator/terminal/replay semantics, ProblemDetail
и Retry-After policy, session bootstrap/expiry, ProductDetails/OfferSnapshot,
ProposalSnapshot/CartSnapshot (включая conflict и outcome_unknown), AttachmentResult
и review variants. ID и Money/Quantity — строки согласно единому ТЗ.

## Статус проверки

До просьбы пользователя отложить проверки проходили 12 транспортных тестов,
frontend build и lint. После добавления browser mock entry, SSE fixture helper и
этой документации повторные проверки **не выполнялись** по просьбе пользователя.
Генерация полного SDK, schema validation business fixtures, browser mocks и
интеграция с backend ожидают FOUND-01 и сигнала на проверку. Это не статус `done`.
