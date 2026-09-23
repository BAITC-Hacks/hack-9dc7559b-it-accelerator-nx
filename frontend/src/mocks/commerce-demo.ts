import type { CartView, CommerceDriver, CommerceView, LineView, ProposalView, SelectionView } from '../components/cart/model';
import { decimal, scaled, validQuantity } from '../components/catalog/decimal';
import { demoProducts, demoSources } from './commerce-data';

// Local UI simulator only. No HTTP DTOs, partner writes or live-AI execution.
const storageKey = 'hackalem.ui-commerce-demo.v1';
const emptyCart = (): CartView => ({ version: 0, lines: [], total: '0.00', currency: 'KZT', observedAt: new Date().toISOString() });
const fresh = (): CommerceView => ({ mode: 'demo', products: demoProducts(), sources: demoSources, selections: {}, proposals: [], cart: emptyCart(), scenario: 'normal', busy: false, notice: null });
type Operation = { key: string; proposal: string; cart: CartView };
type Store = { view: CommerceView; authoritative: CartView; operations: Operation[] };
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const money = (value: unknown): value is string => typeof value === 'string' && scaled(value, 2) !== null;
const quantity = (value: unknown): value is string => typeof value === 'string' && scaled(value, 3) !== null;
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
function line(value: unknown): value is LineView {
  return record(value) && ['productKey', 'article', 'title', 'unit', 'warehouse', 'currency'].every((key) => typeof value[key] === 'string')
    && quantity(value.quantity) && money(value.unitPrice) && money(value.total) && integer(value.offerVersion);
}
function cart(value: unknown): value is CartView {
  return record(value) && integer(value.version) && Array.isArray(value.lines) && value.lines.length <= 100 && value.lines.every(line)
    && money(value.total) && value.currency === 'KZT' && date(value.observedAt);
}
function proposal(value: unknown): value is ProposalView {
  return record(value) && typeof value.key === 'string' && typeof value.conversationKey === 'string'
    && integer(value.revision) && integer(value.expectedCartVersion) && date(value.expiresAt)
    && Array.isArray(value.lines) && value.lines.length <= 100 && value.lines.every(line) && money(value.total) && value.currency === 'KZT'
    && typeof value.state === 'string' && ['pending', 'confirming', 'confirmed', 'rejected', 'expired', 'superseded', 'outcome_unknown', 'failed'].includes(value.state)
    && (value.operationKey === null || typeof value.operationKey === 'string') && (value.note === null || typeof value.note === 'string');
}
function restore(): Store | null {
  try {
    const raw = sessionStorage.getItem(storageKey);
    if (!raw || raw.length > 1_000_000) return null;
    const data: unknown = JSON.parse(raw);
    if (!record(data) || !record(data.view) || !cart(data.authoritative) || !Array.isArray(data.operations)
      || data.operations.length > 200 || !Array.isArray(data.view.proposals) || data.view.proposals.length > 200
      || !data.view.proposals.every(proposal) || !cart(data.view.cart)) return null;
    const operations: Operation[] = [];
    for (const op of data.operations) {
      if (!record(op) || typeof op.key !== 'string' || typeof op.proposal !== 'string' || !cart(op.cart)) return null;
      operations.push({ key: op.key, proposal: op.proposal, cart: op.cart });
    }
    // Reset offers and selection on reload; pending consent must not survive new offers.
    const proposals = data.view.proposals.map((item): ProposalView => ({ ...item,
      state: item.state === 'confirming' ? 'outcome_unknown' : item.state === 'pending' ? 'superseded' : item.state,
      note: item.state === 'confirming' ? 'Ответ потерян при перезагрузке. Узнайте статус операции.'
        : item.state === 'pending' ? 'После перезагрузки запросите новое предложение.' : item.note,
    }));
    const products = demoProducts();
    if (Array.isArray(data.view.products)) {
      for (const saved of data.view.products) {
        if (!record(saved)) continue;
        const product = products.find((item) => item.key === saved.key);
        if (!product || !(saved.price === null || money(saved.price)) || !(saved.stock === null || quantity(saved.stock))
          || !integer(saved.offerVersion) || !date(saved.observedAt)
          || (saved.freshness !== 'fresh' && saved.freshness !== 'stale' && saved.freshness !== 'unknown')) continue;
        Object.assign(product, { price: saved.price, stock: saved.stock, offerVersion: saved.offerVersion, observedAt: saved.observedAt, freshness: saved.freshness });
      }
    }
    return { view: { ...fresh(), products, cart: data.view.cart, proposals }, authoritative: data.authoritative, operations };
  } catch { return null; }
}

