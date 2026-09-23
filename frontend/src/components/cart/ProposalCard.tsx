import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { CommerceDriver, ProposalView, ProposalState } from './model';
import { LineItems } from './LineItems';
import { formatMoney } from '../catalog/decimal';

const labels: Record<ProposalState, string> = { pending: 'Нужно ваше согласие', confirming: 'Уточняем результат…', confirmed: 'Подтверждено в демо', rejected: 'Отклонено',
  expired: 'Срок истёк', superseded: 'Заменено новым предложением', outcome_unknown: 'Результат неизвестен', failed: 'Не выполнено' };
export function ProposalCard({ proposal, driver, disabled, onRenew }: { proposal: ProposalView; driver: CommerceDriver; disabled: boolean; onRenew?: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (proposal.state !== 'pending') return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [proposal.state]);
  const expired = Date.parse(proposal.expiresAt) <= now;
  const pending = proposal.state === 'pending';
  return <article className={`proposal-card proposal-${proposal.state}`} aria-label={`Предложение версии ${proposal.revision}`}>
    <div className="proposal-heading"><div><span className="commerce-eyebrow">Точный состав · демо</span><h3>Предложение №{proposal.revision}</h3></div><span>{pending && expired ? 'Срок истёк' : labels[proposal.state]}</span></div>
    <LineItems lines={proposal.lines} />
    <div className="commerce-total"><span>Итого к добавлению</span><strong>{formatMoney(proposal.total, proposal.currency)}</strong></div>
    <small>Версия предложения: {proposal.revision} · версия корзины: {proposal.expectedCartVersion}<br />Действует до <time dateTime={proposal.expiresAt}>{new Date(proposal.expiresAt).toLocaleTimeString('ru-RU')}</time></small>
    {proposal.note && <p role="status" className="commerce-notice">{proposal.note}</p>}
    {pending && !expired && <><p>Подтверждение относится только к этому составу и цене. Сообщение «да» само по себе ничего не добавит.</p><div className="commerce-actions">
      <button type="button" className="commerce-primary" disabled={disabled} onClick={() => driver.confirm(proposal.conversationKey, proposal.key, proposal.revision)}>Подтвердить этот состав</button>
      <button type="button" className="commerce-secondary" disabled={disabled} onClick={() => driver.reject(proposal.conversationKey, proposal.key)}>Отклонить</button></div></>}
    {(proposal.state === 'expired' || (pending && expired) || proposal.state === 'failed' || proposal.state === 'superseded') && <button type="button" className="commerce-secondary" disabled={disabled || (proposal.origin === 'attachment' && !onRenew)} onClick={() => onRenew ? onRenew() : driver.prepare(proposal.conversationKey)}>Запросить новое предложение</button>}
    {proposal.state === 'outcome_unknown' && proposal.operationKey && <button type="button" className="commerce-primary" disabled={disabled} onClick={() => driver.lookup(proposal.operationKey!)}>Узнать статус операции</button>}
    {proposal.state === 'confirmed' && <Link className="commerce-cart-link" to="/cart">Открыть сохранённую демо-корзину →</Link>}
  </article>;
}
