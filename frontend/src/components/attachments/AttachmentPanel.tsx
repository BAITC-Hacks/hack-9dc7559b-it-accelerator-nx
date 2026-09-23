import { useEffect, useRef, useState } from 'react';
import { Paperclip, X } from 'lucide-react';
import { useAttachments } from '../../hooks/useAttachments';
import { useCommerce } from '../../hooks/useCommerce';
import { validQuantity } from '../catalog/decimal';
import { ProposalCard } from '../cart/ProposalCard';
import type { AttachmentJob, AttachmentDriver, ReviewRow } from './model';
import './attachments.css';

const stageName: Record<AttachmentJob['stage'], string> = {
  queued: 'В очереди', extracting: 'Извлечение', ocr: 'Распознавание', matching: 'Сопоставление', ready: 'Готово к проверке', review: 'Проверка строк', failed: 'Ошибка',
};
const confidenceName: Record<ReviewRow['confidence'], string> = {
  matched: 'Совпадение', ambiguous: 'Неоднозначно', unmatched: 'Без совпадения', needs_quantity: 'Нет количества',
};
function RowReview({ job, row, driver, onChanged, products }: {
  job: AttachmentJob; row: ReviewRow; driver: AttachmentDriver; onChanged: () => void;
  products: { key: string; title: string; article: string }[];
}) {
  const [draft, setDraft] = useState(row.quantity);
  const edit = (patch: Parameters<AttachmentDriver['review']>[3]) => {
    if (driver.review(job.key, row.key, job.version, patch)) onChanged();
  };
  return <li className={`attachment-row ${row.excluded ? 'excluded' : ''}`}>
    <div className="attachment-row-head"><strong>{row.location}</strong><span>{confidenceName[row.confidence]}</span></div>
    <blockquote>{row.rawText}</blockquote>
    {row.article && <small>Артикул: {row.article}</small>}
    {row.warnings.map((warning, index) => <p className="attachment-warning" key={index}>{warning}</p>)}
    <div className="attachment-fields">
      <label>Количество<input inputMode="numeric" value={draft} disabled={row.excluded} onChange={(event) => setDraft(event.target.value.replace(',', '.'))}
        onBlur={() => { if (draft !== row.quantity) edit({ quantity: draft }); }} /></label>
      <label>Единица<select value={row.unit} disabled={row.excluded} onChange={(event) => edit({ unit: event.target.value })}><option value="шт.">шт.</option><option value="м">м</option><option value="уп.">уп.</option></select></label>
      <label>Товар<select value={row.selectedKey ?? ''} disabled={row.excluded} onChange={(event) => edit({ selectedKey: event.target.value || null })}>
        <option value="">Не выбран</option>{row.candidateKeys.map((key) => {
          const product = products.find((item) => item.key === key);
          return product ? <option key={key} value={key}>{product.article} · {product.title}</option> : null;
        })}</select></label>
    </div>
    <button type="button" className="commerce-secondary" onClick={() => edit({ excluded: !row.excluded })}>{row.excluded ? 'Вернуть строку' : 'Исключить строку'}</button>
  </li>;
}
function JobReview({ job, driver }: { job: AttachmentJob; driver: AttachmentDriver }) {
  const commerce = useCommerce();
  const [notice, setNotice] = useState<string | null>(null);
  const reviewable = job.stage === 'ready' || job.stage === 'review';
  const selected = job.rows.filter((row) => !row.excluded);
  const unresolved = selected.filter((row) => !row.selectedKey || !validQuantity(row.quantity, '1') || row.unit !== 'шт.');
  const proposal = job.proposalKey ? commerce.view.proposals.find((item) => item.key === job.proposalKey) : null;
  const onChanged = () => { commerce.driver.invalidateSelection(job.conversation); setNotice(null); };
  return <article className="attachment-job">
    <div className="attachment-job-heading"><div><strong>{job.fileName}</strong><small>{job.family.toUpperCase()} · {(job.size / 1024).toFixed(1)} КБ · версия проверки {job.version}</small></div><span>{stageName[job.stage]}</span></div>
    {job.stage !== 'failed' && <progress aria-label={`Ход учебной обработки файла ${job.fileName}`} value={job.progress} max="100" />}
    {job.error && <p className="attachment-error" role="alert">{job.error}</p>}
    {reviewable && <>
      <p className="attachment-disclosure">Ниже учебные строки для показа review. Они не извлечены из содержимого файла. Неясные позиции исключите или исправьте вручную.</p>
      <ul className="attachment-rows">{job.rows.map((row) => <RowReview key={row.key} job={job} row={row} driver={driver} products={commerce.view.products} onChanged={onChanged} />)}</ul>
      <div className="attachment-review-footer"><span>{selected.length} выбрано · {unresolved.length} требуют решения</span>
        <button type="button" className="commerce-primary" disabled={!!proposal || !selected.length || !!unresolved.length || commerce.view.busy || commerce.view.mode !== 'demo'} onClick={() => {
          const key = commerce.driver.prepareReviewed(job.conversation, { jobKey: job.key, version: job.version,
            lines: selected.map((row) => ({ productKey: row.selectedKey!, quantity: row.quantity })) });
          if (key) { driver.attachProposal(job.key, key); setNotice('Состав перенесён в предложение. Проверьте цену и подтвердите отдельно.'); }
          else setNotice('Предложение не создано. Проверьте строки и доступные остатки.');
        }}>Сформировать предложение</button></div>
      {unresolved.length > 0 && <p className="attachment-warning" role="status">Неизвестное количество не заменяется на 1. Каждую неоднозначную строку нужно решить.</p>}
      {notice && <p role="status">{notice}</p>}
      {proposal && <><p className="attachment-disclosure">Если исправить строку, прежнее предложение отменяется и потребуется создать новое.</p>
        <ProposalCard proposal={proposal} driver={commerce.driver} disabled={commerce.view.busy || unresolved.length > 0} onRenew={() => {
          const key = commerce.driver.prepareReviewed(job.conversation, { jobKey: job.key, version: job.version,
            lines: selected.map((row) => ({ productKey: row.selectedKey!, quantity: row.quantity })) });
          if (key) driver.attachProposal(job.key, key);
          else setNotice('Новое предложение не создано. Уточните количество и остатки.');
        }} /></>}
      <p className="attachment-disclosure">Проверка файла не добавляет товары в корзину. Для этого нужно подтвердить отдельную карточку предложения.</p>
    </>}
  </article>;
}
export function AttachmentPanel({ conversation, enabled }: { conversation: string | null; enabled: boolean }) {
  const { driver, view } = useAttachments();
  const [open, setOpen] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!open) return;
    const triggerButton = trigger.current;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); setOpen(false); return; }
      if (event.key !== 'Tab' || !dialog.current) return;
      const targets = [...dialog.current.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled)')];
      const first = targets[0];
      const last = targets[targets.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); triggerButton?.focus(); };
  }, [open]);
  const jobs = view.jobs.filter((job) => job.conversation === conversation);
  const usable = enabled && view.mode === 'demo' && !!conversation;
  return <>
    <button ref={trigger} type="button" className="icon-button" disabled={!usable} onClick={() => setOpen(true)} aria-label="Прикрепить файл" title={usable ? 'Прикрепить файл' : 'Загрузка пока недоступна'}><Paperclip size={18} /></button>
    {open && <div className="attachment-overlay" role="presentation"><button type="button" className="attachment-scrim" aria-label="Закрыть вложения" onClick={() => setOpen(false)} />
      <section ref={dialog} className="attachment-drawer" role="dialog" aria-modal="true" aria-label="Файлы и проверка позиций"
        onKeyDown={(event) => { if (event.key === 'Enter' && event.target instanceof HTMLInputElement && event.target.type === 'text') event.preventDefault(); }}>
        <header><div><span className="commerce-eyebrow">Локальное демо</span><h2>Проверка спецификации</h2></div><button type="button" className="icon-button" aria-label="Закрыть" onClick={() => setOpen(false)}><X size={18} /></button></header>
        <p>Форматы: XLS, XLSX, DOC, DOCX, PDF, JPEG · до 12 МБ в учебном режиме.</p>
        <p className="attachment-disclosure">Файл остаётся в браузере: проверяется только заголовок формата. Реальная загрузка, OCR и поиск товаров будут доступны после подключения backend.</p>
        <input ref={input} type="file" tabIndex={-1} accept=".xls,.xlsx,.doc,.docx,.pdf,.jpg,.jpeg" className="sr-only" aria-label="Выбрать документ" onChange={(event) => {
          const file = event.target.files?.[0];
          if (file && conversation) void driver.upload(conversation, file);
          event.target.value = '';
        }} />
        <button type="button" className="commerce-primary" disabled={!usable || view.busy} onClick={() => input.current?.click()}>{view.busy ? 'Проверяем файл…' : 'Выбрать файл'}</button>
        {view.notice && <p className="attachment-error" role="alert">{view.notice}</p>}
        {jobs.length ? jobs.map((job) => <JobReview key={job.key} job={job} driver={driver} />) : <p className="attachment-empty">Файлов в этом диалоге пока нет.</p>}
      </section>
    </div>}
  </>;
}
