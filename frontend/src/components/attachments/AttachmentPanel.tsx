import { useEffect, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Paperclip, X } from 'lucide-react';
import { search2 } from '../../client/sdk.gen';
import type { Candidate } from '../../client/types.gen';
import { useAttachments } from '../../hooks/useAttachments';
import { useCommerce } from '../../hooks/useCommerce';
import { validQuantity } from '../catalog/decimal';
import { ProposalCard } from '../cart/ProposalCard';
import { attachmentError, validReviewRow } from '../../lib/attachments-live';
import type { AttachmentJob, AttachmentDriver, ReviewRow } from './model';
import './attachments.css';

const stageName: Record<AttachmentJob['stage'], string> = {
  queued: 'В очереди', extracting: 'Извлечение', ocr: 'Распознавание', matching: 'Сопоставление', ready: 'Готово к проверке', review: 'Проверка строк', failed: 'Ошибка',
};
const confidenceName: Record<ReviewRow['confidence'], string> = {
  matched: 'Найден кандидат', ambiguous: 'Неоднозначно', unmatched: 'Без совпадения', needs_quantity: 'Нет количества',
};
function RowReview({ job, row, driver, onChanged, products, live, busy }: {
  job: AttachmentJob; row: ReviewRow; driver: AttachmentDriver; onChanged: () => void; live: boolean; busy: boolean;
  products: { key: string; title: string; article: string }[];
}) {
  const [draft, setDraft] = useState({ quantity: row.quantity, unit: row.unit, selectedKey: row.selectedKey, warehouse: row.warehouse ?? '' });
  const [query, setQuery] = useState('');
  const [manual, setManual] = useState<Candidate[]>([]);
  const lookup = useMutation({ mutationFn: async () => (await search2({ query: { q: query.trim(), limit: 5 }, throwOnError: true })).data,
    onSuccess: (data) => setManual((data.items ?? []).map((item) => ({ productId: item.id, article: item.article, name: item.name,
      unit: item.unit, minimum: item.minimumQuantity, step: item.stepQuantity,
      warehouses: item.stock?.warehouses?.filter((warehouse) => warehouse.eligible).flatMap((warehouse) => warehouse.warehouseId ? [warehouse.warehouseId] : []) }))) });
  const candidates = [...(row.candidates ?? []), ...manual.filter((item) => !row.candidateKeys.includes(item.productId ?? ''))];
  if (row.selectedKey && !candidates.some((item) => item.productId === row.selectedKey)) candidates.push({ productId: row.selectedKey, name: 'Товар выбран вручную', unit: row.unit, warehouses: row.warehouse ? [row.warehouse] : [] });
  const selected = candidates.find((item) => item.productId === draft.selectedKey);
  const edit = async (patch: Parameters<AttachmentDriver['review']>[3]) => {
    if (await driver.review(job.key, row.key, job.version, patch)) onChanged();
  };
  return <li className={`attachment-row ${row.excluded ? 'excluded' : ''}`}>
    <div className="attachment-row-head"><strong>{row.location}</strong><span>{row.reviewed ? 'Выбор сохранён' : confidenceName[row.confidence]}</span></div>
    <blockquote>{row.rawText || 'Текст не распознан'}</blockquote>
    {row.article && <small>Артикул: {row.article}</small>}
    {!!row.observations?.length && <p className="attachment-disclosure">Наблюдения на фото, требуют проверки: {row.observations.join(" · ")}</p>}
    {row.warnings.map((warning, index) => <p className="attachment-warning" key={index}>{warning}</p>)}
    <div className="attachment-fields">
      <label>Количество<input inputMode="decimal" value={draft.quantity} disabled={(!live && row.excluded) || busy} onChange={(event) => setDraft({ ...draft, quantity: event.target.value.replace(',', '.') })}
        onBlur={() => { if (!live && draft.quantity !== row.quantity) void edit({ quantity: draft.quantity }); }} /></label>
      <label>Единица<select value={draft.unit} disabled={(!live && row.excluded) || busy} onChange={(event) => { setDraft({ ...draft, unit: event.target.value }); if (!live) void edit({ unit: event.target.value }); }}>
        {[...new Set(['', row.unit, selected?.unit ?? '', ...(live ? [] : ['шт.', 'м', 'уп.'])])].map((unit) => <option key={unit} value={unit}>{unit || 'Выберите'}</option>)}</select></label>
      <label>Товар<select value={draft.selectedKey ?? ''} disabled={(!live && row.excluded) || busy} onChange={(event) => {
        const chosen = candidates.find((item) => item.productId === event.target.value);
        setDraft({ ...draft, selectedKey: event.target.value || null, unit: chosen?.unit ?? draft.unit, warehouse: chosen?.warehouses?.length === 1 ? chosen.warehouses[0] : '' });
        if (!live) void edit({ selectedKey: event.target.value || null });
      }}><option value="">Не выбран</option>{live ? candidates.map((candidate) => <option key={candidate.productId} value={candidate.productId}>{candidate.article ?? candidate.productId} · {candidate.name}</option>)
          : row.candidateKeys.map((key) => { const product = products.find((item) => item.key === key); return product ? <option key={key} value={key}>{product.article} · {product.title}</option> : null; })}</select></label>
      {live && <label>Склад<select value={draft.warehouse} disabled={(!live && row.excluded) || busy} onChange={(event) => setDraft({ ...draft, warehouse: event.target.value })}>
        <option value="">Выберите склад</option>{(selected?.warehouses ?? []).map((warehouse) => <option key={warehouse}>{warehouse}</option>)}</select></label>}
    </div>
    {live && selected && <p className="attachment-disclosure">Минимум: {selected.minimum ?? 'по каталогу'} · шаг: {selected.step ?? 'по каталогу'} {selected.unit}</p>}
    {live && <details><summary>Найти другой товар вручную</summary><div className="attachment-search">
      <label>Артикул или название<input value={query} onChange={(event) => setQuery(event.target.value)} /></label>
      <button type="button" className="commerce-secondary" disabled={busy || lookup.isPending || !query.trim()} onClick={() => lookup.mutate()}>{lookup.isPending ? 'Ищем…' : 'Найти'}</button>
    </div>{lookup.isError && <p role="alert" className="attachment-error">{attachmentError(lookup.error)}</p>}
      {lookup.isSuccess && <p role="status">{manual.length ? 'Найденные товары доступны в списке «Товар».' : 'Товаров не найдено. Уточните запрос.'}</p>}</details>}
    <div className="attachment-actions">
      {live && <button type="button" className="commerce-primary" disabled={busy || !validReviewRow({ ...row, ...draft, candidates })} onClick={() => void edit({ ...draft, excluded: false })}>{row.excluded ? 'Вернуть выбранную строку' : 'Сохранить выбор строки'}</button>}
      <button type="button" className="commerce-secondary" disabled={busy || (live && row.excluded && !validReviewRow({ ...row, ...draft, candidates }))} onClick={() => { if (live && row.excluded) { setDraft({ ...draft }); void edit({ ...draft, excluded: false }); } else void edit({ excluded: !row.excluded }); }}>{row.excluded ? 'Вернуть строку' : 'Исключить строку'}</button>
    </div>
  </li>;
}
function JobReview({ job, driver, live, busy }: { job: AttachmentJob; driver: AttachmentDriver; live: boolean; busy: boolean }) {
  const commerce = useCommerce();
  const [notice, setNotice] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const reviewable = job.stage === 'ready' || job.stage === 'review';
  const selected = job.rows.filter((row) => !row.excluded);
  const unresolved = selected.filter((row) => live ? !row.reviewed : !row.selectedKey || !validQuantity(row.quantity, '1') || row.unit !== 'шт.');
  const proposal = job.proposalKey ? commerce.view.proposals.find((item) => item.key === job.proposalKey) : null;
  const onChanged = () => { commerce.driver.invalidateSelection(job.conversation); setNotice(null); };
  const prepare = async () => {
    try {
      const key = await commerce.driver.prepareReviewed(job.conversation, { jobKey: job.key, version: job.version,
        lines: selected.map((row) => ({ productKey: row.selectedKey!, quantity: row.quantity, article: row.article ?? undefined, unit: row.unit, warehouse: row.warehouse })) });
      if (key) { driver.attachProposal(job.key, key); setNotice('Состав перенесён в предложение. Проверьте цену и подтвердите отдельно.'); }
      else setNotice('Предложение не создано. Проверьте строки и доступные остатки.');
    } catch (error) { setNotice(attachmentError(error)); }
  };
  return <article className="attachment-job">
    <div className="attachment-job-heading"><div><strong>{job.fileName}</strong><small>{job.family.toUpperCase()}{job.size > 0 ? ` · ${(job.size / 1024).toFixed(1)} КБ` : ''} · версия проверки {job.serverVersion ?? job.version}</small></div><span>{stageName[job.stage]}</span></div>
    {job.stage !== 'failed' && <progress aria-label={`Ход обработки файла ${job.fileName}`} value={job.progress} max="100" />}
    {job.error && <p className="attachment-error" role="alert">{job.error}</p>}
    {job.warnings?.map((warning, index) => <p className="attachment-warning" key={index}>{warning}</p>)}
    {live && <div className="attachment-actions">
      <button type="button" className="commerce-secondary" disabled={busy} onClick={() => void driver.download?.(job.key)}>Скачать оригинал</button>
      <button type="button" className="commerce-secondary" disabled={busy} onClick={() => { onChanged(); void driver.reprocess?.(job.key); }}>Распознать заново</button>
      <button type="button" className="commerce-secondary" disabled={busy} onClick={() => setDeleting(!deleting)}>Удалить файл</button>
    </div>}
    {deleting && <div className="attachment-disclosure"><p>Удалить оригинал и результаты проверки?</p><button type="button" className="commerce-secondary" disabled={busy} onClick={() => { onChanged(); void driver.remove?.(job.key); }}>Да, удалить</button> <button type="button" className="commerce-secondary" onClick={() => setDeleting(false)}>Отмена</button></div>}
    {reviewable && <>
      <p className="attachment-disclosure">{live ? 'Проверьте распознанные строки и сохраните выбранный товар, количество и склад. Неясные позиции можно исключить.' : 'Ниже учебные строки для показа review. Они не извлечены из содержимого файла.'}</p>
      {job.rows.length === 0 && <p role="status">Не удалось извлечь позиции. Проверьте предупреждения и загрузите более чёткий документ.</p>}
      <ul className="attachment-rows">{job.rows.map((row) => <RowReview key={`${row.key}:${row.selectedKey}:${row.quantity}:${row.unit}:${row.warehouse}:${row.excluded}`} job={job} row={row} driver={driver} products={commerce.view.products} onChanged={onChanged} live={live} busy={busy} />)}</ul>
      <div className="attachment-review-footer"><span>{selected.length} выбрано · {unresolved.length} требуют решения</span>
        <button type="button" className="commerce-primary" disabled={!!proposal || !selected.length || !!unresolved.length || commerce.view.busy || busy || ['loading', 'unavailable'].includes(commerce.view.mode)} onClick={() => void prepare()}>Сформировать предложение</button></div>
      {live && driver.linkConversation && <button type="button" className="commerce-secondary" disabled={busy || !selected.length || !!unresolved.length} onClick={() => void driver.linkConversation?.(job.key)}>Связать проверку с чатом</button>}
      {unresolved.length > 0 && <p className="attachment-warning" role="status">Неизвестное количество не заменяется на 1. Сохраните выбор каждой строки или исключите её.</p>}
      {notice && <p role="status">{notice}</p>}
      {proposal && <ProposalCard proposal={proposal} driver={commerce.driver} disabled={commerce.view.busy || busy || unresolved.length > 0} onRenew={() => void prepare()} />}
      <p className="attachment-disclosure">Проверка файла не добавляет товары в корзину. Для этого нужно подтвердить отдельную карточку предложения.</p>
    </>}
  </article>;
}
export function AttachmentWorkspace({ conversation, enabled }: { conversation: string | null; enabled: boolean }) {
  const { driver, view } = useAttachments();
  const input = useRef<HTMLInputElement>(null);
  const jobs = view.jobs.filter((job) => job.conversation === conversation);
  const live = view.mode === 'live';
  const usable = enabled && (live || view.mode === 'demo') && !!conversation;
  return <div className="attachment-workspace">
    <p>Форматы: {(view.capabilities?.extensions ?? ['xls', 'xlsx', 'doc', 'docx', 'pdf', 'jpg', 'jpeg']).join(', ').toUpperCase()} · до {view.capabilities?.maxBytes ? Math.floor(view.capabilities.maxBytes / 1024 / 1024) : 12} МБ</p>
    {live ? <p className="attachment-disclosure">Распознавание текста: {view.capabilities?.ocrAvailable ? 'доступно' : 'недоступно'} · распознавание фотографии товара: {view.capabilities?.visionAvailable ? 'доступно' : 'недоступно'}. Оригиналы и проверка доступны только вашей сессии.</p>
      : view.mode === 'demo' ? <p className="attachment-disclosure">Учебный режим: файл остаётся в браузере, строки являются примером.</p> : <p role="status">{view.mode === 'loading' ? 'Загружаем возможности обработки…' : 'Обработка файлов недоступна.'}</p>}
    <input ref={input} type="file" tabIndex={-1} accept={(view.capabilities?.extensions ?? ['xls', 'xlsx', 'doc', 'docx', 'pdf', 'jpg', 'jpeg']).map((ext) => `.${ext}`).join(',')} className="sr-only" aria-label="Выбрать документ" onChange={(event) => {
      const file = event.target.files?.[0]; if (file && conversation) void driver.upload(conversation, file); event.target.value = '';
    }} />
    <div className="attachment-actions"><button type="button" className="commerce-primary" disabled={!usable || view.busy} onClick={() => input.current?.click()}>{view.busy ? 'Выполняем…' : 'Выбрать файл'}</button>
      <button type="button" className="commerce-secondary" disabled={view.busy} onClick={() => driver.refresh()}>Обновить</button></div>
    {!conversation && <p role="status">Выберите или создайте диалог для загрузки.</p>}
    {view.notice && <p className="attachment-error" role="status">{view.notice}</p>}
    {jobs.length ? jobs.map((job) => <JobReview key={job.key} job={job} driver={driver} live={live} busy={view.busy} />) : <p className="attachment-empty">Файлов в этом диалоге пока нет.</p>}
  </div>;
}
export function AttachmentPanel({ conversation, enabled }: { conversation: string | null; enabled: boolean }) {
  const { view } = useAttachments();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!open) return;
    const triggerButton = trigger.current;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); setOpen(false); return; }
      if (event.key !== 'Tab' || !dialog.current) return;
      const targets = [...dialog.current.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled), summary')];
      const first = targets[0]; const last = targets[targets.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); triggerButton?.focus(); };
  }, [open]);
  const usable = enabled && ['live', 'demo'].includes(view.mode) && !!conversation;
  return <>
    <button ref={trigger} type="button" className="icon-button" disabled={!usable} onClick={() => setOpen(true)} aria-label="Прикрепить файл" title={usable ? 'Прикрепить файл' : 'Загрузка пока недоступна'}><Paperclip size={18} /></button>
    {open && <div className="attachment-overlay" role="presentation"><button type="button" className="attachment-scrim" aria-label="Закрыть вложения" onClick={() => setOpen(false)} />
      <section ref={dialog} className="attachment-drawer" role="dialog" aria-modal="true" aria-label="Файлы и проверка позиций"
        onKeyDown={(event) => { if (event.key === 'Enter' && event.target instanceof HTMLInputElement && event.target.type === 'text') event.preventDefault(); }}>
        <header><div><span className="commerce-eyebrow">{view.mode === 'demo' ? 'Локальное демо' : 'Документы и фотографии'}</span><h2>Проверка спецификации</h2></div><button type="button" className="icon-button" aria-label="Закрыть" onClick={() => setOpen(false)}><X size={18} /></button></header>
        <AttachmentWorkspace conversation={conversation} enabled={enabled} />
      </section>
    </div>}
  </>;
}
