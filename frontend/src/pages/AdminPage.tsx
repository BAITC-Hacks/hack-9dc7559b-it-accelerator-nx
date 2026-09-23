import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  getModelUsage, ingest, job, jobStatus, quotes, reindex, revoke, startImport, update,
  type CatalogImportRequest, type ImportRequest, type Job, type UpdateOffer,
} from '../client';
import { catalogJobId, isJobPending, parseCatalogImport, sourceHref } from '../lib/knowledge';
import { JsonDetails, ServiceError } from './service-ui';

const exampleCatalog: CatalogImportRequest = {
  schemaVersion: 1, synthetic: true, version: 'manual-demo-v1',
  products: [{
    id: 'manual-item-1', article: '009901', name: 'Демонстрационный товар', brand: 'Demo',
    category: 'test', unit: 'pcs', minimum: 1, step: 1, price: 1000, currency: 'KZT',
    specs: {}, certificates: [], synthetic: true, sourceVersion: 'manual-demo-v1',
    warehouses: [{ warehouseId: 'WH-ALMATY', availableQuantity: 20, status: 'IN_STOCK', eligible: true }],
  }],
};
const uuidPattern = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';

function KnowledgeJob({ jobId }: { jobId: string }) {
  const cache = useQueryClient();
  const result = useQuery({
    queryKey: ['admin-knowledge-job', jobId],
    queryFn: async ({ signal }) => (await job({ path: { id: jobId }, signal, throwOnError: true })).data,
    enabled: Boolean(jobId),
    refetchInterval: (query) => query.state.error ? false : isJobPending(query.state.data?.state) ? 10_000 : false,
  });
  useEffect(() => {
    if (result.data?.state === 'SUCCEEDED') void cache.invalidateQueries({ queryKey: ['knowledge-search'] });
  }, [cache, result.data?.state]);
  if (!jobId) return null;
  const item = result.data;
  return <div aria-live="polite">
    {result.isPending && <p role="status">Получаем состояние индексации…</p>}
    <ServiceError error={result.error} />
    {item && <><p>Индексация: <strong>{item.state}</strong>{isJobPending(item.state) ? ' · обновляется автоматически' : ''}</p>
      <dl className="service-ids"><dt>Задание</dt><dd>{item.id}</dd><dt>Документ</dt><dd>{item.documentId}</dd><dt>Версия</dt><dd>{item.versionId}</dd></dl>
      {item.errorCode && <p className="service-error">{item.errorCode}</p>}
      {item.state === 'SUCCEEDED' && item.documentId && item.versionId && <Link to={sourceHref(item.documentId, item.versionId)}>Открыть опубликованный текст</Link>}
      <JsonDetails value={item} />
    </>}
    <button type="button" disabled={result.isFetching} onClick={() => void result.refetch()}>Обновить состояние индексации</button>
  </div>;
}

