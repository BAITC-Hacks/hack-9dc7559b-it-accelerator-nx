# AI-чат + RAG: план задач

Основание: [архитектура](ai-chat-architecture.md). Все задачи ниже **запланированы**, не реализованы. Пользователь подтвердил 1–5 тыс. **суммарных API RPS**, а не такое же число вызовов LLM.

Приоритеты: P0 — ядро безопасного рабочего чата; P1 — полноценный MVP и проверка нагрузки; P2 — после измерений. Размеры S/M/L — относительные: S примерно до половины инженерного дня, M 1–2 дня, L 2–4 дня; это ориентиры с запасом на интеграцию, не обещание сроков. 5-часовой demo требует сокращённого объёма задач, описанного в конце.

## P0: зафиксировать решения

### CHAT-01 — Нагрузочный и продуктовый контракт

P0 · S · ответственный: tech lead + product · зависимости: нет.

**Goal:** сделать 1–5k RPS проверяемым требованием.

**Context:** total API RPS известен; доля AI turns, token sizes, corpus size, guest identity, sustained/peak пока неизвестны.

**Constraints:** считать HTTP requests, активные SSE и LLM RPM/TPM отдельно; учитывать embeddings и ingestion. Не объявлять benchmark успешным за счёт отказа большинству клиентов.

**Done when:** записаны endpoint mix для 1k/3k/5k, размеры payload/corpus, длительность peak/soak, SLO и accepted/rejected/completed критерии, доступные OpenAI quota/budget; определено, кто загружает документы и кто их видит. Начальные гипотезы помечены до измерения.

### CHAT-02 — Spike OpenAI/Spring AI/SSE

P0 · S · backend + frontend · зависит от CHAT-01.

**Goal:** выбрать один рабочий provider/streaming путь.

**Context:** Spring AI 1.0.0 закреплён; frontend generated client имеет fetch-based SSE.

**Constraints:** не обновлять весь стек без необходимости. Не редактировать generated код. DTO независимы от провайдера; секрет только backend env.

**Done when:** ADR фиксирует Responses adapter либо demo ChatClient/Chat Completions; подтверждены streaming, usage, timeout/cancellation и совместимые зависимости. Browser spike через реальный API proxy доказывает доставку deltas и bearer/session auth. Проверены generated event types, terminal event и политика retry; определена стратегия storage/history.

## P0: основа backend

### CHAT-03 — Identity, workspace и ownership

P0 · M · backend · зависит от CHAT-01.

**Goal:** изолировать разговоры и документы.

**Context:** сейчас `.anyRequest().permitAll()`; login сайта ещё не интегрирован.

**Constraints:** principal/workspace выводить из проверенного JWT/session. Для публичного widget — ограниченная server-issued visitor identity; произвольный ownerId из body не доверенный. Сначала выбрать одну auth-схему и согласовать CORS/CSRF для неё.

**Done when:** защищены chat/run/events/document/source routes; тесты A/B не позволяют читать/подписываться/отменять чужой run и использовать чужой документ. Admin upload отделён от guest read/chat. REST и SSE реально передают и проверяют identity; cache очищается при logout.

### CHAT-04 — Модель хранения и миграции

P0 · M · backend · зависит от CHAT-01.

**Goal:** хранить разговор, сообщение, run, документы, versions/chunks/citations и jobs.

**Context:** V1 только включает vector; схема предложена в архитектуре.

**Constraints:** добавлять V2+; DTO не JPA entities; unique idempotency/payload hash, sequence и один active run/conversation обеспечиваются БД. Не держать транзакцию во время LLM.

**Done when:** миграции проходят на новой **изолированной** БД; concurrent duplicate requests создают один run; конфликт payload/active run даёт определённую ошибку; cursor queries имеют индексы и проверены на репрезентативных данных.

### CHAT-05 — OpenAPI контракт чата и документов

P0 · M · backend + frontend · зависит от CHAT-02, CHAT-03, CHAT-04.

**Goal:** согласовать API до параллельной реализации UI и workers.

**Context:** нужны conversation/history/send/run/status/events/cancel и upload/status/source/delete.

**Constraints:** DTO + Jakarta validation + ProblemDetail; описать `202`, `409`, `429`, `503`, SSE union, Last-Event-ID и replay_unavailable. Не принимать system prompt, model name или ACL filter от обычного клиента.

