import { cancelRun, createConversation, getDialogue, getRun, listConversations, listMessages, streamRun, submitMessage } from '../client';
import type { ChatEvent, Conversation, Message, RunSnapshot, TurnRequest } from '../client';
import type { ChatDriver, ConversationView, MessageView, ReplyPhase, WorkspaceView } from '../components/chat/model';
import { auth } from './api';
import { queryClient } from './query';
import { streamEvents } from './stream';
import { apiError } from './api-errors';
import { isAxiosError } from 'axios';

const terminal = (status?: string) => ['completed', 'cancelled', 'failed'].includes(status ?? '');
const phase = (status?: string): ReplyPhase => terminal(status) ? status as ReplyPhase : 'streaming';
const noOp = () => {};
function stored(key: string) { try { return sessionStorage.getItem(key); } catch { return null; } }
function store(key: string, value: string | null) { try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, value); } catch { /* iframe storage may be blocked */ } }

export function messageView(message: Message): MessageView {
  return { key: message.id ?? crypto.randomUUID(), author: message.role === 'user' ? 'customer' : 'assistant', text: message.text ?? '', createdAt: message.createdAt ?? new Date().toISOString() };
}

/** Full snapshots are authoritative; replayed deltas are filtered by wire counters. */
export function applyLiveEvent(chat: ConversationView, event: ChatEvent, previous?: { epoch: string; sequence: string }): ConversationView {
  if (!chat.reply || event.runId !== chat.reply.key || !event.payload) return chat;
  const payload = event.payload;
  if (payload.kind !== 'terminal' && previous && (BigInt(event.epoch ?? '0') < BigInt(previous.epoch)
    || (event.epoch === previous.epoch && BigInt(event.seq ?? '0') <= BigInt(previous.sequence)))) return chat;
  if (payload.kind === 'delta') return { ...chat, messages: chat.messages.map((message) => message.key === chat.reply?.messageKey ? { ...message, text: message.text + (payload.text ?? '') } : message), reply: { ...chat.reply, phase: 'streaming' } };
  if (payload.kind === 'terminal' && payload.snapshot) {
    const snapshot = payload.snapshot;
    return { ...chat, messages: chat.messages.map((message) => message.key === chat.reply?.messageKey ? { ...message, text: snapshot.text ?? '' } : message), reply: { ...chat.reply, phase: phase(snapshot.status), notice: typeof snapshot.errorCode === 'string' ? snapshot.errorCode : null } };
  }
  if (payload.kind === 'status') return { ...chat, reply: { ...chat.reply, phase: 'streaming', notice: typeof payload.tool === 'string' ? payload.tool : null } };
  return { ...chat, events: [...(chat.events ?? []).filter((item) => item.eventId !== event.eventId), event] };
}

