import { isAxiosError } from 'axios';
import * as sdk from '../client/sdk.gen';
import type { CartLine, CartSnapshot, ChatEvent, OfferSnapshot, OperationOutcome, ProductDetails, ProductResultSet, ProposalLine, ProposalSnapshot, Selection, UpdateDialogue } from '../client/types.gen';
import type { CommerceDriver, CommerceView, LineView, ProductView, ProposalState, ProposalView } from '../components/cart/model';
import { decimal, scaled } from '../components/catalog/decimal';
import { auth } from './api';
import { queryClient } from './query';
import { dialogueQueryKey, publishDialogue } from './dialogue-state';

export function commerceError(error: unknown) {
  if (isAxiosError(error)) {
    const code = error.response?.data?.code ?? error.response?.data?.detail;
    if (error.response?.status === 409) return `Данные изменились. Обновите предложение и подтвердите новый состав. ${typeof code === 'string' ? code : ''}`;
    if (error.response?.status === 429) return 'Слишком много запросов. Подождите немного и повторите.';
    if (!error.response) return 'Не удалось связаться с backend. Проверьте соединение и повторите.';
    return typeof code === 'string' ? `Операция не выполнена: ${code}` : `Backend вернул ошибку ${error.response.status}.`;
  }
  return error instanceof Error ? error.message : 'Не удалось выполнить операцию.';
}
export const commerceRead = <T,>(key: readonly unknown[], request: () => Promise<{ data: T }>) => queryClient.fetchQuery({ queryKey: ['commerce', ...key], staleTime: 0, queryFn: async () => (await request()).data });
export const commerceWrite = <T,>(request: () => Promise<{ data: T }>) => queryClient.getMutationCache().build(queryClient, { mutationFn: async () => (await request()).data, retry: false }).execute(undefined);
const need = (value: string | undefined, name: string) => { if (!value) throw new Error(`Backend не вернул ${name}.`); return value; };
const sum = (lines: readonly LineView[]) => decimal(lines.reduce((total, line) => total + (scaled(line.total, 2) ?? 0n), 0n), 2);
function lineView(line: ProposalLine | CartLine): LineView {
  const quantity = 'addQuantity' in line ? line.addQuantity : (line as CartLine).quantity;
  const amount = need(quantity?.value, 'количество'); const price = need(line.unitPrice?.amount, 'цену');
  const q = scaled(amount, 6); const p = scaled(price, 6);
  if (q === null || p === null) throw new Error('Неверный денежный формат ответа backend.');
  return { productKey: line.article ?? '', article: line.article ?? '', title: line.article ?? '', quantity: amount, unit: quantity?.unit ?? '', warehouse: line.warehouse ?? '', unitPrice: price, total: decimal((q * p + 5_000_000_000n) / 10_000_000_000n, 2), currency: line.unitPrice?.currency ?? '', offerVersion: Number('offerVersion' in line ? line.offerVersion : 0) };
}
export function productView(product: ProductDetails, offer?: OfferSnapshot): ProductView {
  return { key: product.id ?? product.article ?? '', article: product.article ?? '', title: product.name ?? '', unit: offer?.available?.unit ?? '', quantityStep: offer?.available?.step ?? '1', price: offer?.price?.amount ?? null, currency: offer?.price?.currency ?? 'KZT', stock: offer?.available?.value ?? null, warehouse: offer?.warehouse ?? '', freshness: !offer ? 'unknown' : Date.parse(offer.expiresAt ?? '') > Date.now() ? 'fresh' : 'stale', observedAt: offer?.observedAt ?? '', offerVersion: Number(offer?.version ?? 0), specs: Object.entries(product.specs ?? {}).map(([label, value]) => ({ label, value })), certificateKeys: (product.certificates ?? []).map(s => s.id ?? '') };
}
export function proposalView(proposal: ProposalSnapshot): ProposalView {
  const lines = (proposal.lines ?? []).map(lineView); const states: ProposalState[] = ['pending', 'confirmed', 'rejected', 'expired', 'superseded', 'outcome_unknown', 'failed'];
  return { key: need(proposal.id, 'ID предложения'), revision: Number(proposal.revision), conversationKey: need(proposal.conversationId, 'диалог'), expectedCartVersion: Number(proposal.expectedCartVersion), lines, total: sum(lines), currency: lines[0]?.currency ?? 'KZT', expiresAt: proposal.expiresAt ?? '', state: states.includes(proposal.status as ProposalState) ? proposal.status as ProposalState : 'failed', operationKey: proposal.operationId ?? null, note: null, origin: 'catalog' };
}
function cartView(cart: CartSnapshot) { const lines = (cart.lines ?? []).map(lineView); return { version: Number(cart.version), lines, total: sum(lines), currency: lines[0]?.currency ?? 'KZT', observedAt: new Date().toISOString() }; }
const fresh = (): CommerceView => ({ mode: 'live', products: [], sources: [], selections: {}, proposals: [], cart: null, scenario: 'normal', busy: false, notice: null });

