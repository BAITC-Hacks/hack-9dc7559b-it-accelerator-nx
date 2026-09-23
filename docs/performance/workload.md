# Нагрузка D1: профиль v1 для PERF-02

Статус: **planning / не запускался / требуется согласование D2 и D3**. Это входные данные для нагрузочного runner, не результат теста и не обещание 5k RPS. SLO взяты из [единого ТЗ](../ekt-assistant-spec.md); [1000](../../tests/load/profiles/1000.json), [3000](../../tests/load/profiles/3000.json), [5000](../../tests/load/profiles/5000.json) используют одинаковый mix. Профили — JSON-конфигурация будущего runner D3, исполняемого runner здесь нет.

## Mix и сценарий

| Доля total HTTP | Запрос |
|---:|---|
| 28% | история сообщений |
| 25% | статус run |
| 10% | создать turn |
| 15% | открыть/reconnect SSE |
| 5% | прочитать сохранённый result set/offer |
| 5% | подготовить proposal |
| 6% | прочитать корзину |
| 3% | отдельно подтвердить proposal |
| 1% | сверить исход cart operation |
| 2% | загрузить attachment (endpoint D2 ещё не передан) |
| **100%** | **все HTTP-запросы API** |

SSE chunks/heartbeat не входят в RPS; open/reconnect входят. Auth, создание разговоров и preseed выполняются до измеряемого интервала и считаются отдельно. Идентификаторы и proposals берутся из успешных предыдущих действий того же principal; чужие ресурсы и истёкшие proposals не служат способом получить быстрый pass. 3% confirm используют актуальные предложения; остальные proposals остаются без подтверждения. Профиль нельзя молча уменьшить до реализованных endpoints: до подключения upload и D2 data полный AC-16 заблокирован.

На каждый 1k RPS: 12 000 предварительно созданных principals, по два разговора и 30 сообщений; principals выбираются равномерно, один активный run на разговор. 20% клиентов держат две вкладки. В среднем 1.5 SSE opens на turn (включая reconnect), 1.2 одновременных streams на активный turn. Предполагаемое **среднее**, не p95, время turn — 5с: при 100/300/500 turns/s закон Литтла даёт 500/1500/2500 активных turns и около 600/1800/3000 streams. Это исходные предположения для mock, реальные средние необходимо измерить.

Corpus для будущего benchmark: 50 000 SKU, 150 000 warehouse offers, 200 KB документов / 20 000 chunks, 400 валидных вложений. Реальные текущие contract fixtures содержат два товара и один источник; они не заменяют этот corpus. Запрос: среднее 120 / p95 800 / max 8000 символов. Отдельный search query ограничен 2000. Attachments: 40% Excel, 25% Word, 25% PDF, 10% JPEG; средний размер 2MiB, p95 9MiB, max 10MiB, среднее 3 страницы, p95 20. Размеры и ingestion SLO должны подтвердить ATT/QA-02; numerical ingestion pass gate пока не определён. На 1k/3k/5k upload traffic уже составляет 40/120/200 MiB/s.

## Provider и tool budget

Model rounds: 25% × 1, 55% × 2, 15% × 3, 5% × 4; среднее **2 calls/turn**, максимум 4. На call: input mean 1800 / p95 5000, output mean 180 / p95 300 tokens; типовой финальный ответ ≤300 tokens. Это synthetic distribution; реальные prompt/tool/schema tokens входят в usage. Текущий worker ограничивает каждый output budget 700 tokens и общий run 16 000 tokens, следовательно runner не должен генерировать невозможные сочетания хвостов.

| Total HTTP RPS | Turns/s | Generation calls/min | Input+output tokens/min | Embedding calls/s | Vision calls/s | Catalog calls/s | Stock calls/s | Cart writes/s |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1000 | 100 | 12000 | 23760000 | 50 | 12 | 80 | 130 | 30 |
| 3000 | 300 | 36000 | 71280000 | 150 | 36 | 240 | 390 | 90 |
| 5000 | 500 | 60000 | 118800000 | 250 | 60 | 400 | 650 | 150 |