function CatalogImportPanel() {
  const cache = useQueryClient();
  const [text, setText] = useState('');
  const [fileError, setFileError] = useState<unknown>();
  const [confirmed, setConfirmed] = useState(false);
  const [key, setKey] = useState<string>(() => crypto.randomUUID());
  const [lookup, setLookup] = useState('');
  const [jobId, setJobId] = useState('');
  const imported = useMutation({
    mutationFn: async () => (await startImport({ body: parseCatalogImport(text), headers: { 'Idempotency-Key': key }, throwOnError: true })).data,
    onSuccess: (data) => { setJobId(data.id ?? ''); setLookup(data.id ?? ''); },
  });
  const result = useQuery({
    queryKey: ['admin-catalog-job', jobId],
    queryFn: async ({ signal }) => (await jobStatus({ path: { id: catalogJobId(jobId) }, signal, throwOnError: true })).data,
    enabled: Boolean(jobId),
    refetchInterval: (query) => query.state.error ? false : isJobPending(query.state.data?.status) ? 10_000 : false,
  });
  useEffect(() => {
    if (result.data?.status === 'SUCCEEDED') void cache.invalidateQueries({ predicate: (query) => !String(query.queryKey[0]).startsWith('admin-') });
  }, [cache, result.data?.status]);
  return <section className="service-card"><h2>Импорт каталога</h2>
    <p className="service-notice">Успешный импорт заменяет активную версию каталога целиком. Для сохранения всех товаров загрузите полную выгрузку.</p>
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); imported.mutate(); }}>
      <label>JSON-файл каталога<input type="file" accept=".json,application/json" onChange={(event) => {
        const file = event.target.files?.[0]; setFileError(undefined);
        if (file) void file.text().then((value) => { setText(value); setConfirmed(false); setKey(crypto.randomUUID()); }).catch(setFileError);
      }} /></label>
      <label>Содержимое JSON<textarea className="service-json" rows={11} value={text} onChange={(event) => { setText(event.target.value); setConfirmed(false); }} required placeholder='{"schemaVersion":1,"version":"manual-v1","products":[...]}' /></label>
      <div className="service-actions"><button type="button" onClick={() => { setText(JSON.stringify(exampleCatalog, null, 2)); setConfirmed(false); setKey(crypto.randomUUID()); }}>Заполнить примером из одного товара</button></div>
      <label>Ключ повторной отправки<input value={key} onChange={(event) => setKey(event.target.value)} required /></label>
      <p className="service-muted">Повтор с тем же ключом возвращает то же задание. Для другой выгрузки используйте новый ключ.</p>
      <label className="service-check"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />Загруженная выгрузка должна стать активным каталогом.</label>
      <div className="service-actions"><button type="submit" disabled={!confirmed || imported.isPending}>{imported.isPending ? 'Отправляем…' : 'Импортировать каталог'}</button><button type="button" onClick={() => setKey(crypto.randomUUID())}>Новый ключ</button></div>
    </form>
    <ServiceError error={fileError ?? imported.error} />
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); if (jobId === lookup.trim()) void result.refetch(); else setJobId(lookup.trim()); }}>
      <label>ID задания каталога<input value={lookup} onChange={(event) => setLookup(event.target.value)} inputMode="numeric" pattern="[0-9]+" required placeholder="1" /></label>
      <div><button type="submit" disabled={result.isFetching}>Проверить импорт</button></div>
    </form>
    {result.isFetching && <p role="status">Получаем состояние импорта…</p>}
    <ServiceError error={result.error} />
    {result.data && <div aria-live="polite"><p>Импорт: <strong>{result.data.status}</strong> · Товаров: {result.data.importedProducts ?? 0} / {result.data.totalProducts ?? 0}</p>
      <p className="service-muted">Версия источника: {result.data.sourceVersion} · Векторный индекс: {result.data.embeddingStatus ?? '—'}</p>
      {result.data.errors?.map((error, index) => <p className="service-error" key={index}>{error.path}: {error.message} ({error.code})</p>)}
      {result.data.warnings?.map((warning, index) => <p className="service-notice" key={index}>{warning.path}: {warning.message} ({warning.code})</p>)}
      <JsonDetails value={result.data} />
    </div>}
  </section>;
}

