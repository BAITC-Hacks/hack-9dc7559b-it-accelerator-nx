import { isAxiosError } from 'axios';
import * as sdk from '../client/sdk.gen';
import type { Snapshot, Capabilities, Location, AttachmentSelection } from '../client/types.gen';
import type { AttachmentDriver, AttachmentJob, AttachmentView, FileFamily, ReviewRow } from '../components/attachments/model';
import { scaled } from '../components/catalog/decimal';
import { queryClient } from './query';
import { publishDialogue } from './dialogue-state';

const busyStages = new Set(['queued', 'extracting', 'ocr', 'matching']);
const families: FileFamily[] = ['xls', 'xlsx', 'doc', 'docx', 'pdf', 'jpg', 'jpeg'];
const errorMessages: Record<string, string> = {
  stale_attachment: 'Проверку уже изменили. Загружена актуальная версия — повторите действие.',
  attachment_not_ready: 'Дождитесь завершения распознавания.',
  invalid_quantity_step: 'Количество не соответствует минимальной партии или шагу товара.',
  invalid_unit: 'Выберите единицу измерения выбранного товара.',
  invalid_warehouse: 'Выберите доступный склад выбранного товара.',
  IMAGE_BLURRED_RETAKE: 'Фото размыто. Сделайте более чёткий снимок и загрузите заново.',
  OCR_UNAVAILABLE: 'Распознавание текста на изображениях сейчас недоступно.',
  VISION_UNAVAILABLE: 'Распознавание товара по фотографии сейчас недоступно.',
};
export function attachmentError(error: unknown): string {
  if (isAxiosError(error)) {
    const data: unknown = error.response?.data;
    const code = data && typeof data === 'object' && 'code' in data ? String(data.code) : '';
    if (code) return errorMessages[code] ?? `Не удалось выполнить действие: ${code}`;
    if (error.response?.status === 404) return 'Файл не найден или недоступен в вашей сессии.';
    if (error.response?.status === 413) return 'Файл превышает допустимый размер.';
    if (error.response?.status === 415) return 'Формат или содержимое файла не поддерживается.';
    if (error.response?.status === 429) return 'Слишком много запросов. Подождите немного и повторите.';
    return 'Backend недоступен. Проверьте соединение и повторите действие.';
  }
  return error instanceof Error ? error.message : 'Не удалось обработать файл.';
}
export function attachmentLocation(source?: Location): string {
  return [source?.sheet && `Лист ${source.sheet}`, source?.page && `Страница ${source.page}`,
    source?.row && `Строка ${source.row}`, source?.cell && `Ячейка ${source.cell}`,
    source?.paragraph && `Абзац ${source.paragraph}`].filter(Boolean).join(' · ') || 'Область документа';
}
export function mapAttachment(snapshot: Snapshot, previous?: AttachmentJob): AttachmentJob {
  if (!snapshot.attachmentId || !snapshot.conversationId || !snapshot.version) throw new Error('Сервер вернул неполное описание файла.');
  const version = Number(snapshot.version);
  if (!Number.isSafeInteger(version)) throw new Error('Не удалось прочитать версию проверки.');
  const state = snapshot.status?.toUpperCase();
  const stage = state === 'FAILED' ? 'failed' : state === 'READY' ? 'ready' : state === 'NEEDS_REVIEW' ? 'review'
    : snapshot.stage === 'ocr' ? 'ocr' : snapshot.stage === 'matching' ? 'matching' : (state === 'RUNNING' || state === 'PROCESSING') ? 'extracting' : 'queued';
  const extension = snapshot.filename?.split('.').pop()?.toLowerCase() as FileFamily;
  return {
    key: snapshot.attachmentId, conversation: snapshot.conversationId,
    fingerprint: previous?.fingerprint ?? snapshot.attachmentId, fileName: snapshot.filename ?? 'Документ',
    family: families.includes(extension) ? extension : 'pdf', size: previous?.size ?? 0,
    stage, progress: stage === 'queued' ? 5 : stage === 'extracting' ? 30 : stage === 'ocr' ? 55 : stage === 'matching' ? 80 : 100,
    version, serverVersion: snapshot.version, createdAt: previous?.createdAt ?? new Date().toISOString(),
    error: snapshot.errorCode ? errorMessages[snapshot.errorCode] ?? snapshot.errorCode : null,
    warnings: snapshot.warnings?.map((item) => errorMessages[item] ?? item) ?? [],
    proposalKey: previous?.serverVersion === snapshot.version ? previous.proposalKey : null,
    rows: (snapshot.rows ?? []).filter((row) => row.extracted?.id).map((row): ReviewRow => {
      const extracted = row.extracted!;
      const selection = snapshot.selections?.find((item) => item.rowId === extracted.id);
      const candidates = row.candidates ?? [];
      return {
        key: extracted.id!, location: attachmentLocation(extracted.source), rawText: extracted.rawText ?? '',
        article: extracted.article ?? null, quantity: selection?.selected ? selection.quantity ?? '' : extracted.quantity ?? '',
        unit: selection?.selected ? selection.unit ?? '' : extracted.unit ?? candidates[0]?.unit ?? '',
        warehouse: selection?.warehouse ?? '', reviewed: !!selection?.selected,
        selectedKey: selection?.selected ? selection.productId ?? null : null,
        candidateKeys: candidates.flatMap((candidate) => candidate.productId ? [candidate.productId] : []), candidates,
        excluded: selection?.selected === false,
        confidence: !extracted.quantity ? 'needs_quantity' : candidates.length > 1 ? 'ambiguous' : candidates.length === 1 ? 'matched' : 'unmatched',
        warnings: [...(extracted.warnings ?? []), ...(extracted.visualEvidence?.qualityFlags ?? [])],
        observations: [extracted.visualEvidence?.category, ...(extracted.visualEvidence?.visibleMarkings ?? []),
          ...Object.entries(extracted.visualEvidence?.observedAttributes ?? {}).map(([key, value]) => `${key}: ${value}`)].filter((item): item is string => !!item),
      };
    }),
  };
}
export function validReviewRow(row: ReviewRow) {
  const quantity = scaled(row.quantity, 6);
  const candidate = row.candidates?.find((item) => item.productId === row.selectedKey);
  const minimum = scaled(candidate?.minimum ?? '0.000001', 6);
  const step = scaled(candidate?.step ?? '0.000001', 6);
  return !!row.selectedKey && !!row.unit && !!row.warehouse && quantity !== null && quantity > 0n
    && minimum !== null && quantity >= minimum && step !== null && step > 0n && quantity % step === 0n;
}
function storageKey(token?: string) {
  try {
    const payload = token ? JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as { sub?: string } : null;
    return `hackalem.attachments.live.${payload?.sub ?? 'session'}`;
  } catch { return 'hackalem.attachments.live.session'; }
}
export function createLiveAttachmentDriver(sessionToken?: string): AttachmentDriver {
  let view: AttachmentView = { mode: 'loading', jobs: [], notice: null, busy: false };
  const listeners = new Set<() => void>();
  const snapshots = new Map<string, Snapshot>();
  const privateKey = storageKey(sessionToken);
  let disposed = false;
  let generation = 0;
  let polling = false;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let ids: string[] = [];
  try {
    const stored: unknown = JSON.parse(sessionStorage.getItem(privateKey) ?? '[]');
    if (Array.isArray(stored)) ids = stored.filter((id): id is string => typeof id === 'string' && /^[\w-]{1,100}$/.test(id)).slice(0, 100);
  } catch { /* storage unavailable: the server still owns files */ }
  const publish = (patch: Partial<AttachmentView>) => {
    if (disposed) return;
    view = { ...view, ...patch };
    listeners.forEach((listener) => listener());
  };
  const persist = () => { try { sessionStorage.setItem(privateKey, JSON.stringify(ids)); } catch { /* memory fallback */ } };
  const current = (epoch: number) => !disposed && epoch === generation;
  const mutate = <T,>(fn: () => Promise<T>) => queryClient.getMutationCache().build(queryClient, { mutationFn: fn, retry: false }).execute(undefined);
  const read = async (id: string) => {
    const epoch = generation;
    const snapshot = await queryClient.fetchQuery({ queryKey: ['attachments', id], staleTime: 0, retry: false,
      queryFn: async () => (await sdk.status({ path: { id }, throwOnError: true })).data });
    if (!current(epoch)) return;
    const previous = view.jobs.find((job) => job.key === id);
    const next = mapAttachment(snapshot, previous);
    snapshots.set(id, snapshot);
    if (!ids.includes(id)) { ids.push(id); persist(); }
    publish({ jobs: previous ? view.jobs.map((job) => job.key === id ? next : job) : [next, ...view.jobs] });
  };
  const schedule = () => {
    clearTimeout(timeout);
    if (disposed || !view.jobs.some((job) => busyStages.has(job.stage))) return;
    timeout = setTimeout(() => { void poll(); }, 2000);
  };
  const poll = async () => {
    if (polling || disposed) return;
    if (document.visibilityState === 'hidden') { schedule(); return; }
    polling = true;
    try {
      for (const job of view.jobs.filter((item) => busyStages.has(item.stage))) await read(job.key);
    } catch (error) { publish({ notice: attachmentError(error) }); }
    finally { polling = false; schedule(); }
  };
  const act = async <T,>(fn: () => Promise<T>, fallback: T): Promise<T> => {
    if (view.busy || disposed) return fallback;
    const epoch = generation;
    publish({ busy: true, notice: null });
    try { return await fn(); }
    catch (error) { if (current(epoch)) publish({ notice: attachmentError(error) }); return fallback; }
    finally { if (current(epoch)) { publish({ busy: false }); schedule(); } }
  };
  const refresh = async () => {
    const epoch = generation;
    try {
      const capabilities: Capabilities = await queryClient.fetchQuery({ queryKey: ['attachments', 'capabilities'], retry: false,
        queryFn: async () => (await sdk.capabilities({ throwOnError: true })).data });
      if (!current(epoch)) return;
      publish({ mode: 'live', capabilities, notice: null });
      for (const id of [...ids]) {
        try { await read(id); }
        catch (error) {
          if (isAxiosError(error) && error.response?.status === 404) { ids = ids.filter((item) => item !== id); persist(); publish({ jobs: view.jobs.filter((item) => item.key !== id) }); }
          else throw error;
        }
      }
    } catch (error) { if (current(epoch)) publish({ mode: view.capabilities ? 'live' : 'unavailable', notice: attachmentError(error) }); }
    finally { schedule(); }
  };
  void refresh();
  return {
    getSnapshot: () => view,
    subscribe: (listener) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    async upload(conversation, file) {
      await act(async () => {
        if (!file.size) throw new Error('Файл пустой. Выберите документ с содержимым.');
        if (view.capabilities?.maxBytes && file.size > view.capabilities.maxBytes) throw new Error('Файл превышает допустимый размер.');
        const epoch = generation;
        const accepted = await mutate(async () => (await sdk.upload({ path: { id: conversation }, body: { file }, throwOnError: true })).data);
        if (!current(epoch) || !accepted.attachmentId) return;
        await read(accepted.attachmentId);
        publish({ jobs: view.jobs.map((job) => job.key === accepted.attachmentId ? { ...job, size: file.size } : job),
          notice: accepted.deduplicated ? 'Этот файл уже загружен в диалог. Открыта существующая проверка.' : null });
      }, undefined);
    },
    async review(jobKey, rowKey, expectedVersion, patch) {
      return act(async () => {
        const job = view.jobs.find((item) => item.key === jobKey);
        const row = job?.rows.find((item) => item.key === rowKey);
        if (!job || !row || job.version !== expectedVersion) throw new Error('Проверка обновилась. Повторите действие.');
        const next = { ...row, ...patch };
        if (!next.excluded && !validReviewRow(next)) throw new Error('Выберите товар, склад, единицу и допустимое количество.');
        const selection: AttachmentSelection = { rowId: rowKey, productId: next.selectedKey ?? 'excluded', quantity: next.quantity || '0',
          unit: next.unit || 'excluded', warehouse: next.warehouse || 'excluded', selected: !next.excluded };
        try {
          const epoch = generation;
          const snapshot = await mutate(async () => (await sdk.review({ path: { id: jobKey }, body: { expectedVersion: job.serverVersion ?? String(expectedVersion), selections: [selection] }, throwOnError: true })).data);
          if (!current(epoch)) return false;
          snapshots.set(jobKey, snapshot);
          publish({ jobs: view.jobs.map((item) => item.key === jobKey ? mapAttachment(snapshot, item) : item) });
          return true;
        } catch (error) { if (isAxiosError(error) && error.response?.status === 409) await read(jobKey); throw error; }
      }, false);
    },
    refresh: () => { void refresh(); },
    attachProposal(jobKey, proposalKey) { publish({ jobs: view.jobs.map((job) => job.key === jobKey ? { ...job, proposalKey } : job) }); },
    async open(jobKey) { await act(async () => { await read(jobKey); }, undefined); },
    async remove(jobKey) {
      await act(async () => {
        const epoch = generation;
        await mutate(() => sdk.delete_({ path: { id: jobKey }, throwOnError: true }));
        if (!current(epoch)) return;
        ids = ids.filter((id) => id !== jobKey); persist(); snapshots.delete(jobKey);
        queryClient.removeQueries({ queryKey: ['attachments', jobKey] });
        publish({ jobs: view.jobs.filter((job) => job.key !== jobKey) });
      }, undefined);
    },
    async download(jobKey) {
      await act(async () => {
        const epoch = generation;
        const response = await sdk.source1({ path: { id: jobKey }, responseType: 'blob', throwOnError: true });
        if (!current(epoch)) return;
        const blob = response.data as unknown;
        if (!(blob instanceof Blob)) throw new Error('Сервер не вернул исходный файл.');
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a'); link.href = url;
        link.download = view.jobs.find((job) => job.key === jobKey)?.fileName ?? 'document';
        link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }, undefined);
    },
    async reprocess(jobKey) {
      await act(async () => {
        const snapshot = snapshots.get(jobKey);
        if (!snapshot?.version) return;
        try { await mutate(() => sdk.reprocess({ path: { id: jobKey }, body: { expectedVersion: snapshot.version }, throwOnError: true })); }
        catch (error) { if (isAxiosError(error) && error.response?.status === 409) await read(jobKey); throw error; }
        await read(jobKey);
      }, undefined);
    },
    async linkConversation(jobKey) {
      return act(async () => {
        const snapshot = snapshots.get(jobKey);
        if (!snapshot?.conversationId || !snapshot.version || !snapshot.selections?.some((item) => item.selected)) throw new Error('Сначала сохраните выбранные строки.');
        const path = { id: snapshot.conversationId };
        const state = (await sdk.getDialogue({ path, throwOnError: true })).data;
        const { data } = await mutate(() => sdk.updateDialogue({ path, body: { expectedVersion: state.version, attachmentId: jobKey, attachmentVersion: snapshot.version }, throwOnError: true }));
        publishDialogue(path.id, data);
        window.dispatchEvent(new CustomEvent('hackalem:conversation', { detail: { id: path.id } }));
        publish({ notice: 'Проверенный файл связан с диалогом. Можно продолжить подбор в чате.' });
        return true;
      }, false);
    },
    clearPrivateData() { generation++; clearTimeout(timeout); snapshots.clear(); ids = []; try { sessionStorage.removeItem(privateKey); } catch { /* memory cleared */ } publish({ jobs: [], notice: null, busy: false }); },
    dispose() { disposed = true; generation++; clearTimeout(timeout); listeners.clear(); },
  };
}