export function createDemoCommerce(): CommerceDriver {
  const restored = restore();
  let view = restored?.view ?? fresh();
  let authoritative = restored?.authoritative ?? emptyCart();
  let operations = restored?.operations ?? [];
  const listeners = new Set<() => void>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;
  const publish = () => {
    try { sessionStorage.setItem(storageKey, JSON.stringify({ view, authoritative, operations })); }
    catch { view = { ...view, notice: 'Сохранение недоступно. Демо-корзина живёт до закрытия страницы.' }; }
    listeners.forEach((listener) => listener());
  };
  const notice = (text: string) => { view = { ...view, notice: text }; publish(); };
  const update = (key: string, patch: Partial<ProposalView>) => {
    view = { ...view, proposals: view.proposals.map((item) => item.key === key ? { ...item, ...patch } : item) };
  };
  const unresolved = () => view.proposals.some((item) => item.state === 'outcome_unknown');
  const blocked = () => disposed || view.busy || unresolved();
  const total = (lines: readonly LineView[]) => decimal(lines.reduce((sum, item) => sum + scaled(item.total, 2)!, 0n), 2);
  const available = (key: string) => {
    const product = view.products.find((item) => item.key === key);
    if (!product || product.stock === null) return 0n;
    const used = authoritative.lines.filter((item) => item.productKey === key).reduce((sum, item) => sum + scaled(item.quantity, 3)!, 0n);
    const remaining = scaled(product.stock, 3)! - used;
    return remaining > 0n ? remaining : 0n;
  };
  const makeLines = (selection: SelectionView): LineView[] | null => {
    const requested = scaled(selection.quantity, 3);
    if (requested === null || !validQuantity(selection.quantity, '1')) return null;
    if (selection.mode === 'split' && view.products.find((item) => item.key === 'demo-original')?.stock === null) return null;
    const original = available('demo-original');
    const first = requested < original ? requested : original;
    const parts = selection.mode === 'split'
      ? [{ key: 'demo-original', count: first }, { key: 'demo-alternative', count: requested - first }]
      : [{ key: selection.mode === 'replace' ? 'demo-alternative' : selection.productKey, count: requested }];
    const lines: LineView[] = [];
    for (const part of parts.filter((item) => item.count > 0n)) {
      const product = view.products.find((item) => item.key === part.key);
      if (!product || product.price === null || product.stock === null || product.freshness !== 'fresh' || part.count > available(part.key)) return null;
      lines.push({ productKey: product.key, article: product.article, title: product.title, quantity: decimal(part.count, 3),
        unit: product.unit, warehouse: product.warehouse, unitPrice: product.price, currency: product.currency,
        offerVersion: product.offerVersion, total: decimal(scaled(product.price, 2)! * part.count / 1000n, 2) });
    }
    return lines.length ? lines : null;
  };
  const invalidate = (conversation: string) => {
    view = { ...view, proposals: view.proposals.map((item) => item.conversationKey === conversation && item.state === 'pending'
      ? { ...item, state: 'superseded', note: 'Выбор или контекст изменён. Нужно новое предложение и согласие.' } : item) };
  };
  const prepare = (conversation: string) => {
    if (blocked()) return;
    const selection = view.selections[conversation];
    const lines = selection && makeLines(selection);
    if (!lines) { notice('Нельзя составить предложение: проверьте количество и подтверждённый остаток.'); return; }
    if (view.proposals.length >= 200) { notice('Лимит учебных предложений. Очистите локальное демо для продолжения.'); return; }
    invalidate(conversation);
    const revision = Math.max(0, ...view.proposals.filter((item) => item.conversationKey === conversation).map((item) => item.revision)) + 1;
    const next: ProposalView = { key: crypto.randomUUID(), revision, conversationKey: conversation, expectedCartVersion: authoritative.version, origin: 'catalog',
      lines, total: total(lines), currency: 'KZT', expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(), state: 'pending', operationKey: null, note: null };
    view = { ...view, proposals: [...view.proposals, next], notice: null };
    publish();
  };
  const confirm: CommerceDriver['confirm'] = (conversation, key, revision) => {
    if (blocked()) return;
    const item = view.proposals.find((entry) => entry.key === key && entry.conversationKey === conversation && entry.revision === revision);
    if (!item) return;
    const previous = operations.find((op) => op.proposal === key);
    if (previous) { update(key, { state: 'confirmed' }); view = { ...view, cart: authoritative }; publish(); return; }
    if (item.state !== 'pending') return;
    if (Date.parse(item.expiresAt) <= Date.now()) { update(key, { state: 'expired', note: 'Срок предложения истёк. Запросите новое.' }); publish(); return; }
    const operationKey = item.operationKey ?? crypto.randomUUID();
    update(key, { state: 'confirming', operationKey });
    view = { ...view, busy: true, notice: null };
    const scenario = view.scenario;
    publish();
    timer = setTimeout(() => {
      if (disposed) return;
      view = { ...view, busy: false, scenario: 'normal' };
      if (Date.parse(item.expiresAt) <= Date.now()) { update(key, { state: 'expired', note: 'Предложение истекло до фиксации. Корзина не изменена.' }); publish(); return; }
      if (scenario === 'stock-changed' || scenario === 'price-changed' || scenario === 'offer-unavailable') {
        view = { ...view, products: view.products.map((product) => product.key !== 'demo-original' ? product : {
          ...product, offerVersion: product.offerVersion + 1, observedAt: new Date().toISOString(),
          stock: scenario === 'stock-changed' ? '7' : scenario === 'offer-unavailable' ? null : product.stock,
          price: scenario === 'price-changed' ? '27930.00' : product.price,
          freshness: scenario === 'offer-unavailable' ? 'unknown' : product.freshness,
        }) };
      }
      if (scenario === 'stale-cart') authoritative = { ...authoritative, version: authoritative.version + 1, observedAt: new Date().toISOString() };
      if (scenario === 'not-found') { update(key, { state: 'failed', note: 'Предложение не найдено в текущей сессии. Корзина не изменена.' }); publish(); return; }
      const mismatch = authoritative.version !== item.expectedCartVersion || item.lines.some((entry) => {
        const product = view.products.find((product) => product.key === entry.productKey);
        return !product || product.freshness !== 'fresh' || product.price !== entry.unitPrice || product.offerVersion !== entry.offerVersion
          || scaled(entry.quantity, 3)! > available(entry.productKey);
      });
      if (mismatch) {
        update(key, { state: 'superseded', note: 'Цена, остаток или версия корзины изменились. Ничего не добавлено. Проверьте новое предложение и подтвердите отдельно.' });
        view = { ...view, cart: authoritative };
        publish();
        if (item.origin !== 'attachment') prepare(conversation);
        return;
      }
      // Atomic sample commit. Visible cart is refreshed only after a known outcome.
      const lines = [...authoritative.lines, ...item.lines.map((entry) => ({ ...entry }))];
      if (lines.length > 100) { update(key, { state: 'failed', note: 'Достигнут лимит строк учебной корзины. Ничего не добавлено.' }); publish(); return; }
      authoritative = { version: authoritative.version + 1, lines, total: total(lines), currency: 'KZT', observedAt: new Date().toISOString() };
      operations = [...operations, { key: operationKey, proposal: key, cart: authoritative }];
      if (scenario === 'unknown-outcome') {
        update(key, { state: 'outcome_unknown', note: 'Ответ на подтверждение потерян. Результат неизвестен — запросите статус; не отправляйте новое добавление.' });
      } else {
        update(key, { state: 'confirmed', note: 'Учебная операция подтверждена. Показан сохранённый локальный снимок корзины.' });
        view = { ...view, cart: authoritative };
      }
      publish();
    }, 650);
  };
  return {
    getSnapshot: () => view,
    subscribe: (listener) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    select: (conversation, selection) => {
      if (blocked()) return;
      invalidate(conversation);
      view = { ...view, selections: { ...view.selections, [conversation]: { ...selection } }, notice: null };
      publish();
    },
    prepare,
    prepareReviewed: (conversation, source) => {
      if (blocked() || !source.jobKey || !Number.isSafeInteger(source.version) || source.version < 1 || !source.lines.length || source.lines.length > 100) return null;
      const lines: LineView[] = [];
      for (const selection of source.lines) {
        const next = makeLines({ mode: 'single', productKey: selection.productKey, quantity: selection.quantity });
        if (!next) { notice('Проверенные строки требуют уточнения товара или количества. Предложение не создано.'); return null; }
        lines.push(...next);
      }
      for (const productKey of new Set(lines.map((line) => line.productKey))) {
        const needed = lines.filter((line) => line.productKey === productKey).reduce((sum, line) => sum + scaled(line.quantity, 3)!, 0n);
        if (needed > available(productKey)) { notice('Суммарное количество в проверенных строках превышает остаток. Уточните состав.'); return null; }
      }
      if (view.proposals.length >= 200) { notice('Лимит учебных предложений. Очистите локальное демо для продолжения.'); return null; }
      invalidate(conversation);
      const revision = Math.max(0, ...view.proposals.filter((item) => item.conversationKey === conversation).map((item) => item.revision)) + 1;
      const proposal: ProposalView = { key: crypto.randomUUID(), revision, conversationKey: conversation, expectedCartVersion: authoritative.version,
        lines, total: total(lines), currency: 'KZT', expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
        state: 'pending', operationKey: null, origin: 'attachment', note: `Состав перенесён из учебной проверки файла, версия ${source.version}. Требуется отдельное подтверждение.` };
      view = { ...view, proposals: [...view.proposals, proposal], notice: null }; publish();
      return proposal.key;
    },
    confirm,
    reject: (conversation, key) => {
      if (blocked()) return;
      const item = view.proposals.find((entry) => entry.key === key && entry.conversationKey === conversation);
      if (item?.state !== 'pending') return;
      update(key, { state: 'rejected', note: 'Предложение отклонено. Корзина не изменена.' }); publish();
    },
    lookup: (key) => {
      if (disposed || view.busy) return;
      const item = view.proposals.find((entry) => entry.operationKey === key && entry.state === 'outcome_unknown');
      if (!item) return;
      const op = operations.find((entry) => entry.key === key && entry.proposal === item.key);
      update(item.key, { state: op ? 'confirmed' : 'failed', note: op ? 'Статус: учебная операция выполнена. Повторного добавления не было.' : 'Учебная операция не зафиксирована. Создайте новое предложение.' });
      view = { ...view, cart: authoritative }; publish();
    },
    refreshCart: () => { if (!disposed) { view = { ...view, cart: authoritative }; publish(); } },
    invalidateSelection: (conversation) => { if (!blocked()) { invalidate(conversation); publish(); } },
    setScenario: (scenario) => { if (!blocked()) { view = { ...view, scenario }; publish(); } },
    clearPrivateData: () => { clearTimeout(timer); view = fresh(); authoritative = emptyCart(); operations = []; publish(); },
    dispose: () => { disposed = true; clearTimeout(timer); listeners.clear(); },
  };
}