**Done when:** springdoc отдаёт схемы и security; `npm run gen` создаёт корректные types/sdk; frontend компилируется; contract fixtures показывают accepted/delta/terminal/citation/error. Клиентские HTTP-типы не написаны вручную.

### CHAT-06 — Разговоры, история и идемпотентная отправка

P0 · M · backend · зависит от CHAT-03, CHAT-04, CHAT-05.

**Goal:** создать conversation и надёжно принять сообщение.

**Context:** HTTP принятие и генерация имеют разную длительность.

**Constraints:** acceptance после durable commit, bounded queue; один активный run в разговоре; страницы истории ограничены, сортировка стабильна. Повтор существующего idempotency result возвращается до нового quota reservation/проверки active run; гонка retries не удерживает лишнюю reservation.

**Done when:** создание/список/история работают после reload; одинаковая отправка возвращает те же IDs; одновременные отправки/разные payload с одним ключом обработаны; response содержит runId и не ждёт LLM.

### CHAT-07 — Run worker, leases, cancel и recovery

P0 · L · backend · зависит от CHAT-04, CHAT-06.

**Goal:** доводить принятые runs до явного terminal state.

**Context:** в MVP jobs в PostgreSQL, worker в том же приложении с bounded executor.

**Constraints:** короткие claim/update транзакции, lease/fencing, ограничение concurrent jobs и total deadline. Не перезапускать автоматически provider call с неизвестным исходом; at-least-once job delivery не означает exactly-once billing.

**Done when:** queued run подхватывается после рестарта; конкурирующие workers не коммитят разные результаты; cancel/completion гонка даёт один terminal state; stale epoch не публикует deltas после cancel/recovery; зависшие и outcome_unknown runs видимы как failure, partial сохраняется по принятой политике.

### CHAT-08 — LlmGateway и контекст

P0 · M · backend/AI · зависит от CHAT-02, CHAT-07; RAG подключается после CHAT-12.

**Goal:** один ограниченный вызов OpenAI с историей и предоставленными источниками.

**Context:** model default в проекте — gpt-4o-mini; модель окончательно выбирается eval.

**Constraints:** versioned system prompt, bounded context/output, хранить полную UI-историю отдельно от model context; не дублировать memory из нескольких механизмов. Provider call вне JPA transaction; stream не собирать целиком до показа пользователю.

**Done when:** доступны mock и выбранный реальный adapter; проверены usage, TTFT, token limit, timeout/429/5xx, cancellation, no-answer и безопасный error mapping. В логах нет ключа/сырого prompt. Model/таймауты/бюджеты управляются конфигурацией.

### CHAT-09 — Доставка SSE и replay policy

P0 · M · backend · зависит от CHAT-05, CHAT-07, CHAT-08.

**Goal:** передавать deltas, sources и lifecycle существующего run.

**Context:** клиент подписывается после принятия сообщения; worker может начать раньше подписки.

**Constraints:** subscription не создаёт run; buffer ограничен; terminal event публикуется после commit результата. MVP одна реплика, explicit fallback к persisted snapshot при недоступном replay; общий журнал — CHAT-18.

**Done when:** initial subscribe не теряет начало ответа; seq/eventId упорядочены; EOF без terminal event не считается success; slow client не исчерпывает heap; reconnect, buffer expiry и worker restart дают документированное поведение; heartbeat проходит через реальный proxy.

## P0: RAG

### CHAT-10 — Загрузка и хранение документов

P0 · M · backend · зависит от CHAT-03, CHAT-04, CHAT-05.

**Goal:** принять разрешённый документ и создать durable ingestion job.

**Context:** первый формат TXT/Markdown; PDF/OCR отдельное расширение.

**Constraints:** admin/owner ACL, upload/extracted-size limits, object keys генерирует сервер. Job создаётся атомарно с metadata; cleanup orphan objects определён. Никаких произвольных URL-fetch из запроса для MVP.

**Done when:** valid upload получает documentId/status; запрещённый формат/oversize отвергается; оригинал доступен через ACL-protected source route; повтор загрузки обрабатывается по checksum в разрешённом scope; storage/job failures видны пользователю.

