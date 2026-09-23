import { useEffect, useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import * as sdk from '../../client/sdk.gen';
import type { Option, ProductResponse, Quote, Result, SavedSearch, SearchCriteria } from '../../client/types.gen';
import { useCommerce } from '../../hooks/useCommerce';
import { commerceError, commerceRead, commerceWrite, productView } from '../../lib/commerce-live';
import { ProposalCard } from '../cart/ProposalCard';
import { formatMoney, formatQuantity, validQuantity } from './decimal';
import type { ProductView } from '../cart/model';

export function LiveCatalogPanel({ conversation }: { conversation?: string }) {
  const { driver, view } = useCommerce();
  const [localConversation, setActive] = useState(conversation ?? (() => { try { return sessionStorage.getItem('hackalem.active-conversation') ?? ''; } catch { return ''; } })());
  const active = conversation ?? localConversation;
  const [query, setQuery] = useState(''); const [category, setCategory] = useState(''); const [brand, setBrand] = useState(''); const [maxPrice, setMaxPrice] = useState(''); const [minPrice, setMinPrice] = useState(''); const [exact, setExact] = useState(false);
  const [method, setMethod] = useState('get'); const [saved, setSaved] = useState<SavedSearch>(); const [browsed, setBrowsed] = useState<ProductResponse[]>(); const [checked, setChecked] = useState<number[]>([]); const [comparison, setComparison] = useState<SavedSearch>();
  const [selected, setSelected] = useState<ProductView>(); const [quantity, setQuantity] = useState('1'); const [warehouse, setWarehouse] = useState('');
  const [details, setDetails] = useState<ProductResponse>(); const [quotes, setQuotes] = useState<Quote[]>([]); const [alternatives, setAlternatives] = useState<Result>(); const [chosen, setChosen] = useState<Option>(); const [notice, setNotice] = useState<string>(); const [certificateUrl, setCertificateUrl] = useState<string>();
  useEffect(() => { if (conversation) { void driver.hydrateConversation?.(conversation); } }, [conversation, driver]);
  useEffect(() => () => { if (certificateUrl) URL.revokeObjectURL(certificateUrl); }, [certificateUrl]);
  const action = useMutation({ mutationFn: (fn: () => Promise<void>) => fn(), onError: error => setNotice(commerceError(error)) });
  const run = (fn: () => Promise<void>) => { setNotice(undefined); action.mutate(fn); };
  const busy = action.isPending || view.busy;
  const products = saved ? (saved.resultSet?.products ?? []).map(p => productView(p, saved.resultSet?.offers?.find(o => o.article === p.article))) : browsed ? browsed.map(p => productView({ id: p.id, article: p.article, name: p.name, specs: p.specs })) : view.products;
  async function ensureConversation() {
    if (active) return active;
    const created = await commerceWrite(() => sdk.createConversation({ throwOnError: true })); if (!created.id) throw new Error('Backend не вернул диалог.');
    setActive(created.id); sessionStorage.setItem('hackalem.active-conversation', created.id); window.dispatchEvent(new CustomEvent('hackalem:conversation', { detail: { id: created.id } })); return created.id;
  }
  async function find(event: FormEvent) { event.preventDefault(); run(async () => {
    const criteria: SearchCriteria = { query: query.trim(), category: category || undefined, brand: brand || undefined, minPrice: minPrice || undefined, maxPrice: maxPrice || undefined, exactArticle: exact, limit: 20 };
    const params = { q: query.trim(), category: category || undefined, brand: brand || undefined, minPrice: minPrice ? Number(minPrice) : undefined, maxPrice: maxPrice ? Number(maxPrice) : undefined, exactArticle: exact, limit: 20 };
    setSelected(undefined); setDetails(undefined); setAlternatives(undefined); setChosen(undefined); setChecked([]); setComparison(undefined);
    if (method === 'products') { const result = await commerceRead(['browse', criteria], () => sdk.search2({ query: params, throwOnError: true })); setSaved(undefined); setBrowsed(result.items ?? []); setNotice(result.warnings?.join(' ')); }
    else { const result = method === 'post' ? await commerceWrite(() => sdk.search1({ body: criteria, throwOnError: true })) : await commerceRead(['search', criteria], () => sdk.search({ query: params, throwOnError: true })); setSaved(result); setBrowsed(undefined); setNotice(result.warnings?.join(' ')); }
  }); }
  async function choose(product: ProductView) {
    setSelected(product); setAlternatives(undefined); setChosen(undefined); setCertificateUrl(undefined);
    const [card, offers] = await Promise.all([commerceRead(['product', product.article], () => sdk.byArticle({ path: { article: product.article }, throwOnError: true })), commerceRead(['offers', product.article], () => sdk.quotes({ path: { article: product.article }, throwOnError: true }))]);
    setDetails(card); setQuotes(offers); setQuantity(card.minimumQuantity ?? '1'); setWarehouse(offers.find(q => q.offer)?.warehouse ?? '');
  }
  const selectedQuote = quotes.find(q => q.warehouse === warehouse && q.offer);
  const increment = selectedQuote?.offer?.available?.step ?? details?.stepQuantity ?? '1';
  async function prepare() {
    const id = await ensureConversation();
    if (chosen?.id) { await driver.prepareSelection?.(id, { fulfillmentOptionId: chosen.id, lines: chosen.lines ?? [] }); return; }
    if (!selected || !selectedQuote?.offer) throw new Error('Выберите товар и склад с подтверждённым предложением.');
    const result = saved?.resultSet?.products?.some(p => p.article === selected.article) ? saved : await commerceWrite(() => sdk.search1({ body: { query: selected.article, exactArticle: true, limit: 1 }, throwOnError: true }));
    await driver.prepareSelection?.(id, { resultSetId: result?.resultSet?.id, lines: [{ article: selected.article, addQuantity: quantity, unit: selectedQuote.offer.available?.unit, warehouse }] });
  }
  const unknown = view.proposals.some(p => p.state === 'outcome_unknown');
  return <section className="commerce-panel live-catalog" aria-label="Каталог и предложения">
    <div className="commerce-section-heading"><div><span className="commerce-eyebrow">Каталог API</span><h2>Поиск и подбор товаров</h2></div><Link to="/cart">Корзина →</Link></div>
    <form className="catalog-search" onSubmit={find}>
      <label className="catalog-query">Название или артикул<input required value={query} onChange={e => setQuery(e.target.value)} placeholder="Например: автомат C16 или 000001" /></label>
      <div className="catalog-filters"><label>Категория<input value={category} onChange={e => setCategory(e.target.value)} placeholder="breakers" /></label><label>Бренд<input value={brand} onChange={e => setBrand(e.target.value)} /></label><label>Цена от<input inputMode="decimal" value={minPrice} pattern="[0-9]+([.][0-9]+)?" onChange={e => setMinPrice(e.target.value)} /></label><label>Цена до<input inputMode="decimal" value={maxPrice} pattern="[0-9]+([.][0-9]+)?" onChange={e => setMaxPrice(e.target.value)} /></label></div>
      <div className="commerce-actions"><label className="catalog-toggle"><input type="checkbox" checked={exact} disabled={method === 'products'} onChange={e => setExact(e.target.checked)} />Точный артикул</label><label>Режим поиска<select value={method} onChange={e => setMethod(e.target.value)}><option value="get">Каталог · быстрый поиск</option><option value="post">Каталог · фильтры</option><option value="products">Обзор товаров</option></select></label><button className="commerce-primary" disabled={busy || !query.trim()}>Найти</button></div>
    </form>
    {busy && <p role="status">Запрашиваем данные…</p>}{notice && <p role="status" className="commerce-notice">{notice}</p>}{view.notice && <p role="status" className="commerce-notice">{view.notice}</p>}
    {saved && <div className="commerce-actions"><small>Поиск: {saved.mode} · найдено {products.length}</small><button className="commerce-secondary" disabled={busy || !saved.resultSet?.id} onClick={() => run(async () => { setSaved(await commerceRead(['saved', saved.resultSet!.id], () => sdk.result({ path: { id: saved.resultSet!.id! }, throwOnError: true }))); })}>Обновить сохранённую выдачу</button><button className="commerce-secondary" disabled={busy || checked.length < 2} onClick={() => run(async () => { setComparison(await commerceWrite(() => sdk.compare({ path: { id: saved.resultSet!.id! }, body: { indices: checked }, throwOnError: true }))); })}>Сравнить выбранные ({checked.length})</button></div>}
    {(saved || browsed) && !products.length && <p>По заданным условиям товары не найдены. Измените запрос или фильтры.</p>}
    <div className="product-grid">{products.map((product, index) => <article className={`product-card ${selected?.article === product.article ? 'is-selected' : ''}`} key={product.key}>
      <div className="product-card-top"><span className="product-article">{product.article}</span>{saved && <label className="catalog-toggle"><input type="checkbox" aria-label={`Сравнить ${product.article}`} checked={checked.includes(index)} disabled={!checked.includes(index) && checked.length >= 10} onChange={e => setChecked(e.target.checked ? [...checked, index] : checked.filter(i => i !== index))} />Сравнить</label>}</div>
      <h3>{product.title}</h3><dl className="product-specs">{product.specs.slice(0, 5).map(s => <div key={s.label}><dt>{s.label}</dt><dd>{s.value}</dd></div>)}</dl>
      <p className="product-price">{formatMoney(product.price, product.currency)}</p><p>{product.stock === null ? 'Уточните цену и остаток в карточке' : `Доступно ${formatQuantity(product.stock)} ${product.unit} · ${product.warehouse}`}</p>
      <button className="commerce-secondary" disabled={busy} onClick={() => run(() => choose(product))}>Карточка, склады и аналоги</button>
    </article>)}</div>
    {comparison && <section className="analogue-comparison"><h3>Сравнение товаров</h3><div className="catalog-table-scroll"><table><thead><tr><th>Параметр</th>{comparison.resultSet?.products?.map(p => <th key={p.id}>{p.name}<br /><small>{p.article}</small></th>)}</tr></thead><tbody>{[...new Set(comparison.resultSet?.products?.flatMap(p => Object.keys(p.specs ?? {})))].map(key => <tr key={key}><th>{key}</th>{comparison.resultSet?.products?.map(p => <td key={p.id}>{p.specs?.[key] ?? 'Нет данных'}</td>)}</tr>)}</tbody></table></div></section>}
    {selected && details && <section className="catalog-detail"><div className="commerce-section-heading"><h3>{details.name}</h3><button className="commerce-secondary" onClick={() => setSelected(undefined)}>Закрыть карточку</button></div><p>{details.article} · {details.brand} · {details.category}</p>{details.synthetic && <p className="commerce-notice">Тестовый каталог: ассортимент и цены синтетические.</p>}
      <dl className="product-specs">{Object.entries(details.specs ?? {}).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>
      <div className="catalog-filters"><label>Склад<select value={warehouse} onChange={e => { setWarehouse(e.target.value); setChosen(undefined); }}>{quotes.map(q => <option key={q.warehouse} value={q.warehouse} disabled={!q.offer}>{q.warehouse} · {q.offer ? `${q.offer.available?.value} ${q.offer.available?.unit} · ${q.offer.price?.amount} ${q.offer.price?.currency}` : q.error ?? q.status}</option>)}</select></label><label>Количество<input value={quantity} inputMode="decimal" onChange={e => { setQuantity(e.target.value.replace(',', '.')); setChosen(undefined); setAlternatives(undefined); }} /></label></div>
      <small>Минимум {details.minimumQuantity} · шаг {increment} · версия цены {selectedQuote?.offer?.version ?? 'неизвестна'}</small>
      {!quotes.some(q => q.offer) && <p className="commerce-notice">Нет подтверждённых цен и остатков. Предложение недоступно.</p>}
      {!validQuantity(quantity, increment) && <p className="commerce-error">Укажите положительное количество, кратное шагу.</p>}
      <div className="commerce-actions"><button className="commerce-secondary" disabled={busy || !validQuantity(quantity, increment)} onClick={() => run(async () => { setAlternatives(await commerceRead(['analogs', selected.article, quantity, details.unit], () => sdk.analogs({ path: { article: selected.article }, query: { quantity, unit: details.unit }, throwOnError: true }))); setChosen(undefined); })}>Найти аналоги и комплектации</button><button className="commerce-primary" disabled={busy || unknown || !selectedQuote?.offer || !validQuantity(quantity, increment)} onClick={() => run(prepare)}>Посмотреть точное предложение</button></div>
      <div className="source-links">{details.certificates?.map(c => <button className="commerce-secondary" key={c.id} disabled={busy} onClick={() => run(async () => { const blob = await commerceRead(['certificate', selected.article, c.id], () => sdk.certificate({ path: { article: selected.article, certificateId: c.id! }, responseType: 'blob', throwOnError: true })); setCertificateUrl(URL.createObjectURL(blob)); })}>Сертификат {c.id} · {c.version}</button>)}{certificateUrl && <a href={certificateUrl} target="_blank" rel="noopener noreferrer">Открыть PDF сертификата ↗</a>}</div>
      {alternatives && <section className="analogue-comparison"><h3>Аналоги и варианты отгрузки</h3><p>{alternatives.reasons?.join(' · ')}</p>{!alternatives.options?.length && <p>Подходящих вариантов нет. Измените количество или требования.</p>}{alternatives.analogs?.map(a => <p key={a.article}><strong>{a.article} · {a.name}</strong> — {a.compatibility?.status}<br />{Object.entries(a.compatibility?.differences ?? {}).map(([key, value]) => `${key}: ${value}`).join('; ')}</p>)}<div className="choice-grid">{alternatives.options?.map(o => <button className="commerce-choice" key={o.id} aria-pressed={chosen?.id === o.id} disabled={busy} onClick={() => run(async () => { setChosen(await commerceRead(['option', o.id], () => sdk.option({ path: { id: o.id! }, throwOnError: true }))); })}><strong>{o.kind === 'PARTIAL_REPLACEMENT' ? 'Исходный товар + аналог' : o.kind === 'FULL_ALTERNATIVE' ? 'Полная замена' : 'Комплектация'}</strong><span>{o.lines?.map(l => `${l.article}: ${l.addQuantity} ${l.unit} · ${l.warehouse}`).join(' + ')}</span><small>{o.differences?.join(' · ')}</small></button>)}</div>{chosen && <button className="commerce-primary" disabled={busy || unknown} onClick={() => run(prepare)}>Подготовить выбранную комплектацию</button>}</section>}
    </section>}
    {unknown && <p className="commerce-notice">Есть неподтверждённый результат добавления. Проверьте статус операции в корзине.</p>}
    {view.proposals.filter(p => p.conversationKey === active).map(proposal => <ProposalCard key={proposal.key} proposal={proposal} driver={driver} disabled={busy || (unknown && proposal.state !== 'outcome_unknown')} />)}
  </section>;
}