function KnowledgeAdminPanel() {
  const cache = useQueryClient();
  const [jobId, setJobId] = useState('');
  const [lookup, setLookup] = useState('');
  const [documentId, setDocumentId] = useState('');
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  function queued(data: Job) { setJobId(data.id ?? ''); setLookup(data.id ?? ''); setDocumentId(data.documentId ?? ''); }
  const importing = useMutation({ mutationFn: async (body: ImportRequest) => (await ingest({ body, throwOnError: true })).data, onSuccess: queued });
  const indexing = useMutation({ mutationFn: async () => (await reindex({ path: { id: documentId.trim() }, throwOnError: true })).data, onSuccess: queued });
  const revoking = useMutation({
    mutationFn: async () => revoke({ path: { id: documentId.trim() }, throwOnError: true }),
    onSuccess: () => { setConfirmRevoke(false); void cache.invalidateQueries({ queryKey: ['knowledge-search'] }); void cache.invalidateQueries({ queryKey: ['knowledge-source'] }); },
  });
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    importing.mutate({
      externalId: String(form.get('externalId')).trim(), title: String(form.get('title')).trim(), version: String(form.get('version')).trim(),
      visibility: form.get('visibility') === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC',
      text: String(form.get('text')), sourceUrl: String(form.get('sourceUrl')).trim() || undefined,
      tags: String(form.get('tags')).split(',').map((tag) => tag.trim()).filter(Boolean),
      synthetic: form.get('synthetic') === 'on', semanticIndex: form.get('semanticIndex') === 'on',
    });
  }
  return <section className="service-card"><h2>Документы базы знаний</h2><p className="service-muted">Новая версия публикуется после индексации. До этого поиск использует предыдущую готовую версию.</p>
    <form className="service-form" onSubmit={submit}>
      <div className="service-fields"><label>Внешний ID документа<input name="externalId" defaultValue="manual-demo-terms" pattern="[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}" required /></label><label>Версия<input name="version" defaultValue="v1" maxLength={100} required /></label></div>
      <label>Название<input name="title" defaultValue="Учебные условия доставки" maxLength={300} required /></label>
      <div className="service-fields"><label>Доступ<select name="visibility" defaultValue="PUBLIC"><option value="PUBLIC">Все пользователи</option><option value="PRIVATE">Только владелец документа</option></select></label><label>Теги через запятую<input name="tags" defaultValue="доставка, условия" /></label></div>
      <label>Адрес оригинала, если есть<input name="sourceUrl" type="url" placeholder="https://example.org/terms" maxLength={2000} /></label>
      <label>Текст документа<textarea name="text" rows={6} required maxLength={200000} defaultValue="Учебные условия: доставка по Алматы занимает 2 рабочих дня. Стоимость доставки — 1500 KZT." /></label>
      <div className="service-actions"><label className="service-check"><input name="synthetic" type="checkbox" defaultChecked />Учебный документ</label><label className="service-check"><input name="semanticIndex" type="checkbox" />Семантическая индексация</label></div>
      <div><button type="submit" disabled={importing.isPending}>{importing.isPending ? 'Отправляем документ…' : 'Загрузить и индексировать'}</button></div>
    </form>
    <ServiceError error={importing.error} />
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); setJobId(lookup.trim()); }}>
      <label>ID задания индексации<input value={lookup} onChange={(event) => setLookup(event.target.value)} placeholder="UUID задания" pattern={uuidPattern} required /></label><div><button type="submit">Открыть состояние</button></div>
    </form>
    <KnowledgeJob jobId={jobId} />
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); indexing.mutate(); }}>
      <h3>Управление документом</h3><label>ID документа<input value={documentId} onChange={(event) => { setDocumentId(event.target.value); setConfirmRevoke(false); revoking.reset(); }} placeholder="UUID из задания или результата поиска" pattern={uuidPattern} required /></label>
      <div><button type="submit" disabled={indexing.isPending || revoking.isPending}>{indexing.isPending ? 'Запускаем…' : 'Переиндексировать'}</button></div>
      <label className="service-check"><input type="checkbox" checked={confirmRevoke} onChange={(event) => setConfirmRevoke(event.target.checked)} />Отозвать документ: убрать из поиска и закрыть доступ к его версиям.</label>
      <div><button className="service-danger" type="button" disabled={!confirmRevoke || !new RegExp(`^${uuidPattern}$`).test(documentId.trim()) || revoking.isPending || indexing.isPending} onClick={() => revoking.mutate()}>{revoking.isPending ? 'Отзываем…' : 'Отозвать документ'}</button></div>
    </form>
    <ServiceError error={indexing.error ?? revoking.error} />
    {revoking.isSuccess && <p role="status">Документ отозван. Его источники больше недоступны.</p>}
  </section>;
}