export function createLiveChatDriver(): ChatDriver {
  let view: WorkspaceView = { mode: 'loading', conversations: [], selectedKey: null, scenario: 'normal', banner: null, storageAvailable: true };
  const listeners = new Set<() => void>();
  const lifecycle = new AbortController();
  const queryScope = ['live-chat', crypto.randomUUID()];
  const signal = AbortSignal.any([lifecycle.signal, auth.snapshot().signal]);
  const streams = new Map<string, AbortController>();
  const cursors = new Map<string, { epoch: string; sequence: string }>();
  const pending = new Map<string, { key: string; body: TurnRequest; runId?: string }>();
  const submitting = new Set<string>();
  let creating = false;
  const emit = (next: WorkspaceView) => { if (signal.aborted) return; view = next; listeners.forEach((listener) => listener()); };
  const patch = (id: string, update: (chat: ConversationView) => ConversationView) => emit({ ...view, conversations: view.conversations.map((chat) => chat.key === id ? update(chat) : chat) });
  const chatFor = (id: string) => view.conversations.find((chat) => chat.key === id);
  const runTask = (work: () => Promise<void>) => { void work().catch((error) => { if (!signal.aborted) emit({ ...view, banner: apiError(error) }); }); };
  const query = <T,>(key: string[], load: (requestSignal: AbortSignal) => Promise<T>) => queryClient.fetchQuery({ queryKey: [...queryScope, ...key], staleTime: 0, retry: false, queryFn: ({ signal: requestSignal }) => load(AbortSignal.any([signal, requestSignal])) });
  function announce(id: string) { if (view.selectedKey !== id) return; store('hackalem.active-conversation', id); window.dispatchEvent(new CustomEvent('hackalem:conversation', { detail: { id } })); }
  function fresh(conversation: Conversation, draft = ''): ConversationView {
    return { key: conversation.id!, title: 'Новый диалог', updatedAt: conversation.createdAt ?? new Date().toISOString(), draft, messages: [], context: [], reply: null, visibleCount: 30, loadingEarlier: false, events: [] };
  }
  async function loadState(id: string) {
    const data = await query(['dialogue', id], async (requestSignal) => (await getDialogue({ path: { id }, signal: requestSignal, throwOnError: true })).data);
    patch(id, (chat) => ({ ...chat, dialogue: data, context: [
      ...(typeof data.category === 'string' ? [{ label: 'Категория', value: data.category }] : []),
      ...Object.entries(data.hardConstraints ?? {}).map(([label, value]) => ({ label, value })),
      ...(data.selectedArticles?.length ? [{ label: 'Выбрано', value: data.selectedArticles.join(', ') }] : []),
      ...(typeof data.attachmentId === 'string' ? [{ label: 'Спецификация', value: data.attachmentId }] : []),
    ] }));
  }
  function snapshot(id: string, run: RunSnapshot) {
    cursors.set(run.id!, { epoch: run.epoch ?? '0', sequence: run.sequence ?? '0' });
    patch(id, (chat) => applyLiveEvent(chat, { runId: run.id, payload: { kind: 'terminal', snapshot: run } }));
    if (terminal(run.status) && pending.get(id)?.runId === run.id) { pending.delete(id); store(`hackalem.pending.${id}`, null); }
  }
  async function listen(id: string, runId: string) {
    streams.get(id)?.abort();
    const controller = new AbortController(); streams.set(id, controller);
    const streamSignal = AbortSignal.any([signal, controller.signal]);
    try {
      while (!streamSignal.aborted) {
        const cursor = cursors.get(runId);
        const events = streamEvents((options) => streamRun({ ...options, path: { id: runId } }), {
          session: auth, signal: streamSignal, lastEventId: cursor ? `${cursor.epoch}:${cursor.sequence}` : undefined,
          isTerminal: (event) => event.payload?.kind === 'terminal',
        });
        for await (const event of events) {
          if (event.runId !== runId || event.schemaVersion !== '1') continue;
          const previous = cursors.get(runId);
          if (event.payload?.kind !== 'terminal' && previous && BigInt(event.seq ?? '0') <= BigInt(previous.sequence)) continue;
          if (event.payload?.kind !== 'terminal' && previous && (event.epoch !== previous.epoch || BigInt(event.seq ?? '0') !== BigInt(previous.sequence) + 1n)) {
            const run = (await getRun({ path: { id: runId }, signal: streamSignal, throwOnError: true })).data;
            snapshot(id, run); break;
          }
          patch(id, (chat) => applyLiveEvent(chat, event, previous));
          cursors.set(runId, { epoch: event.epoch ?? '0', sequence: event.seq ?? '0' });
          if (view.selectedKey === id) window.dispatchEvent(new CustomEvent('hackalem:chat-event', { detail: event }));
          if (event.payload?.kind === 'terminal' && event.payload.snapshot) snapshot(id, event.payload.snapshot);
        }
        const run = (await getRun({ path: { id: runId }, signal: streamSignal, throwOnError: true })).data;
        snapshot(id, run);
        if (terminal(run.status)) { await loadState(id); announce(id); return; }
      }
    } catch (error) {
      if (!streamSignal.aborted) patch(id, (chat) => ({ ...chat, reply: chat.reply ? { ...chat.reply, phase: 'interrupted', notice: apiError(error) } : null }));
    } finally { if (streams.get(id) === controller) streams.delete(id); }
  }
  async function loadConversation(id: string) {
    // Server cursors go forwards. Load bounded pages, then show the last 30 locally.
    const messages: Message[] = []; let cursor: string | undefined;
    do {
      const page = await query(['messages', id, cursor ?? 'first'], async (requestSignal) => (await listMessages({ path: { id }, query: { cursor, limit: 100 }, signal: requestSignal, throwOnError: true })).data);
      messages.push(...(page.items ?? [])); cursor = typeof page.nextCursor === 'string' ? page.nextCursor : undefined;
    } while (cursor && !signal.aborted);
    if (signal.aborted) return;
    const last = messages.at(-1);
    patch(id, (chat) => ({ ...chat, messages: messages.map(messageView), title: messages.find((item) => item.role === 'user')?.text?.slice(0, 48) || 'Новый диалог', visibleCount: 30, reply: null }));
    await loadState(id);
    if (last?.runId) {
      const run = await query(['run', last.runId], async (requestSignal) => (await getRun({ path: { id: last.runId! }, signal: requestSignal, throwOnError: true })).data);
      const assistant = last.role === 'assistant' ? last.id! : `reply-${run.id}`;
      patch(id, (chat) => ({ ...chat, messages: last.role === 'assistant' ? chat.messages : [...chat.messages, { key: assistant, author: 'assistant', text: run.text ?? '', createdAt: run.createdAt ?? chat.updatedAt }], reply: { key: run.id!, messageKey: assistant, submissionKey: '', prompt: '', phase: phase(run.status), generation: 0, lastChunk: 0, recoveryCount: 0, faultShown: false, notice: null, retryAt: null } }));
      snapshot(id, run);
      if (!terminal(run.status)) void listen(id, run.id!);
    }
    const saved = stored(`hackalem.pending.${id}`);
    if (saved && (!chatFor(id)?.reply || ['completed', 'failed', 'cancelled'].includes(chatFor(id)?.reply?.phase ?? ''))) {
      try {
        const value = JSON.parse(saved) as { key?: unknown; body?: TurnRequest };
        if (typeof value.key === 'string' && typeof value.body?.text === 'string') {
          pending.set(id, { key: value.key, body: value.body });
          patch(id, (chat) => ({ ...chat, draft: value.body!.text ?? '', reply: { key: value.key as string, messageKey: '', submissionKey: value.key as string, prompt: value.body!.text ?? '', phase: 'interrupted', generation: 0, lastChunk: 0, recoveryCount: 0, faultShown: false, notice: 'Статус отправки неизвестен. Продолжите с тем же ключом запроса.', retryAt: null } }));
        }
      } catch { store(`hackalem.pending.${id}`, null); }
    }
    announce(id);
  }
  async function send(id: string, text: string, retry = false) {
    let chat = chatFor(id); if (!chat || !text.trim() || signal.aborted || submitting.has(id)) return;
    if (!retry && chat.reply && ['sending', 'streaming', 'recovering', 'interrupted'].includes(chat.reply.phase)) return;
    submitting.add(id);
    try { if (!retry) { await loadState(id); chat = chatFor(id)!; } }
    catch (error) { submitting.delete(id); throw error; }
    let submission = pending.get(id);
    if (!submission || !retry) {
      submission = { key: crypto.randomUUID(), body: { text: text.trim(), ...(chat.dialogue?.version ? { expectedStateVersion: chat.dialogue.version } : {}) } };
      pending.set(id, submission); store(`hackalem.pending.${id}`, JSON.stringify(submission));
    }
    const messageKey = `reply-${submission.key}`;
    patch(id, (current) => ({ ...current, draft: '', title: current.messages.length ? current.title : text.slice(0, 48), messages: retry && current.messages.some((message) => message.key === messageKey) ? current.messages : [...current.messages, { key: `user-${submission.key}`, author: 'customer', text, createdAt: new Date().toISOString() }, { key: messageKey, author: 'assistant', text: '', createdAt: new Date().toISOString() }], reply: { key: submission.key, messageKey, submissionKey: submission.key, prompt: text, phase: 'sending', generation: 0, lastChunk: 0, recoveryCount: 0, faultShown: false, notice: null, retryAt: null } }));
    try {
      const { data } = await submitMessage({ path: { id }, headers: { 'Idempotency-Key': submission.key }, body: submission.body, signal, throwOnError: true });
      if (!data.runId) throw new Error('Сервер не вернул идентификатор ответа.');
      submission.runId = data.runId;
      store(`hackalem.pending.${id}`, JSON.stringify(submission));
      patch(id, (current) => ({ ...current, reply: current.reply ? { ...current.reply, key: data.runId!, phase: 'streaming' } : null }));
      await listen(id, data.runId);
    } catch (error) {
      if (signal.aborted) return;
      if (isAxiosError(error) && [400, 403, 404, 409, 422].includes(error.response?.status ?? 0)) {
        pending.delete(id); store(`hackalem.pending.${id}`, null);
        patch(id, (current) => ({ ...current, draft: text, messages: current.messages.filter((message) => message.key !== messageKey && message.key !== `user-${submission.key}`), reply: null }));
        emit({ ...view, banner: apiError(error) });
        await loadState(id); return;
      }
      patch(id, (current) => ({ ...current, reply: current.reply ? { ...current.reply, phase: 'interrupted', notice: apiError(error) } : null }));
    } finally { submitting.delete(id); }
  }
  async function start() {
    let cursor: string | undefined; const conversations: ConversationView[] = [];
    do {
      const page = await query(['conversations', cursor ?? 'first'], async (requestSignal) => (await listConversations({ query: { cursor, limit: 100 }, signal: requestSignal, throwOnError: true })).data);
      conversations.push(...(page.items ?? []).filter((item) => item.id).map((item) => fresh(item)));
      cursor = typeof page.nextCursor === 'string' ? page.nextCursor : undefined;
    } while (cursor && !signal.aborted);
    const selected = stored('hackalem.active-conversation');
    const id = conversations.find((item) => item.key === selected)?.key ?? conversations.at(-1)?.key ?? null;
    emit({ ...view, mode: 'live', conversations: conversations.reverse(), selectedKey: id });
    if (id) await loadConversation(id);
  }
  const driver: ChatDriver = {
    getSnapshot: () => view,
    subscribe: (listener) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    newConversation(draft = '') { if (creating) return; creating = true; runTask(async () => { try { const { data } = await createConversation({ signal, throwOnError: true }); if (!data.id) throw new Error('Диалог не создан.'); emit({ ...view, mode: 'live', conversations: [fresh(data, draft), ...view.conversations], selectedKey: data.id, banner: null }); await loadState(data.id); announce(data.id); } finally { creating = false; } }); },
    selectConversation(id) { emit({ ...view, selectedKey: id, banner: null }); runTask(() => loadConversation(id)); },
    setDraft(id, text) { patch(id, (chat) => ({ ...chat, draft: text })); },
    send(id, text) { runTask(() => send(id, text)); },
    resume(id) { runTask(async () => { const chat = chatFor(id); if (!chat?.reply) return; if (chat.reply.key === chat.reply.submissionKey) { await send(id, chat.reply.prompt, true); return; } const { data } = await getRun({ path: { id: chat.reply.key }, signal, throwOnError: true }); snapshot(id, data); if (!terminal(data.status)) await listen(id, data.id!); }); },
    stop(id) { runTask(async () => { let chat = chatFor(id); if (!chat?.reply) return; if (chat.reply.key === chat.reply.submissionKey) { const item = pending.get(id); if (!item) return; const { data } = await submitMessage({ path: { id }, headers: { 'Idempotency-Key': item.key }, body: item.body, signal, throwOnError: true }); patch(id, (current) => ({ ...current, reply: current.reply ? { ...current.reply, key: data.runId! } : null })); chat = chatFor(id); } const { data } = await cancelRun({ path: { id: chat!.reply!.key }, signal, throwOnError: true }); streams.get(id)?.abort(); snapshot(id, data); }); },
    loadEarlier(id) { patch(id, (chat) => ({ ...chat, visibleCount: chat.visibleCount + 30 })); },
    setScenario: noOp,
    clearPrivateData() { streams.forEach((controller) => controller.abort()); pending.clear(); emit({ ...view, conversations: [], selectedKey: null }); },
    dispose() { lifecycle.abort(); streams.forEach((controller) => controller.abort()); queryClient.removeQueries({ queryKey: queryScope }); listeners.clear(); },
  };
  runTask(start);
  return driver;
}
