import { Link } from 'react-router-dom';
import type { ProductView, SourceView } from '../cart/model';
import { formatMoney, formatQuantity } from './decimal';

export function ProductCard({ product, sources, selected, disabled, onSelect }: {
  product: ProductView; sources: readonly SourceView[]; selected: boolean; disabled: boolean; onSelect: () => void;
}) {
  const known = product.price !== null && product.stock !== null && product.freshness === 'fresh';
  return <article className={`product-card ${selected ? 'is-selected' : ''}`}>
    <div className="product-card-top"><span className="commerce-eyebrow">Учебный товар</span><span className={`offer-status ${known ? '' : 'unknown'}`}>{known ? 'Демо-данные' : 'Нужно уточнить'}</span></div>
    <span className="product-article">{product.article}</span><h3>{product.title}</h3>
    <dl className="product-specs">{product.specs.map((spec) => <div key={spec.label}><dt>{spec.label}</dt><dd>{spec.value}</dd></div>)}</dl>
    <p className="product-price">{formatMoney(product.price, product.currency)} <small>/ {product.unit}</small></p>
    <p>{product.stock === null ? 'Остаток неизвестен' : `Остаток: ${formatQuantity(product.stock)} ${product.unit}`} · шаг {formatQuantity(product.quantityStep)}</p>
    <small>{product.warehouse} · оффер v{product.offerVersion}<br />{product.freshness === 'stale' ? 'Данные устарели · ' : ''}<time dateTime={product.observedAt}>{new Date(product.observedAt).toLocaleString('ru-RU')}</time></small>
    <div className="source-links">{product.certificateKeys.length ? product.certificateKeys.map((key) => {
      const source = sources.find((item) => item.key === key && item.kind === 'certificate');
      return source ? <Link to={`/sources/${encodeURIComponent(key)}`} key={key}>{source.title} · {source.version}</Link> : <span key={key}>Документ недоступен</span>;
    }) : <span>Подтверждённых документов нет</span>}</div>
    <button className="commerce-secondary" aria-pressed={selected} disabled={disabled || !known} onClick={onSelect}>{selected ? 'Выбран · корзина не изменена' : known ? 'Выбрать товар' : 'Недоступен для предложения'}</button>
  </article>;
}