### CHAT-11 — Ingestion: extract → chunk → embed → publish

P0 · L · backend/AI · зависит от CHAT-10, CHAT-02.

**Goal:** сделать ready-документ доступным поиску с версией и координатами источников.

**Context:** pgvector есть только как extension; требуется собственный JdbcTemplate retrieval/write adapter, chunk schema и parser/chunker, Spring AI EmbeddingModel.

**Constraints:** async bounded jobs, limits/retry/lease; фиксированные dimensions/model/metric, Flyway управляет schema. Chunking/top-k — гипотезы для eval. Ingestion не забирает весь quota budget чата.

**Done when:** upload→ready проходит полностью; перезапуск не дублирует chunks; ошибка embedding не публикует половину новой версии; поиск использует old ready version до atomic publish. Поздний worker не отменяет tombstone/новую версию; citation v1 открывает immutable оригинал v1 после публикации v2; chunk metadata содержит source/version/location.

### CHAT-12 — Retrieval, ACL и citations

P0 · M · backend/AI · зависит от CHAT-03, CHAT-08, CHAT-11.

**Goal:** отвечать на основе релевантных доступных документов.

**Context:** первая версия использует один retrieval + один generation call; exact search baseline, HNSW после измерений.

**Constraints:** ACL применяется сервером перед prompt; client filter не заменяет его; только active ready versions. Модель видит документы как данные, не инструкции. Source IDs сверяются с retrieval allowlist и текущим доступом.

**Done when:** вопросы/уточнения получают правильные snippets и citations; отсутствие/конфликт основания отражается в ответе; чужие/deleted/unready chunks исключены; документ с prompt injection не меняет доступ. Grounding оценивается отдельно от валидности source ID.

## P0/P1: интерфейс

### CHAT-13 — Экран разговоров и streaming UX

P0 · L · frontend · зависит от CHAT-05; интеграция с CHAT-06, CHAT-09, CHAT-12.

**Goal:** новый чат, история, отправка, stream, Stop, retry, просмотр sources.

**Context:** сейчас HomePage только ping; generated fetch SSE обходит axios interceptor.

**Constraints:** shared auth provider для REST/SSE; TanStack Query для server data, ограниченный local buffer для deltas. Повтор submit использует прежний idempotency key; subscription retry никогда не повторяет mutation. Generated files не менять.

**Done when:** reload восстанавливает историю/run; disconnect → bounded reconnect с event dedup либо status snapshot; 401 прекращает retries, 429 учитывает согласованную политику. EOF без terminal event не «готово». Stop вызывает cancel API. Безопасный Markdown, ссылки только на разрешённые источники; переход между чатами не смешивает текст.

### CHAT-14 — UI документов

P1 · M · frontend · зависит от CHAT-05, CHAT-10, CHAT-11.

**Goal:** загрузить источник и понять, когда он готов для RAG.

**Context:** загрузка доступна только выбранной роли; admin UX можно отделить от публичного widget.

**Constraints:** показывать upload/extract/embed/ready/failed; status polling только пока нужно, с backoff, без бесконечных запросов всех вкладок. Скрытая кнопка не заменяет server ACL.

**Done when:** upload с лимитами и ошибками; failed ingestion можно повторить допустимым способом; ready-документ доступен для выбора/поиска; source открывается по авторизации; удаление сразу исключает его из новых retrieval.

## P1: готовность к эксплуатации и нагрузке

### CHAT-15 — Admission, budget и retries

P0 базовые limits / P1 распределённые · M · backend · зависит от CHAT-01, CHAT-07, CHAT-08, CHAT-11.

**Goal:** не принять больше платных работ, чем можно выполнить в пределах deadline и бюджета.

**Context:** quota OpenAI отличается от throughput нашего API; embeddings имеют отдельную нагрузку.

**Constraints:** per-principal/workspace и global RPM/TPM/concurrency, bounded queue age/length; один retry owner, backoff/jitter/Retry-After. При неизвестном budget state новые paid runs не запускать; history не блокировать limiter outage.

**Done when:** перегруз даёт быстрый понятный отказ до принятия; известные квоты соблюдаются; retry storm отсутствует; измеряются rejected/admitted/completed. Ограничения проверены и при двух репликах перед production scaling.

