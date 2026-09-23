import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { search3, type Search3Data } from '../client';
import { sourceHref } from '../lib/knowledge';
import { JsonDetails, ServiceError } from './service-ui';

const answerLabels: Record<string, string> = {
  ANSWERABLE: 'Найдены подтверждающие источники', NO_ANSWER: 'В источниках нет ответа',
  CONFLICT: 'Источники противоречат друг другу', SOURCE_UNAVAILABLE: 'Источник недоступен',
  CATALOG_REQUIRED: 'Нужны данные каталога',
};

export default function KnowledgePage() {
  const [question, setQuestion] = useState('Какие условия доставки?');
  const [budget, setBudget] = useState(6000);
  const [submitted, setSubmitted] = useState<Search3Data['query']>();
  const results = useQuery({
    queryKey: ['knowledge-search', submitted],
    queryFn: async ({ signal }) => (await search3({ query: submitted!, signal, throwOnError: true })).data,
    enabled: Boolean(submitted),
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    const next = { query: question.trim(), characterBudget: budget };
    if (submitted?.query === next.query && submitted.characterBudget === next.characterBudget) void results.refetch();
    else setSubmitted(next);
  }
  const data = results.data;
  return <main className="service-page">
    <header><Link to="/">← Вернуться в чат</Link><nav><Link to="/admin">Администрирование</Link><Link to="/session">Сессия</Link></nav></header>
    <h1>База знаний</h1><p>Условия покупки, доставки и оплаты с подтверждением из документов.</p>
    <form className="service-card service-form" onSubmit={submit}>
      <label>Вопрос<input value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="Какие способы оплаты доступны?" required maxLength={2000} /></label>
      <label>Объём найденных фрагментов, символов<input type="number" min={1} max={16000} value={budget} onChange={(event) => setBudget(Number(event.target.value))} required /></label>
      <div className="service-actions"><button type="submit" disabled={results.isFetching || !question.trim()}>{results.isFetching ? 'Ищем источники…' : 'Найти в документах'}</button>
        {['Какие условия доставки?', 'Какие способы оплаты?', 'Как вернуть товар?'].map((example) => <button type="button" key={example} onClick={() => setQuestion(example)}>{example}</button>)}
      </div>
    </form>
    <ServiceError error={results.error} />
    {!submitted && <p className="service-muted">Задайте вопрос, чтобы увидеть найденные фрагменты и открыть оригиналы.</p>}
    {results.isFetching && <p role="status">Поиск по доступным документам…</p>}
    {data && !results.isError && <section aria-live="polite">
      <h2>{answerLabels[data.answerability ?? ''] ?? data.answerability ?? 'Результат поиска'}</h2>
      <p>{data.explanation}</p><p className="service-muted">Поиск: {data.retrievalMode ?? '—'} · Фрагментов: {data.chunks?.length ?? 0}</p>
      {!data.chunks?.length && <p className="service-notice">Подтверждающих фрагментов нет. Уточните вопрос или добавьте документ через раздел администрирования.</p>}
      <div className="service-results">{data.chunks?.map((chunk, index) => {
        const allowed = Boolean(chunk.citationId && data.citationAllowlist?.includes(chunk.citationId));
        return <article className="service-card" key={chunk.citationId ?? index}>
          <div className="service-actions"><span className="service-badge">Источник {index + 1}</span>{chunk.synthetic && <span className="service-muted">Учебные данные</span>}</div>
          <h3>{chunk.title ?? 'Документ'} · {chunk.versionLabel ?? 'версия не указана'}</h3>
          <p className="service-muted">{chunk.heading}{chunk.page ? ` · Страница ${chunk.page}` : ''}</p>
          <p className="service-document-text">{chunk.text}</p>
          {allowed && chunk.documentId && chunk.versionId ? <Link to={sourceHref(chunk.documentId, chunk.versionId)}>Открыть полный источник</Link> : <p className="service-notice">Ссылка на источник не подтверждена текущим поиском.</p>}
          <dl className="service-ids"><dt>Документ</dt><dd>{chunk.documentId ?? '—'}</dd><dt>Версия</dt><dd>{chunk.versionId ?? '—'}</dd></dl>
        </article>;
      })}</div>
      <JsonDetails value={data} />
    </section>}
  </main>;
}
