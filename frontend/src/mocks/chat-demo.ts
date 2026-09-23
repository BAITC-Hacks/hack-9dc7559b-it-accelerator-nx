import type {
  ChatDriver, ContextField, ConversationView, ReplyView, WorkspaceView,
} from '../components/chat/model';
import { isReplyActive } from '../components/chat/model';
import { applyReplyUpdate } from '../components/chat/reducer';

// A local UI simulator, not HTTP/SSE fixtures or a substitute backend contract.
// Never loaded into the live application. No actual stock, cart or AI operations.
const STORAGE_KEY = 'hackalem.ui-chat-demo.v1';
const PAGE_SIZE = 12;
const CHUNK_SIZE = 14;
const id = () => crypto.randomUUID();
const now = () => new Date().toISOString();

function emptyConversation(): ConversationView {
  return {
    key: id(), title: 'Новый диалог', updatedAt: now(), draft: '', messages: [],
    context: [], reply: null, visibleCount: PAGE_SIZE, loadingEarlier: false,
  };
}

function demoContext(text: string, previous: ContextField[]): ContextField[] {
  const fields = new Map(previous.map((field) => [field.label, field.value]));
  const lower = text.toLowerCase();
  if (/автомат|выключател/.test(lower)) fields.set('Категория', 'Автоматические выключатели');
  if (/светильник|освещени/.test(lower)) fields.set('Категория', 'Освещение');
  if (/legrand/.test(lower)) fields.set('Бренд', 'Legrand');
  const current = lower.match(/(\d+(?:[.,]\d+)?)\s*(?:ампер|а(?=\s|$|[,.;]))/u);
  if (current) fields.set('Номинальный ток', `${current[1]} А`);
  const quantity = lower.match(/(?:нужно|нужн[оы]|количество)\s+(\d+)|(?:^|\s)(\d+)\s*(?:шт|штук)/u);
  if (quantity) fields.set('Количество', `${quantity[1] ?? quantity[2]} шт.`);
  if (/дешевле|бюджет/.test(lower)) fields.set('Предпочтение', 'Более доступные варианты');
  if (/первые два/.test(lower)) fields.set('Действие', 'Сравнить первые два варианта');
  return [...fields].map(([label, value]) => ({ label, value }));
}

