import { Link } from 'react-router-dom';
import { useCommerce } from '../../hooks/useCommerce';
import { ProductCard } from './ProductCard';
import { ProposalCard } from '../cart/ProposalCard';
import { formatQuantity, scaled, validQuantity } from './decimal';
import type { CommerceScenario, SelectionView } from '../cart/model';

const scenarios: { value: CommerceScenario; label: string }[] = [
  { value: 'normal', label: 'Подтверждение успешно' }, { value: 'stock-changed', label: 'Остаток 12 → 7' },
  { value: 'price-changed', label: 'Изменение цены' }, { value: 'unknown-outcome', label: 'Потеря ответа: статус неизвестен' },
  { value: 'stale-cart', label: 'Изменение версии корзины' }, { value: 'offer-unavailable', label: 'Остаток недоступен' }, { value: 'not-found', label: 'Предложение не найдено' },
];
export function CommercePanel({ conversation }: { conversation: string }) {
  const { driver, view } = useCommerce();
  if (view.mode !== 'demo') return null;
  const selection = view.selections[conversation];
  const amount = selection?.quantity ?? '20';
  const unknown = view.proposals.some((item) => item.state === 'outcome_unknown');
  const disabled = view.busy || unknown;
  const choose = (patch: Partial<SelectionView>) => driver.select(conversation, { ...(selection ?? { mode: 'split', productKey: 'demo-original', quantity: amount }), ...patch });
  const original = view.products.find((product) => product.key === 'demo-original');
  const requested = scaled(amount, 3);
  const stock = original?.stock === null || !original ? null : scaled(original.stock, 3);
  const used = view.cart?.lines.filter((line) => line.productKey === 'demo-original').reduce((sum, line) => sum + (scaled(line.quantity, 3) ?? 0n), 0n) ?? 0n;
  const available = stock === null ? null : stock > used ? stock - used : 0n;
  const first = requested !== null && available !== null ? (requested < available ? requested : available) : null;
  const split = first !== null && requested !== null ? `${first / 1000n} + ${(requested - first) / 1000n}` : 'остаток неизвестен';
  return <section className="commerce-panel" aria-label="Учебный каталог и предложения">
    <div className="commerce-section-heading"><div><span className="commerce-eyebrow">Подбор на примере</span><h2>Товары и комплектация</h2></div><Link to="/cart">Корзина →</Link></div>
    <p className="commerce-notice">Синтетические цены, остатки и документы. Это фиксированный учебный набор, не результаты поиска по вашему запросу и не реальные предложения ekt.kz.</p>
    <div className="product-grid">{view.products.map((product) => <ProductCard key={product.key} product={product} sources={view.sources}
      selected={selection?.mode === 'single' && selection.productKey === product.key} disabled={disabled}
      onSelect={() => choose({ mode: 'single', productKey: product.key })} />)}</div>
    <div className="analogue-comparison"><h3>Сравнение A и B · задано учебными данными</h3><p>Совпадают: 3 полюса, 40 А, 400 В. Различаются: отключающая способность 10 / 20 кА и цена. Совместимость здесь задана fixture, не проверена для реального оборудования.</p><p>Серия C не является аналогом: 1 полюс, 16 А; цена и остаток неизвестны.</p></div>
    <div className="selection-builder"><label>Нужное количество, шт.<input aria-label="Количество товара" inputMode="numeric" value={amount} maxLength={8} disabled={disabled} onChange={(event) => choose({ quantity: event.target.value.replace(',', '.') })} /></label>
      <p>Количество относится к новому добавлению. Уже сохранённая корзина не меняется.</p>
      <div className="choice-grid" role="group" aria-label="Выберите комплектацию">
        <button className="commerce-choice" aria-pressed={selection?.mode === 'split'} disabled={disabled || stock === null} onClick={() => choose({ mode: 'split' })}><strong>Сохранить исходный + аналог</strong><span>A + B: {split} шт.</span></button>
        <button className="commerce-choice" aria-pressed={selection?.mode === 'replace'} disabled={disabled} onClick={() => choose({ mode: 'replace' })}><strong>Полностью заменить на B</strong><span>{formatQuantity(amount || '0')} шт. аналога</span></button>
      </div>
      {!validQuantity(amount, '1') && <p className="commerce-error" role="status">Укажите целое положительное количество.</p>}
      <button className="commerce-primary" disabled={disabled || !selection || !validQuantity(amount, '1')} onClick={() => driver.prepare(conversation)}>Посмотреть точное предложение</button>
      <small>Выбор варианта не является согласием на добавление.</small>
    </div>
    <label className="commerce-scenario">Следующее подтверждение (демо)<select value={view.scenario} disabled={disabled} onChange={(event) => {
      const scenario = scenarios.find((item) => item.value === event.target.value);
      if (scenario) driver.setScenario(scenario.value);
    }}>{scenarios.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select></label>
    {view.notice && <p className="commerce-notice" role="status">{view.notice}</p>}
    {unknown && <p className="commerce-notice" role="status">Есть операция с неизвестным результатом. Новое добавление заблокировано до запроса статуса.</p>}
    {view.proposals.filter((item) => item.conversationKey === conversation).map((proposal) => <ProposalCard key={proposal.key} proposal={proposal} driver={driver} disabled={view.busy || (unknown && proposal.state !== 'outcome_unknown')} />)}
    {unknown && view.proposals.filter((item) => item.conversationKey !== conversation && item.state === 'outcome_unknown').map((item) => <button key={item.key} className="commerce-secondary" disabled={view.busy} onClick={() => item.operationKey && driver.lookup(item.operationKey)}>Узнать статус операции из другого диалога</button>)}
    <div className="source-links"><Link to="/sources/demo-delivery">Доставка и оплата · учебный FAQ</Link></div>
  </section>;
}
