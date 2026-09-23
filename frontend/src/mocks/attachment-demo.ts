import type { AttachmentDriver, AttachmentJob, AttachmentView, FileFamily, ReviewRow } from '../components/attachments/model';
import { validQuantity } from '../components/catalog/decimal';

// Fixed educational rows. The uploaded file is NEVER parsed, transmitted or persisted.
const key = 'hackalem.ui-attachments-demo.v1';
export const DEMO_MAX_FILE_BYTES = 12 * 1024 * 1024;
export const DEMO_FAMILIES: readonly FileFamily[] = ['xls', 'xlsx', 'doc', 'docx', 'pdf', 'jpg', 'jpeg'];
const active = (stage: AttachmentJob['stage']) => ['queued', 'extracting', 'ocr', 'matching'].includes(stage);
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const stages: AttachmentJob['stage'][] = ['queued', 'extracting', 'ocr', 'matching', 'ready'];
function fixtureRows(family: FileFamily): ReviewRow[] {
  const location = family === 'xls' || family === 'xlsx' ? ['Лист 1 · строка 2', 'Лист 1 · строка 3', 'Лист 1 · строка 4']
    : family === 'doc' || family === 'docx' ? ['Таблица 1 · строка 2', 'Таблица 1 · строка 3', 'Абзац 4']
      : family === 'pdf' ? ['Страница 1 · строка 2', 'Страница 1 · строка 3', 'Страница 2 · строка 1']
        : ['Область фото · верх', 'Область фото · центр', 'Область фото · низ'];
  return [
    { key: 'row-a', location: location[0], rawText: 'Автомат 3P 40 А — 12 шт.', article: null, quantity: '12', unit: 'шт.',
      confidence: 'matched', warnings: [], candidateKeys: ['demo-original', 'demo-alternative'], selectedKey: 'demo-original', excluded: false },
    { key: 'row-b', location: location[1], rawText: 'Автомат 3P 40 А — 8 шт., серия неясна', article: null, quantity: '8', unit: 'шт.',
      confidence: 'ambiguous', warnings: ['Серия не определена. Выберите вариант вручную.'], candidateKeys: ['demo-original', 'demo-alternative'], selectedKey: null, excluded: false },
    { key: 'row-c', location: location[2], rawText: 'Неясная позиция — количество не указано', article: null, quantity: '', unit: 'шт.',
      confidence: 'needs_quantity', warnings: ['Количество неизвестно; строка не войдёт в предложение без проверки.'], candidateKeys: [], selectedKey: null, excluded: false },
  ];
}
function validStoredJob(value: unknown): value is AttachmentJob {
  return record(value) && ['key', 'conversation', 'fingerprint', 'fileName', 'family', 'createdAt'].every((field) => typeof value[field] === 'string')
    && DEMO_FAMILIES.includes(value.family as FileFamily) && typeof value.size === 'number' && value.size >= 0 && value.size <= DEMO_MAX_FILE_BYTES
    && stages.concat(['review', 'failed']).includes(value.stage as AttachmentJob['stage'])
    && typeof value.progress === 'number' && value.progress >= 0 && value.progress <= 100
    && Number.isSafeInteger(value.version) && Number(value.version) >= 0 && Array.isArray(value.rows) && value.rows.length <= 100
    && value.rows.every((row: unknown) => record(row) && typeof row.key === 'string' && typeof row.location === 'string'
      && typeof row.rawText === 'string' && typeof row.quantity === 'string' && typeof row.unit === 'string'
      && Array.isArray(row.warnings) && row.warnings.every((warning: unknown) => typeof warning === 'string')
      && Array.isArray(row.candidateKeys) && row.candidateKeys.every((candidate: unknown) => typeof candidate === 'string')
      && (row.selectedKey === null || typeof row.selectedKey === 'string') && typeof row.excluded === 'boolean')
    && (value.error === null || typeof value.error === 'string') && (value.proposalKey === null || typeof value.proposalKey === 'string');
}
function restore(): AttachmentJob[] {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw || raw.length > 500_000) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length <= 20 && parsed.every(validStoredJob) ? parsed : [];
  } catch { return []; }
}
async function formatError(file: File, family: FileFamily): Promise<string | null> {
  if (file.size === 0) return 'Файл пустой. Выберите документ с содержимым.';
  if (file.size > DEMO_MAX_FILE_BYTES) return 'В учебном режиме максимальный размер файла — 12 МБ.';
  try {
    const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
    const isZip = bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
    const isOle = bytes[0] === 0xd0 && bytes[1] === 0xcf && bytes[2] === 0x11 && bytes[3] === 0xe0;
    const isPdf = bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46;
    const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
    const valid = family === 'xlsx' || family === 'docx' ? isZip : family === 'xls' || family === 'doc' ? isOle
      : family === 'pdf' ? isPdf : isJpeg;
    return valid ? null : 'Содержимое не похоже на выбранный формат или файл повреждён. Проверка учебная и не заменяет серверную валидацию.';
  } catch { return 'Не удалось прочитать файл в браузере. Повторите выбор.'; }
}
export function createDemoAttachmentDriver(): AttachmentDriver {
  let view: AttachmentView = { mode: 'demo', jobs: restore(), notice: null, busy: false };
  let disposed = false;
  let generation = 0;
  let storageAvailable = true;
  const listeners = new Set<() => void>();
  const pending = new Set<string>();
  const timers = new Map<string, ReturnType<typeof setTimeout>>();
  const publish = () => {
    try { sessionStorage.setItem(key, JSON.stringify(view.jobs)); }
    catch { storageAvailable = false; view = { ...view, notice: 'Хранилище недоступно. Учебная проверка сохранится только до перезагрузки.' }; }
    listeners.forEach((listener) => listener());
  };
  const replace = (jobKey: string, patch: Partial<AttachmentJob>) => {
    view = { ...view, jobs: view.jobs.map((job) => job.key === jobKey ? { ...job, ...patch } : job) };
    publish();
  };
  const schedule = (jobKey: string) => {
    clearTimeout(timers.get(jobKey));
    if (disposed || document.visibilityState === 'hidden') return;
    const job = view.jobs.find((item) => item.key === jobKey);
    if (!job || !active(job.stage)) return;
    const delay = 450 + Math.min(job.progress, 80) * 12;
    timers.set(jobKey, setTimeout(() => {
      timers.delete(jobKey);
      const current = view.jobs.find((item) => item.key === jobKey);
      if (!current || !active(current.stage) || disposed || document.visibilityState === 'hidden') return;
      const stage = stages[stages.indexOf(current.stage) + 1];
      replace(jobKey, { stage, progress: stage === 'ready' ? 100 : Math.min(90, current.progress + 23),
        rows: stage === 'ready' ? fixtureRows(current.family) : current.rows });
      schedule(jobKey);
    }, delay));
  };
  const visibility = () => {
    if (document.visibilityState === 'hidden') timers.forEach(clearTimeout);
    else view.jobs.forEach((job) => { if (active(job.stage)) schedule(job.key); });
  };
  document.addEventListener('visibilitychange', visibility);
  view.jobs.forEach((job) => { if (active(job.stage)) schedule(job.key); });
  return {
    getSnapshot: () => view,
    subscribe: (listener) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    async upload(conversation, file) {
      if (disposed) return;
      const family = file.name.split('.').pop()?.toLowerCase() as FileFamily | undefined;
      if (!family || !DEMO_FAMILIES.includes(family)) { view = { ...view, notice: 'Допустимы только XLS, XLSX, DOC, DOCX, PDF и JPEG.' }; publish(); return; }
      const fingerprint = `${conversation}:${file.name}:${file.size}:${file.lastModified}`;
      if (pending.has(fingerprint) || view.jobs.some((job) => job.fingerprint === fingerprint)) {
        view = { ...view, notice: 'Этот файл уже добавлен в текущий диалог. Откройте существующую проверку.' }; publish(); return;
      }
      if (view.jobs.length >= 20) { view = { ...view, notice: 'В учебном режиме не более 20 файлов за сессию.' }; publish(); return; }
      pending.add(fingerprint);
      const uploadGeneration = generation;
      view = { ...view, busy: true, notice: null }; publish();
      try {
        const error = await formatError(file, family);
        if (disposed || generation !== uploadGeneration) return;
        if (error) { view = { ...view, notice: error }; publish(); return; }
        const job: AttachmentJob = { key: crypto.randomUUID(), conversation, fingerprint, fileName: file.name, family,
          size: file.size, stage: 'queued', progress: 10, version: 1, rows: [], error: null, createdAt: new Date().toISOString(), proposalKey: null };
        view = { ...view, jobs: [job, ...view.jobs] }; publish(); schedule(job.key);
      } finally { if (generation === uploadGeneration) { pending.delete(fingerprint); if (!disposed) { view = { ...view, busy: pending.size > 0 }; publish(); } } }
    },
    review(jobKey, rowKey, expectedVersion, patch) {
      if (disposed) return false;
      const job = view.jobs.find((item) => item.key === jobKey);
      const stored = storageAvailable ? restore().find((item) => item.key === jobKey) : job;
      if (!job || !stored || stored.version !== expectedVersion || job.version !== expectedVersion) {
        view = { ...view, jobs: storageAvailable ? restore() : view.jobs, notice: 'Версия проверки изменилась. Список обновлён; повторите правку.' }; publish(); return false;
      }
      if (!['ready', 'review'].includes(job.stage)) return false;
      const row = job.rows.find((item) => item.key === rowKey);
      if (!row || (patch.selectedKey && !row.candidateKeys.includes(patch.selectedKey)) || (patch.unit && !['шт.', 'м', 'уп.'].includes(patch.unit))) return false;
      const quantity = patch.quantity ?? row.quantity;
      if (quantity && !validQuantity(quantity, '1')) { view = { ...view, notice: 'Укажите целое положительное количество или оставьте поле пустым для ручной проверки.' }; publish(); return false; }
      replace(jobKey, { version: job.version + 1, stage: 'review', proposalKey: null,
        rows: job.rows.map((item) => item.key === rowKey ? { ...item, ...patch } : item) });
      return true;
    },
    refresh() { if (!disposed && storageAvailable) { view = { ...view, jobs: restore() }; publish(); } },
    attachProposal(jobKey, proposalKey) {
      const job = view.jobs.find((item) => item.key === jobKey);
      if (!job || job.proposalKey === proposalKey) return;
      replace(jobKey, { proposalKey, version: job.version + 1 });
    },
    clearPrivateData() { generation++; timers.forEach(clearTimeout); timers.clear(); pending.clear();
      try { sessionStorage.removeItem(key); } catch { /* memory still cleared */ }
      view = { mode: 'demo', jobs: [], notice: null, busy: false }; publish(); },
    dispose() { disposed = true; timers.forEach(clearTimeout); timers.clear(); document.removeEventListener('visibilitychange', visibility); listeners.clear(); },
  };
}