export function createLiveCommerce(): CommerceDriver {
  let view = fresh(); let disposed = false; let generation = 0;
  let activeConversation: string | null = null;
  const hydrationVersions = new Map<string, number>();
  const listeners = new Set<() => void>(); const raw = new Map<string, ProposalSnapshot>(); const resultSets = new Map<string, ProductResultSet>();
  const emit = (patch: Partial<CommerceView>) => { if (disposed) return; view = { ...view, ...patch }; listeners.forEach(fn => fn()); };
  const remember = (proposal: ProposalSnapshot) => { if (!proposal.id) return; raw.set(proposal.id, proposal); const mapped = proposalView(proposal); emit({ proposals: [...view.proposals.filter(p => p.key !== mapped.key), mapped] }); };
  const productsFor = (result?: ProductResultSet) => (result?.products ?? []).map(p => productView(p, result?.offers?.find(o => o.article === p.article)));
  const setResult = (conversation: string, result: ProductResultSet) => { resultSets.set(conversation, result); if (conversation === activeConversation) emit({ products: productsFor(result) }); };
  async function run<T>(task: () => Promise<T>): Promise<T | null> {
    if (disposed || view.busy) return null;
    const current = generation; emit({ busy: true, notice: null });
    try { return await task(); } catch (error) { if (current === generation) emit({ notice: commerceError(error) }); return null; }
    finally { if (current === generation) emit({ busy: false }); }
  }
  async function refreshCart() { const current = generation; const cart = await commerceRead(['cart'], () => sdk.getCart({ throwOnError: true })); if (current === generation) emit({ cart: cartView(cart) }); }
  async function readDialogue(conversation: string) {
    const current = generation;
    return queryClient.fetchQuery({ queryKey: dialogueQueryKey(conversation), staleTime: 0,
      queryFn: async () => {
        const state = (await sdk.getDialogue({ path: { id: conversation }, throwOnError: true })).data;
        return current === generation && !disposed ? publishDialogue(conversation, state) : state;
      } });
  }
  async function syncDialogue(conversation: string) {
    try { await readDialogue(conversation); }
    catch (error) { emit({ notice: `Операция выполнена, но параметры диалога не обновились. ${commerceError(error)}` }); }
  }
  async function hydrate(conversation: string) {
    const current = generation;
    const version = (hydrationVersions.get(conversation) ?? 0) + 1;
    hydrationVersions.set(conversation, version);
    activeConversation = conversation;
    emit({ products: productsFor(resultSets.get(conversation)) });
    const valid = () => !disposed && current === generation && hydrationVersions.get(conversation) === version;
    const state = await readDialogue(conversation);
    if (!valid()) return;
    if (typeof state.lastResultSetId === 'string') {
      const result = await commerceRead(['results', conversation, state.lastResultSetId], () => sdk.getResultSet({ path: { id: conversation, resultId: state.lastResultSetId as string }, throwOnError: true }));
      if (!valid()) return;
      setResult(conversation, result);
    } else {
      resultSets.delete(conversation);
      if (activeConversation === conversation) emit({ products: [] });
    }
    if (typeof state.activeProposalId === 'string') {
      const proposal = await commerceRead(['proposal', state.activeProposalId], () => sdk.getProposal({ path: { id: state.activeProposalId as string }, throwOnError: true }));
      if (valid()) remember(proposal);
    }
  }
  async function prepare(conversation: string, patch: UpdateDialogue, lines: Selection[], origin: 'catalog' | 'attachment', reviewedLines?: Parameters<CommerceDriver['prepareReviewed']>[1]['lines']) {
    if (view.proposals.some(p => p.state === 'outcome_unknown')) throw new Error('Сначала узнайте статус предыдущего добавления.');
    const state = await readDialogue(conversation);
    const bound = publishDialogue(conversation, await commerceWrite(() => sdk.updateDialogue({ path: { id: conversation }, body: { ...patch, expectedVersion: state.version }, throwOnError: true })));
    if (typeof bound.lastResultSetId !== 'string') throw new Error('Для предложения нужен сохранённый результат поиска.');
    if (reviewedLines) {
      const result = await commerceRead(['results', conversation, bound.lastResultSetId], () => sdk.getResultSet({ path: { id: conversation, resultId: bound.lastResultSetId as string }, throwOnError: true }));
      lines = reviewedLines.map(line => {
        const product = result.products?.find(item => item.id === line.productKey || item.article === line.productKey);
        if (!product?.article) throw new Error('Выбранный товар отсутствует в проверенном составе файла. Обновите проверку.');
        return { article: product.article, unit: line.unit, warehouse: line.warehouse, addQuantity: line.quantity };
      });
      setResult(conversation, result);
    }
    const proposal = await commerceWrite(() => sdk.prepareProposal({ headers: { 'Idempotency-Key': crypto.randomUUID() }, body: { conversationId: conversation, expectedStateVersion: bound.version, resultSetId: bound.lastResultSetId as string, lines }, throwOnError: true }));
    emit({ proposals: view.proposals.map(p => p.conversationKey === conversation && p.state === 'pending' ? { ...p, state: 'superseded' } : p) });
    remember(proposal); emit({ proposals: view.proposals.map(p => p.key === proposal.id ? { ...p, origin } : p), notice: 'Проверьте состав и отдельно подтвердите добавление.' });
    await syncDialogue(conversation);
    return proposal.id ?? null;
  }
  async function outcome(result: OperationOutcome) {
    emit({ proposals: view.proposals.map(p => p.key === result.proposalId ? { ...p, state: result.status === 'succeeded' ? 'confirmed' : result.status === 'failed' ? 'failed' : 'outcome_unknown', note: typeof result.code === 'string' ? result.code : null } : p) });
    await refreshCart(); await queryClient.invalidateQueries({ queryKey: ['commerce'] });
    const conversation = view.proposals.find(p => p.key === result.proposalId)?.conversationKey;
    if (conversation) await syncDialogue(conversation);
  }
  const onEvent = (event: Event) => { const data = (event as CustomEvent<ChatEvent>).detail; const payload = data?.payload; if (!payload) return;
    if ('proposal' in payload && payload.proposal) remember(payload.proposal);
    if ('resultSet' in payload && payload.resultSet) { const conversation = sessionStorage.getItem('hackalem.active-conversation'); if (conversation && conversation === activeConversation) setResult(conversation, payload.resultSet); }
  };
  const onConversation = (event: Event) => { const id = (event as CustomEvent<{ id: string }>).detail?.id; if (id) void hydrate(id).catch(error => emit({ notice: commerceError(error) })); };
  window.addEventListener('hackalem:chat-event', onEvent); window.addEventListener('hackalem:conversation', onConversation);
  const driver: CommerceDriver = {
    getSnapshot: () => view, subscribe: fn => { listeners.add(fn); return () => { listeners.delete(fn); }; },
    select: (id, selection) => emit({ selections: { ...view.selections, [id]: selection } }),
    prepare: id => { void run(async () => { const selection = view.selections[id]; const result = resultSets.get(id); const product = result?.products?.find(p => p.id === selection?.productKey || p.article === selection?.productKey); const offer = result?.offers?.find(o => o.article === product?.article); if (!selection || !product || !offer) throw new Error('Выберите товар из актуального поиска.'); return prepare(id, { resultSetId: result?.id }, [{ article: product.article, unit: offer.available?.unit, warehouse: offer.warehouse, addQuantity: selection.quantity }], 'catalog'); }); },
    prepareSelection: (id, source) => run(() => prepare(id, { resultSetId: source.resultSetId, fulfillmentOptionId: source.fulfillmentOptionId }, source.lines, 'catalog')),
    prepareReviewed: (id, source) => run(() => prepare(id, { attachmentId: source.jobKey, attachmentVersion: String(source.version) }, [], 'attachment', source.lines)),
    hydrateConversation: id => hydrate(id).catch(error => { emit({ notice: commerceError(error) }); }),
    confirm: (_conversation, id) => { void run(async () => { const proposal = raw.get(id); if (!proposal) throw new Error('Обновите предложение перед подтверждением.'); emit({ proposals: view.proposals.map(p => p.key === id ? { ...p, state: 'confirming' } : p) });
      try { await outcome(await commerceWrite(() => sdk.confirmProposal({ path: { id }, headers: { 'Idempotency-Key': `browser-confirm-${id}-${proposal.revision}` }, body: { revision: proposal.revision, digest: proposal.digest, origin: 'button' }, throwOnError: true }))); }
      catch (error) { if (isAxiosError(error) && error.response) { remember(await commerceRead(['proposal', id], () => sdk.getProposal({ path: { id }, throwOnError: true }))); await refreshCart(); } else emit({ proposals: view.proposals.map(p => p.key === id ? { ...p, state: 'outcome_unknown', note: 'Ответ потерян. Проверьте статус операции перед повторным добавлением.' } : p) }); throw error; }
    }); },
    reject: (conversation, id) => { void run(async () => { remember(await commerceWrite(() => sdk.rejectProposal({ path: { id }, throwOnError: true }))); await queryClient.invalidateQueries({ queryKey: ['commerce'] }); await syncDialogue(conversation); }); },
    lookup: id => { void run(async () => { try { await outcome(await commerceRead(['operation', id], () => sdk.getCartOperation({ path: { id }, throwOnError: true }))); } catch (error) { if (isAxiosError(error) && error.response?.status === 404) { const p = view.proposals.find(p => p.operationKey === id); if (p) remember(await commerceRead(['proposal', p.key], () => sdk.getProposal({ path: { id: p.key }, throwOnError: true }))); } throw error; } }); },
    refreshCart: () => { void run(refreshCart); }, invalidateSelection: id => { const selections = { ...view.selections }; delete selections[id]; emit({ selections }); }, setScenario: () => {},
    clearPrivateData: () => { generation++; activeConversation = null; hydrationVersions.clear(); raw.clear(); resultSets.clear(); emit(fresh()); },
    dispose: () => { disposed = true; generation++; listeners.clear(); window.removeEventListener('hackalem:chat-event', onEvent); window.removeEventListener('hackalem:conversation', onConversation); },
  };
  if (auth.get()) { void run(async () => { await refreshCart(); const id = sessionStorage.getItem('hackalem.active-conversation'); if (id) await hydrate(id); }); }
  return driver;
}