### CHAT-16 — Автоматическая проверка RAG и изоляции

P1 · M · backend/AI + QA · зависит от CHAT-11, CHAT-12.

**Goal:** менять chunking/model/prompt на основании качества.

**Context:** одних красивых ответов на demo недостаточно для проверки источников.

**Constraints:** golden set отделить от вопросов для настройки; минимум 50 примеров, versioned corpus/prompts. Включить follow-ups, no-answer, конфликт версий, языки продукта, cross-owner и injection. Ручная rubric дополняет метрики.

**Done when:** отчёт показывает Recall@5 на answerable subset, grounding/citation correctness, no-answer поведение, latency/tokens; достигнуты согласованные gates; регрессия ACL останавливает релиз. Оценка не утверждает безопасность вне проверенного набора.

### CHAT-17 — Наблюдаемость и end-to-end тесты

P1 · M · full stack/QA · зависит от CHAT-06…CHAT-13, CHAT-15.

**Goal:** видеть, где тратятся время/токены и почему failed run не завершился.

**Context:** сейчас Actuator health/info и application DEBUG, но бизнес-метрик нет.

**Constraints:** route-template labels вместо IDs, чувствительные данные не логировать; mock provider для CI. Проверки миграций на отдельной БД, shared volume не удалять.

**Done when:** есть API latency, active SSE/runs, queue age, TTFT, retrieval, token/error/pool метрики и trace/request IDs. Integration/browser suite проверяет send→stream→citation→reload, duplicate submit, 401/429, cancel, restart и ingestion failure. Backend build, frontend build/lint и tests запускаются в CI.

### CHAT-18 — Несколько реплик и общий SSE journal

P1 · L · backend/infra · зависит от CHAT-09, CHAT-15, CHAT-17.

**Goal:** масштабировать независимо короткие API, streaming и фоновые работы.

**Context:** local in-memory replay работает только в demo с одной репликой.

**Constraints:** Redis для глобальных quotas и bounded event journal; PostgreSQL — durable результат/jobs. Sticky sessions не заменяют recovery. Рабочие роли могут быть отдельными процессами одного артефакта; broker добавлять по результатам нагрузки.

**Done when:** POST на A, SSE на B, reconnect на C работают; повтор событий не дублирует текст; Redis restart/TTL expiry ведёт к snapshot/status fallback; worker lease защищён от stale writes. Drain и rolling deploy имеют проверенную политику для accepted runs и SSE.

### CHAT-19 — Benchmark 1k → 3k → 5k

P1 · L · performance/infra · зависит от CHAT-01, CHAT-16, CHAT-17, CHAT-18.

**Goal:** доказать capacity для оговорённого API mix.

**Context:** mock benchmark собственного контура и реальная доступность OpenAI — разные результаты.

**Constraints:** open arrival rate; realistic DB/corpus/auth и mock generation streaming timings, затем ограниченный real-provider прогон. Отдельно считать SSE и embedding workload. Не запускать нагрузку на shared demo/production.

**Done when:** 15 минут на каждой ступени и 60 минут soak на target; SLO и error/admission gates выполнены, dropped arrivals учтены, очередь стабильна, память bounded. Зафиксированы hardware/replicas, bottlenecks, provider quota/budget и отличие mock от real результатов; есть failure/reconnect/restart сценарии.

### CHAT-20 — Deployment и конфигурационный контракт

P1 · M · infra · зависит от CHAT-02, CHAT-05; финальная приёмка после CHAT-17…CHAT-19.

**Goal:** воспроизводимо запускать demo и production-профиль.

**Context:** deployment URL/manifests отсутствуют в изученных файлах; host bootRun не подхватывает корневой .env автоматически.

**Constraints:** новые env одновременно в `.env.example` и compose; secrets только deployment secret store. Не уничтожать shared pgdata. Проверить реальные LB timeouts/buffering/connection limits выбранной платформы; frontend VITE_API_URL — build-time.

**Done when:** документирован host startup с env, есть CI/deploy/rollback и проверенный URL; build/health/ping/chat smoke проходят. Для isolated clean-start корректно задаются project name, container names и порты. Миграции, backup/restore, resource limits и graceful shutdown проверены в выделенном окружении.

### CHAT-21 — Удаление, retention и версионирование

