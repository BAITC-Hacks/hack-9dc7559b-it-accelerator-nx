import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Check, FileText, Plus, RefreshCw, ShieldCheck, SlidersHorizontal, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { getDialogue, getResultSet, updateDialogue } from '../../client';
import type { DialogueState, UpdateDialogue } from '../../client';
import { apiError } from '../../lib/api-errors';
import { constraintLabels, dialogueCategories, dialogueForm, dialogueUpdate } from '../../lib/dialogue-form';
import type { DialogueForm } from '../../lib/dialogue-form';
import { dialogueQueryKey, publishDialogue } from '../../lib/dialogue-state';
import { formatMoney, formatQuantity } from '../catalog/decimal';
import './parameters.css';

/** Remount the editing session on a conversation switch; drafts never cross conversations. */
export function DialogueEditor({ conversation }: { conversation: string }) {
  return <DialogueParameters key={conversation} conversation={conversation} />;
}

function DialogueParameters({ conversation }: { conversation: string }) {
  const [draft, setDraft] = useState<{ base: DialogueState; form: DialogueForm }>();
  const [validationError, setValidationError] = useState('');
  const [showResults, setShowResults] = useState(false);
  const state = useQuery({
    queryKey: dialogueQueryKey(conversation),
    queryFn: async ({ signal }) => publishDialogue(conversation, (await getDialogue({ path: { id: conversation }, signal, throwOnError: true })).data),
  });
  const form = draft?.form ?? dialogueForm(state.data ?? {});
  const resultId = typeof state.data?.lastResultSetId === 'string' ? state.data.lastResultSetId : '';
  const result = useQuery({
    queryKey: ['dialogue-result', conversation, resultId], enabled: showResults && !!resultId, retry: false,
    queryFn: async ({ signal }) => (await getResultSet({ path: { id: conversation, resultId }, signal, throwOnError: true })).data,
  });
  const change = useMutation({
    mutationFn: async (body: UpdateDialogue) => (await updateDialogue({ path: { id: conversation }, body, throwOnError: true })).data,
    onSuccess: (data) => {
      publishDialogue(conversation, data);
      setDraft(undefined);
      setValidationError('');
    },
    onError: () => { void state.refetch(); },
  });
  const conflict = isAxiosError(change.error) && change.error.response?.status === 409;
  const newerState = !!draft && !!state.data?.version && draft.base.version !== state.data.version;
  function edit(patch: Partial<DialogueForm>) {
    if (!state.data || change.isPending) return;
    change.reset();
    setValidationError('');
    setDraft((current) => ({ base: current?.base ?? state.data!, form: { ...(current?.form ?? dialogueForm(state.data!)), ...patch } }));
  }
  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!draft || change.isPending) return;
    try { change.mutate(dialogueUpdate(draft.form, draft.base)); }
    catch (error) { setValidationError(apiError(error)); }
  }
  return <div className="context-panel-inner parameters-panel">
    <div className="context-heading"><SlidersHorizontal size={17} /><h2>Ваш подбор</h2></div>
    <p className="parameters-caption">Сохраните параметры — ассистент учтёт их в следующем поиске.</p>
    {state.isPending && <p role="status" className="parameters-message">Загружаем параметры…</p>}
    {state.error && <div className="parameters-error" role="alert"><p>{apiError(state.error)}</p><button type="button" onClick={() => void state.refetch()}>Повторить загрузку</button></div>}
    {state.data && <form className="parameters-form" onSubmit={submit}>
      <fieldset disabled={change.isPending}>
        <label>Категория<select value={form.category} onChange={(event) => edit({ category: event.target.value })}>
          <option value="">Любая категория</option>
          {dialogueCategories.map((category) => <option key={category.value} value={category.value}>{category.label}</option>)}
          {form.category && !dialogueCategories.some((category) => category.value === form.category) && <option value={form.category}>{form.category}</option>}
        </select></label>
        <div className="parameters-row"><label>Бюджет до<input inputMode="decimal" value={form.budget} placeholder="Без ограничения" onChange={(event) => edit({ budget: event.target.value })} /></label>
          <label className="parameters-unit">Валюта<select value={form.currency} onChange={(event) => edit({ currency: event.target.value })}>{[...new Set(['KZT', 'RUB', 'USD', 'EUR', form.currency])].filter(Boolean).map((currency) => <option key={currency}>{currency}</option>)}</select></label></div>
        <div className="parameters-row"><label>Количество<input inputMode="decimal" value={form.quantity} placeholder="Не задано" onChange={(event) => edit({ quantity: event.target.value })} /></label>
          <label className="parameters-unit">Единица<select value={form.unit} onChange={(event) => edit({ unit: event.target.value })}>
            <option value="pcs">шт.</option><option value="m">м</option><option value="kg">кг</option>
            {!['pcs', 'm', 'kg'].includes(form.unit) && <option value={form.unit}>{form.unit}</option>}
          </select></label></div>
        {form.quantity && <label>Шаг количества<input inputMode="decimal" value={form.step} onChange={(event) => edit({ step: event.target.value })} /></label>}
        <details className="parameters-constraints" open={form.constraints.length > 0 || undefined}>
          <summary>Характеристики{form.constraints.length > 0 ? ` · ${form.constraints.length}` : ''}</summary>
          <p className="parameters-caption">Например: ток 16 А или сечение 2,5 мм².</p>
          {form.constraints.map((entry, index) => <div className="parameters-constraint" key={index}>
            <div className="parameters-constraint-heading"><span>Параметр {index + 1}</span><button type="button" className="parameters-remove" aria-label={`Удалить параметр ${index + 1}`} onClick={() => edit({ constraints: form.constraints.filter((_, position) => position !== index) })}><X size={15} /></button></div>
            <label>Характеристика<select value={Object.hasOwn(constraintLabels, entry.key) ? entry.key : '__custom'} onChange={(event) => edit({ constraints: form.constraints.map((item, position) => position === index ? { ...item, key: event.target.value === '__custom' ? '' : event.target.value } : item) })}>
              <option value="__custom">Другая характеристика</option>{Object.entries(constraintLabels).map(([key, label]) => <option value={key} key={key}>{label}</option>)}
            </select></label>
            {!Object.hasOwn(constraintLabels, entry.key) && <label>Название характеристики<input value={entry.key} onChange={(event) => edit({ constraints: form.constraints.map((item, position) => position === index ? { ...item, key: event.target.value } : item) })} /></label>}
            <label>Значение<input value={entry.value} placeholder="Например, 16" onChange={(event) => edit({ constraints: form.constraints.map((item, position) => position === index ? { ...item, value: event.target.value } : item) })} /></label>
          </div>)}
          <button className="parameters-secondary" type="button" onClick={() => edit({ constraints: [...form.constraints, { key: 'currentA', value: '' }] })}><Plus size={15} />Добавить характеристику</button>
        </details>
        {(conflict || newerState) && <div className="parameters-warning" role="alert"><p>Параметры изменились в диалоге. Ваши правки сохранены в форме. Загрузите актуальные значения и проверьте их перед повторным сохранением.</p><button type="button" disabled={state.isFetching} onClick={async () => {
          const latest = await state.refetch();
          if (latest.data && !latest.error) { setDraft(undefined); change.reset(); setValidationError(''); }
        }}><RefreshCw size={14} />Загрузить актуальные параметры</button></div>}
        {validationError && <p className="parameters-error" role="alert">{validationError}</p>}
        {change.error && !conflict && <p className="parameters-error" role="alert">{apiError(change.error)}</p>}
        <button className="parameters-save" disabled={!draft || change.isPending || newerState || conflict}>{change.isPending ? 'Сохраняем…' : 'Сохранить параметры'}</button>
        <div className="parameters-actions"><button type="button" disabled={change.isPending} onClick={() => edit({ category: '', budget: '', quantity: '', constraints: [] })}>Сбросить фильтры</button>{draft && <button type="button" onClick={() => { setDraft(undefined); change.reset(); setValidationError(''); }}>Отменить правки</button>}</div>
      </fieldset>
      {change.isSuccess && !draft && <p className="parameters-success" role="status"><Check size={14} />Параметры сохранены</p>}
      {draft && !newerState && <p className="parameters-caption" role="status">Есть несохранённые изменения.</p>}
    </form>}
    {!!state.data?.selectedArticles?.length && <section className="parameters-summary"><h3>Выбранные товары</h3><ul>{state.data.selectedArticles.map((article) => <li key={article}>Артикул <strong>{article}</strong></li>)}</ul><Link to="/catalog">Открыть каталог</Link></section>}
    {typeof state.data?.attachmentId === 'string' && <section className="parameters-summary"><h3><FileText size={15} />Прикреплённый файл</h3><p>Файл связан с этим подбором{typeof state.data.attachmentVersion === 'string' ? ` · версия ${state.data.attachmentVersion}` : ''}.</p><Link to="/attachments">Открыть файлы и распознанные позиции</Link></section>}
    {resultId && <section className="parameters-summary"><button type="button" className="parameters-results-toggle" aria-expanded={showResults} onClick={() => setShowResults((value) => !value)}>{showResults ? 'Скрыть последний подбор' : 'Показать последний подбор'}</button>
      {showResults && result.isPending && <p role="status">Загружаем товары…</p>}
      {showResults && result.error && <div className="parameters-error" role="alert"><p>{apiError(result.error)}</p><button type="button" onClick={() => void result.refetch()}>Повторить</button></div>}
      {showResults && result.data && <><p className="parameters-caption">Сохранённый результат поиска. Актуальную цену и остаток проверяйте перед подтверждением.</p>{result.data.products?.length ? <ul className="parameters-products">{result.data.products.map((product, index) => {
        const offer = result.data.offers?.find((item) => item.article === product.article);
        return <li key={product.id ?? product.article ?? index}><strong>{product.name ?? 'Товар'}</strong><span>Артикул: {product.article ?? 'не указан'}</span>{offer && <span>{formatMoney(offer.price?.amount ?? null, offer.price?.currency)}{offer.available?.value ? ` · ${formatQuantity(offer.available.value)} ${offer.available.unit === 'pcs' ? 'шт.' : offer.available.unit ?? ''}` : ''}</span>}{product.specs && <dl>{Object.entries(product.specs).slice(0, 4).map(([key, value]) => <div key={key}><dt>{constraintLabels[key] ?? key}</dt><dd>{value}</dd></div>)}</dl>}</li>;
      })}</ul> : <p>В последнем подборе пока нет товаров.</p>}</>}
    </section>}
    <div className="cart-assurance"><ShieldCheck size={18} /><p>Изменение параметров<br /><strong>не меняет вашу корзину.</strong></p></div>
  </div>;
}