function OffersPanel() {
  const cache = useQueryClient();
  const [article, setArticle] = useState('000123');
  const [warehouse, setWarehouse] = useState('');
  const [price, setPrice] = useState('');
  const [available, setAvailable] = useState('');
  const [version, setVersion] = useState('');
  const current = useMutation({
    mutationFn: async () => (await quotes({ path: { article: article.trim() }, throwOnError: true })).data,
    onSuccess: (data) => {
      const quote = data.find((item) => item.warehouse === warehouse) ?? data[0];
      if (quote?.offer) { setWarehouse(quote.warehouse ?? ''); setVersion(quote.offer.version ?? ''); setPrice(quote.offer.price?.amount ?? ''); setAvailable(quote.offer.available?.value ?? ''); }
    },
  });
  const saving = useMutation({
    mutationFn: async (body: UpdateOffer) => (await update({ path: { article: article.trim(), warehouse: warehouse.trim() }, body, throwOnError: true })).data,
    onSuccess: (data) => { const item = data.find((quote) => quote.warehouse === warehouse); setVersion(item?.offer?.version ?? ''); void cache.invalidateQueries({ predicate: (query) => !String(query.queryKey[0]).startsWith('admin-') }); },
  });
  return <section className="service-card"><h2>Тестовые цены и остатки</h2><p className="service-muted">Изменяет sample-предложение. Версия защищает от перезаписи более свежих данных; цены и остатки влияют на новые предложения корзины.</p>
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); saving.mutate({ price, available, expectedVersion: version }); }}>
      <div className="service-fields"><label>Артикул<input value={article} onChange={(event) => { setArticle(event.target.value); setVersion(''); current.reset(); saving.reset(); }} required /></label><label>Склад<input value={warehouse} onChange={(event) => { setWarehouse(event.target.value); setVersion(''); }} required /></label></div>
      <div><button type="button" disabled={!article.trim() || current.isPending} onClick={() => current.mutate()}>{current.isPending ? 'Загружаем…' : 'Получить текущие предложения'}</button></div>
      <div className="service-fields"><label>Цена<input inputMode="decimal" pattern="[0-9]+([.][0-9]+)?" value={price} onChange={(event) => setPrice(event.target.value)} required /></label><label>Доступное количество<input inputMode="decimal" pattern="[0-9]+([.][0-9]+)?" value={available} onChange={(event) => setAvailable(event.target.value)} required /></label></div>
      <label>Ожидаемая версия предложения<input value={version} onChange={(event) => setVersion(event.target.value)} required /></label>
      <div><button type="submit" disabled={saving.isPending || !version}>{saving.isPending ? 'Сохраняем…' : 'Обновить предложение'}</button></div>
    </form>
    <ServiceError error={current.error ?? saving.error} />
    {saving.isSuccess && <p role="status">Цена и остаток обновлены.</p>}
    {(saving.data ?? current.data) && <JsonDetails value={saving.data ?? current.data} label="Предложения по складам" />}
  </section>;
}

function ModelUsagePanel() {
  const [input, setInput] = useState('');
  const [runId, setRunId] = useState('');
  const result = useQuery({
    queryKey: ['run-model-usage', runId],
    queryFn: async ({ signal }) => (await getModelUsage({ path: { id: runId }, signal, throwOnError: true })).data,
    enabled: Boolean(runId),
  });
  return <section className="service-card"><h2>Использование модели</h2><p className="service-muted">ID запуска показан в чате. Доступны только запуски текущей сессии.</p>
    <form className="service-form" onSubmit={(event) => { event.preventDefault(); if (runId === input.trim()) void result.refetch(); else setRunId(input.trim()); }}><label>ID запуска<input value={input} onChange={(event) => setInput(event.target.value)} pattern={uuidPattern} required placeholder="UUID запуска" /></label><div><button type="submit" disabled={result.isFetching}>{result.isFetching ? 'Загружаем…' : 'Показать расход'}</button></div></form>
    <ServiceError error={result.error} />
    {result.data && <>{!result.data.length ? <p>Данные использования пока не записаны.</p> : <div className="service-table-wrap"><table className="service-table"><thead><tr><th>Раунд</th><th>Модель</th><th>Входящие токены</th><th>Исходящие токены</th><th>Измерено</th></tr></thead><tbody>{result.data.map((usage, index) => <tr key={index}><td>{usage.round}</td><td>{usage.model}</td><td>{usage.inputTokens ?? '—'}</td><td>{usage.outputTokens ?? '—'}</td><td>{usage.measured ? 'Да' : 'Нет'}</td></tr>)}</tbody></table></div>}<JsonDetails value={result.data} /></>}
  </section>;
}

export default function AdminPage() {
  return <main className="service-page"><header><Link to="/">← Вернуться в чат</Link><nav><Link to="/knowledge">База знаний</Link><Link to="/session">Сессия</Link></nav></header>
    <h1>Управление данными</h1><p>Импорт каталога, публикация документов и проверка фоновых заданий.</p>
    <p className="service-notice">Изменение данных требует роли ADMIN, назначенной на сервере. Гостевая сессия не получает эту роль автоматически. <Link to="/session">Подключить выданную сессию</Link>.</p>
    <CatalogImportPanel /><KnowledgeAdminPanel /><OffersPanel /><ModelUsagePanel />
  </main>;
}
