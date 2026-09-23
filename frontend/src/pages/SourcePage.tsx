import { Link, useParams } from 'react-router-dom';
import { useCommerce } from '../hooks/useCommerce';
import { SafeMarkdown } from '../components/sources/SafeMarkdown';

export default function SourcePage() {
  const { key } = useParams<{ key: string }>();
  const { view } = useCommerce();
  const source = view.mode === 'demo' ? view.sources.find((item) => item.key === key) : undefined;
  return <main className="commerce-page"><header><Link to="/">← Вернуться в чат</Link><span className="commerce-eyebrow">Источник</span></header>
    {view.mode === 'loading' ? <p role="status">Открываем источник…</p> : source ? <article className="source-document">
      <span className="commerce-eyebrow">Учебный документ · {source.version}</span><h1>{source.title}</h1>
      <p className="commerce-notice">Не является сертификатом или условиями продажи реального товара.</p>
      <SafeMarkdown text={source.content} sources={view.sources} />
    </article> : <><h1>Источник недоступен</h1><p>Документ не найден среди доступных источников текущего режима. После интеграции право доступа проверит backend.</p></>}
  </main>;
}
