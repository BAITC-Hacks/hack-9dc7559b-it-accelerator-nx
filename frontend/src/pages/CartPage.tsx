import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useCommerce } from '../hooks/useCommerce';
import { LineItems } from '../components/cart/LineItems';
import { ProposalCard } from '../components/cart/ProposalCard';
import { formatMoney } from '../components/catalog/decimal';

export default function CartPage() {
  const { driver, view } = useCommerce();
  useEffect(() => { driver.refreshCart(); }, [driver]);
  const unknown = view.proposals.filter((item) => item.state === 'outcome_unknown');
  return <main className="commerce-page"><header><Link to="/">← Вернуться в чат</Link><span className="commerce-eyebrow">EKT · помощник с выбором</span></header>
    <h1>Ваша корзина</h1><p>Точный состав после подтверждения</p>
    {view.mode === 'loading' ? <p role="status">Открываем корзину…</p> : view.mode === 'unavailable' ? <p role="status">Корзина пока не подключена к backend. Учебные данные доступны только в dev mock-режиме.</p> : <>
      {view.mode === 'demo' ? <p className="commerce-notice">Локальная учебная корзина. Реальный заказ не создаётся.</p> : <p className="commerce-notice">Корзина текущей сессии получена из backend. Добавить позиции можно через <Link to="/catalog">каталог</Link> или проверенный файл.</p>}
      {view.notice && <p role="status">{view.notice}</p>}
      {unknown.map((item) => <div key={item.key} className="commerce-notice"><p>Результат операции неизвестен. Просмотр снимка не заменяет проверку её статуса.</p><button className="commerce-primary" disabled={view.busy} onClick={() => item.operationKey && driver.lookup(item.operationKey)}>Узнать статус операции</button></div>)}
      {view.proposals.filter(p => p.state !== 'confirmed' && p.state !== 'rejected').map(p => <ProposalCard key={p.key} proposal={p} driver={driver} disabled={view.busy || (unknown.length > 0 && p.state !== 'outcome_unknown')} />)}
      {view.cart && <section className="cart-snapshot" aria-label="Сохранённый снимок корзины">
        <div className="commerce-section-heading"><h2>Сохранённый состав</h2><button className="commerce-secondary" disabled={view.busy} onClick={() => driver.refreshCart()}>Обновить снимок</button></div>
        <small>Версия {view.cart.version} · <time dateTime={view.cart.observedAt}>{new Date(view.cart.observedAt).toLocaleString('ru-RU')}</time></small>
        {view.cart.lines.length ? <LineItems lines={view.cart.lines} /> : <div className="cart-empty"><h3>Здесь пока ничего нет</h3><p>Выберите вариант в чате и отдельно подтвердите точный состав предложения.</p><Link to="/">Перейти к подбору →</Link></div>}
        <div className="commerce-total"><span>Итого</span><strong>{formatMoney(view.cart.total, view.cart.currency)}</strong></div>
      </section>}
    </>}
  </main>;
}