P1 · M · backend · зависит от CHAT-10, CHAT-11, CHAT-12, CHAT-15.

**Goal:** исключать устаревшие/удалённые данные без утечек через retrieval/cache/source.

**Context:** originals, chunks, history, citations, snapshots и event buffers имеют разные сроки жизни.

**Constraints:** revoke-first, cleanup-after; ACL/version в cache key; retry cleanup идемпотентен. Отдельно определить судьбу ранее сохранённого ответа при удалении документа.

**Done when:** delete сразу исключает новые retrieval и запрещает source; object/chunks/cache cleanup повторяем; неготовая переиндексация не ломает старый corpus; политика retention и provider storage записана и проверена тестами.

## P2: расширения после MVP

### CHAT-22 — Улучшения только по измеренной проблеме

P2 · размер после spike · backend/AI + product · зависит от CHAT-16, CHAT-19.

**Goal:** улучшить качество или UX там, где baseline не проходит rubric.

**Context:** кандидаты — text PDF, OCR, hybrid retrieval/reranking, query rewrite, summary длинной истории.

**Constraints:** каждое улучшение — отдельная задача/PR с baseline сравнения; учитывать дополнительные LLM calls, latency и переиндексацию. Tool actions вынести в отдельный продуктовый scope, не добавлять автономный loop автоматически.

**Done when:** выбранная мера улучшает конкретную метрику на held-out set и укладывается в budget/SLO; есть rollout/rollback; результаты сохранены рядом с eval.

## Порядок работы и параллельность

Критический путь: **01 → 02 → 03/04 → 05 → 06/10 → 07/11 → 08/12 → 09 → интеграция 13 → 16/17 → 18/19**. Базовые limits из 15 нужны до любого публичного demo; 20 начинать рано, после первого вертикального среза. 21 завершить до использования приватных документов в полноценном MVP.

После CHAT-05 можно работать параллельно в отдельных worktrees:

- Backend A: chat lifecycle, provider, SSE — CHAT-06…09.
- Backend/AI B: documents, ingestion, retrieval — CHAT-10…12.
- Frontend: chat/documents по contract fixtures — CHAT-13/14.
- Infra/QA по доступности команды: limits, observability, deployment/eval — CHAT-15…20.

Общая БД и порты не являются независимыми окружениями: отдельные project/container names и порты либо согласованное reuse. Не менять применённую миграцию соседа; номера миграций распределить заранее.

## Отдельный timebox demo: 5 часов

Это урезанный вертикальный срез для 2–3 разработчиков, не выполнение всего backlog:

1. **0:00–0:30:** scope/identity/corpus, contract fixtures, выбор stream adapter; проверить запуск и provider access.
2. **0:30–1:30:** минимальные таблицы, owner checks, conversation/send; frontend shell с историей параллельно. Базовые input/concurrency/output limits обязательны.
3. **1:30–2:30:** несколько заранее выбранных TXT/Markdown через ingestion pipeline, retrieval с sources; при необходимости загрузка через admin API без UI.
4. **2:30–3:30:** streaming generation, сохранение ответа, citations и simple failure/cancel; одна backend replica, reload восстанавливает persisted state, потерю in-flight честно показывать.
5. **3:30–4:30:** интеграция и 10 demo-вопросов, чужой ID, no-answer и отказ OpenAI; build/lint/smoke, deployment.
6. **4:30–5:00:** резерв на проблемы и репетицию. Нагрузочные claims ограничить фактически измеренным; полный 1–5k gate — CHAT-19.

## Общий Done для implementation PR

- Один reviewable vertical slice; Conventional Commit в короткой ветке от main, зелёная сборка до merge.
- Backend `./gradlew build`; frontend `npm run build` и `npm run lint`; дополнительные тесты соответствуют изменённому поведению.
- API изменился → `npm run gen`, типы и SDK используются фронтом; generated files не редактировались вручную.
- Новые конфигурационные переменные синхронны в application, `.env.example`, compose и deployment. В текущем планировании новые env не введены.
- Demo path smoke проверен; clean DB проверяется в изолированном окружении, без удаления общей БД.
- Никаких секретов/сырых документов в git или обычных логах; ограничения и ещё не подтверждённая capacity описаны честно.