function demoAnswer(chat: ConversationView): string {
  const prompt = chat.reply?.prompt.toLowerCase() ?? '';
  const remembered = chat.context.map((item) => `${item.label.toLowerCase()}: ${item.value}`).join('; ');
  if (/достав|оплат|парти/.test(prompt)) {
    return 'Условия доставки и оплаты нужно сверить с актуальными документами ekt.kz.\n\nВ этом демо источник ещё не подключён, поэтому я не буду придумывать сроки и тарифы. Укажите город доставки — после подключения каталога продолжим с этими данными.';
  }
  if (/дешевле/.test(prompt)) {
    return `Сохранил параметры подбора: ${remembered || 'те же, что в предыдущем сообщении'}.\n\nПри поиске более доступного варианта важно сохранить обязательные характеристики. В демонстрации я запоминаю ваше предпочтение; конкретные товары и цены появятся после подключения каталога.`;
  }
  if (/первые два/.test(prompt)) {
    return 'Запомнил: нужно сравнить первые два варианта из предыдущего подбора.\n\nВ демонстрации нет подтверждённой выдачи товаров. После подключения каталога сравнение будет связано с конкретным результатом поиска, а не с произвольными товарами.';
  }
  if (/добав|корзин|подтвержда/.test(prompt) || /^(да|ага|ок|окей|yes|согласен)[.!\s]*$/iu.test(prompt.trim())) {
    return 'Это сообщение не добавляет товары в корзину. Какое предложение вы хотите подтвердить? Проверьте состав и нажмите «Подтвердить этот состав» на нужной карточке.\n\nНиже — синтетический учебный набор. Фактический результат операции показывают карточка предложения и сохранённый снимок демо-корзины, а не текст этого ответа.';
  }
  if (/\d{5,}|артикул/.test(prompt)) {
    return 'Принял запрос по артикулу. Для точного совпадения потребуется подключённый каталог: только оттуда можно получить характеристики, цену и наличие.\n\nСейчас открыт локальный пример диалога. Название похожего товара не будет выдано за точное совпадение.';
  }
  if (chat.context.length) {
    return `Зафиксировал параметры: ${remembered}.\n\nПродолжайте подбор в этом диалоге — можно уточнить количество или написать «дешевле». После подключения каталога здесь появятся проверенные варианты.\n\nЭто демонстрационный ответ: цены, наличие и совместимость пока не проверяются.`;
  }
  return 'Помогу уточнить запрос. Напишите название товара или артикул, нужное количество и известные характеристики.\n\nНапример: «Нужен автомат Legrand, 40 ампер, 3 штуки». Параметры сохранятся в этом диалоге.\n\nСейчас это локальная демонстрация интерфейса; фактические предложения появятся после подключения каталога.';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function isReply(value: unknown): value is ReplyView {
  if (!isRecord(value)) return false;
  return ['key', 'messageKey', 'submissionKey', 'prompt'].every((key) => typeof value[key] === 'string')
    && ['sending', 'streaming', 'recovering', 'interrupted', 'completed', 'cancelled', 'failed'].includes(String(value.phase))
    && ['generation', 'lastChunk', 'recoveryCount'].every((key) => typeof value[key] === 'number' && Number.isSafeInteger(value[key]) && Number(value[key]) >= 0)
    && typeof value.faultShown === 'boolean'
    && (value.notice === null || typeof value.notice === 'string')
    && (value.retryAt === null || typeof value.retryAt === 'number');
}
function isConversation(value: unknown): value is ConversationView {
  if (!isRecord(value)) return false;
  return ['key', 'title', 'updatedAt', 'draft'].every((key) => typeof value[key] === 'string')
    && typeof value.loadingEarlier === 'boolean' && typeof value.visibleCount === 'number' && Number.isSafeInteger(value.visibleCount)
    && Number(value.visibleCount) > 0
    && Array.isArray(value.messages) && value.messages.length <= 500
    && value.messages.every((message: unknown) => isRecord(message)
      && typeof message.key === 'string' && typeof message.text === 'string'
      && message.text.length <= 20_000 && typeof message.createdAt === 'string'
      && ['customer', 'assistant'].includes(String(message.author)))
    && Array.isArray(value.context) && value.context.every((field: unknown) => isRecord(field)
      && typeof field.label === 'string' && typeof field.value === 'string')
    && (value.reply === null || isReply(value.reply));
}

export function createDemoChatDriver(onSessionExpired?: () => void): ChatDriver {
  let storage: Storage | undefined;
  try { storage = window.sessionStorage; } catch { /* Private iframe may deny storage. */ }
  let state: WorkspaceView = {
    mode: 'demo', conversations: [], selectedKey: null, scenario: 'normal',
    banner: null, storageAvailable: !!storage,
  };
  try {
    const saved: unknown = JSON.parse(storage?.getItem(STORAGE_KEY) ?? 'null');
    if (isRecord(saved) && saved.version === 1 && Array.isArray(saved.conversations)
      && saved.conversations.length <= 30 && saved.conversations.every(isConversation)) {
      state.conversations = saved.conversations.map<ConversationView>((chat) => ({
        ...chat, loadingEarlier: false,
        reply: chat.reply && isReplyActive(chat.reply) ? {
          ...chat.reply, phase: 'interrupted', notice: 'Незавершённый ответ восстановлен. Можно продолжить.',
        } : chat.reply,
      }));
      state.selectedKey = typeof saved.selectedKey === 'string'
        && state.conversations.some((chat) => chat.key === saved.selectedKey) ? saved.selectedKey : state.conversations[0]?.key ?? null;
    }
  } catch { state.banner = 'Локальную историю не удалось восстановить. Можно начать новый диалог.'; }
  if (!state.conversations.length) {
    const chat = emptyConversation();
    state = { ...state, conversations: [chat], selectedKey: chat.key };
  }

  let disposed = false;
  let lifecycle = 0;
  const listeners = new Set<() => void>();
  const timers = new Map<string, ReturnType<typeof setTimeout>>();

  function publish() {
    if (disposed) return;
    try {
      storage?.setItem(STORAGE_KEY, JSON.stringify({
        version: 1, conversations: state.conversations, selectedKey: state.selectedKey,
      }));
    } catch {
      state = { ...state, storageAvailable: false };
    }
    listeners.forEach((listener) => listener());
  }
  const getChat = (key: string) => state.conversations.find((chat) => chat.key === key);
  function change(key: string, update: (chat: ConversationView) => ConversationView) {
    if (disposed) return;
    state = { ...state, conversations: state.conversations.map((chat) => chat.key === key ? update(chat) : chat) };
    publish();
  }
  function schedule(key: string, action: () => void, delay: number) {
    clearTimeout(timers.get(key));
    const generation = lifecycle;
    timers.set(key, setTimeout(() => {
      timers.delete(key);
      if (!disposed && generation === lifecycle) action();
    }, delay));
  }
  function stopTimers() {
    lifecycle++;
    timers.forEach(clearTimeout);
    timers.clear();
  }
  function clearPrivateData() {
    stopTimers();
    try { storage?.removeItem(STORAGE_KEY); } catch { /* memory is cleared regardless */ }
    const chat = emptyConversation();
    state = { ...state, conversations: [chat], selectedKey: chat.key, banner: 'Предыдущая сессия завершена. Начните новый диалог.' };
    publish();
  }

  function recover(key: string, fromExpiredJournal = false) {
    const original = getChat(key);
    if (!original?.reply || ['completed', 'cancelled', 'failed'].includes(original.reply.phase)) return;
    if (original.reply.retryAt && original.reply.retryAt > Date.now()) return;
    const runKey = original.reply.key;
    change(key, (chat) => ({ ...chat, reply: { ...chat.reply!, phase: 'recovering', notice: null, retryAt: null } }));
    schedule(`reply:${key}`, () => {
      const current = getChat(key);
      if (!current?.reply || current.reply.key !== runKey || current.reply.phase !== 'recovering') return;
      const text = fromExpiredJournal ? demoAnswer(current)
        : current.messages.find((message) => message.key === current.reply?.messageKey)?.text ?? '';
      change(key, (chat) => applyReplyUpdate(chat, {
        kind: 'snapshot', runKey, generation: current.reply!.generation + 1,
        ordinal: fromExpiredJournal ? Math.ceil(text.length / CHUNK_SIZE) : current.reply!.lastChunk,
        text, phase: fromExpiredJournal ? 'completed' : 'streaming',
      }));
      if (!fromExpiredJournal) tick(key);
    }, 650);
  }

  function tick(key: string) {
    const chat = getChat(key);
    if (!chat?.reply || chat.reply.phase !== 'streaming') return;
    const reply = chat.reply;
    const answer = demoAnswer(chat);
    if (!reply.faultShown && reply.lastChunk >= 4 && ['disconnect', 'expired'].includes(state.scenario)) {
      change(key, (current) => ({ ...current, reply: {
        ...current.reply!, faultShown: true, phase: 'recovering', recoveryCount: reply.recoveryCount + 1,
        notice: 'Соединение прервалось. Восстанавливаем ответ…',
      } }));
      if (reply.recoveryCount >= 2) {
        change(key, (current) => ({ ...current, reply: { ...current.reply!, phase: 'interrupted', notice: 'Автоматическое восстановление остановлено. Попробуйте ещё раз.' } }));
      } else recover(key, state.scenario === 'expired');
      return;
    }
    const text = answer.slice(reply.lastChunk * CHUNK_SIZE, (reply.lastChunk + 1) * CHUNK_SIZE);
    if (!text) {
      change(key, (current) => applyReplyUpdate(current, {
        kind: 'snapshot', runKey: reply.key, generation: reply.generation,
        ordinal: reply.lastChunk, text: answer, phase: 'completed',
      }));
      return;
    }
    const update = { kind: 'chunk' as const, runKey: reply.key, generation: reply.generation, ordinal: reply.lastChunk + 1, text };
    change(key, (current) => applyReplyUpdate(current, update));
    if (state.scenario === 'duplicate') change(key, (current) => applyReplyUpdate(current, update));
    schedule(`reply:${key}`, () => tick(key), 55);
  }

  function accept(key: string) {
    const chat = getChat(key);
    if (!chat?.reply) return;
    if (state.scenario === 'unauthorized' && !chat.reply.faultShown) {
      onSessionExpired?.();
      clearPrivateData();
      return;
    }
    if (!chat.reply.faultShown && ['busy', 'send-timeout'].includes(state.scenario)) {
      change(key, (current) => ({ ...current, reply: {
        ...current.reply!, phase: 'interrupted', faultShown: true,
        retryAt: state.scenario === 'busy' ? Date.now() + 5000 : null,
        notice: state.scenario === 'busy' ? 'Слишком много запросов. Подождите несколько секунд.'
          : 'Ответ на отправку потерян. Повтор использует тот же запрос и не добавляет сообщение заново.',
      } }));
      return;
    }
    change(key, (current) => ({ ...current, reply: { ...current.reply!, phase: 'streaming', notice: null, retryAt: null } }));
    tick(key);
  }

  return {
    getSnapshot: () => state,
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    newConversation() {
      if (state.conversations.length >= 30) {
        state = { ...state, banner: 'В демо сохранено 30 диалогов. Очистите локальную историю, чтобы начать новый.' };
        publish(); return;
      }
      const chat = emptyConversation();
      state = { ...state, conversations: [chat, ...state.conversations], selectedKey: chat.key, banner: null };
      publish();
    },
    selectConversation(key) {
      if (!getChat(key)) return;
      state = { ...state, selectedKey: key }; publish();
    },
    setDraft(key, text) { change(key, (chat) => ({ ...chat, draft: text.slice(0, 4000) })); },
    send(key, raw) {
      const text = raw.trim();
      const chat = getChat(key);
      if (!text || text.length > 4000 || !chat || isReplyActive(chat.reply) || chat.reply?.phase === 'interrupted') return;
      if (chat.messages.length >= 500) {
        state = { ...state, banner: 'Этот демонстрационный диалог заполнен. Начните новый.' }; publish(); return;
      }
      const timestamp = now();
      const answerKey = id();
      // Created once by the user action; resume keeps submissionKey and run key.
      const reply: ReplyView = {
        key: id(), submissionKey: id(), messageKey: answerKey, prompt: text,
        phase: 'sending', generation: 1, lastChunk: 0, recoveryCount: 0,
        faultShown: false, notice: null, retryAt: null,
      };
      change(key, (current) => ({
        ...current, draft: '', title: current.messages.length ? current.title : text.slice(0, 48),
        updatedAt: timestamp, context: demoContext(text, current.context), reply,
        visibleCount: Math.max(PAGE_SIZE, current.visibleCount),
        messages: [...current.messages,
          { key: id(), author: 'customer', text, createdAt: timestamp },
          { key: answerKey, author: 'assistant', text: '', createdAt: timestamp },
        ],
      }));
      schedule(`reply:${key}`, () => accept(key), 400);
    },
    resume(key) {
      const chat = getChat(key);
      if (!chat?.reply || chat.reply.phase !== 'interrupted') return;
      if (chat.reply.retryAt && chat.reply.retryAt > Date.now()) return;
      // The local request identity is retained, including timeout-after-accept.
      if (!chat.reply.lastChunk) accept(key);
      else recover(key);
    },
    stop(key) {
      const chat = getChat(key);
      if (!chat?.reply || ['completed', 'cancelled', 'failed'].includes(chat.reply.phase)) return;
      clearTimeout(timers.get(`reply:${key}`)); timers.delete(`reply:${key}`);
      change(key, (current) => ({ ...current, reply: {
        ...current.reply!, phase: 'cancelled', generation: current.reply!.generation + 1,
        notice: 'Ответ остановлен. Уже полученный текст сохранён.', retryAt: null,
      } }));
    },
    loadEarlier(key) {
      const chat = getChat(key);
      if (!chat || chat.loadingEarlier || chat.visibleCount >= chat.messages.length) return;
      change(key, (current) => ({ ...current, loadingEarlier: true }));
      schedule(`history:${key}`, () => change(key, (current) => ({
        ...current, loadingEarlier: false, visibleCount: current.visibleCount + PAGE_SIZE,
      })), 250);
    },
    setScenario(scenario) { state = { ...state, scenario }; publish(); },
    clearPrivateData,
    dispose() { stopTimers(); disposed = true; listeners.clear(); },
  };
}