Формулы: generation RPM = R × 0.10 × 2 × 60; TPM = generation RPM × (1800+180). Embedding = 0.5/turn, vision = 0.6/upload (с учётом страниц); catalog = 0.8/turn; stock = 0.5/turn + 0.3/turn agent proposal + один check на HTTP proposal (5% R); cart writes = HTTP confirm (3% R). Дополнительные partner revalidation, retries, ingestion embeddings и vision tokens считаются отдельно: их budget **неизвестен**, таблица — нижняя плановая оценка, не полный provider invoice. Knowledge retrieval 0.2/turn, analogs 0.15/turn, reviewed attachment 0.1/turn; query cache hits 30%, LLM response cache 0%. Требуется измерить фактический call multiplier и повторные запросы.

У аккаунта не проверены generation/embedding/vision RPM/TPM, model access, approved spend и partner limits. Planned monetary budget = generation input/output tokens × согласованная цена + embedding/vision/partner расходы; без фактических quotas, цен и бюджета числовую сумму не назначаем. Несколько API keys не увеличивают общую quota организации. Live run не разрешён самим существованием JSON-профиля.

## SLO и evidence

Open-arrival: прогрев 5мин, затем 15мин на каждом 1k → 3k → 5k, после этого 60мин soak на 5k; 60с peak 120% — отдельный overload/recovery эксперимент, не критерий capacity. Сохранять offered/admitted/completed/rejected/dropped; считать все 5xx и timeout. Любая потерянная arrival проваливает capacity run, даже если latency оставшихся хорошая.

| Проверка | Gate |
|---|---|
| history / status | p95 ≤200мс, p99 ≤500мс |
| HTTP acceptance | p95 ≤300мс; 202 не является ответом ассистента |
| queue wait | p95 ≤1с |
| TTFT полезного текста от submit | p95 ≤3с; heartbeat/status/card без текста отдельно |
| completed типовой ответ ≤300 output tokens от submit | p95 ≤8с |
| unexpected 5xx / offered HTTP | <0.1% |
| admission rejected / offered turns | ≤0.1% |
| successful terminal runs / admitted turns | ≥99% при здоровых зависимостях |

Mock должен держать реальные задержки, connections, usage и tool rounds. Текущий `ContractLlm` мгновенный и для capacity непригоден. Mock прохождение доказывает только предел приложения при заданной модели провайдера. Ограниченный live OpenAI test запускается отдельно после проверки quota/budget; partner compatibility — отдельно от sample cart.

Defaults backend: 60 global model calls/min, 10 principal calls/min, 200000 reserved tokens/min, 4 provider calls одновременно, 4 worker threads, очередь 100, deadline 60с, 100 SSE subscribers/replica, DB pool 30. Это dev-защита; с ними эти load profiles **не пройдут**. PERF-02 задаёт и фиксирует approved sizing/env/число реплик, DB pool sum, Redis memory/event retention, LB buffering/timeouts и лимиты ОС. `EventJournal` сейчас читает PostgreSQL; Redis содержит replicated Streams. Учесть polling DB каждые 200мс на stream. Метрики Prometheus защищены ADMIN; отдельного scrape identity в D1 нет, D3 должен настроить защищённый доступ.

Evidence для functional release: build, clean/upgrade DB, owner isolation, idempotency, proposal/confirm/recovery и реальный widget по QA-01. Evidence для high-load: версия профиля и corpus, commit, env/replicas, генератор и его отсутствие dropped arrivals, p95/p99/ошибки, queue/SSE/provider/tool usage, traces, crash/reconnect/Redis failure и partner/quota reports. **Live OpenAI, partner API, frontend E2E, high-load и deploy health сейчас не подтверждены этим документом.**
