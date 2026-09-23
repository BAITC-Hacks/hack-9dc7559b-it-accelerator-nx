import { isAxiosError } from 'axios';
import type { ApiProblem, CatalogImportRequest } from '../client';

export function sourceHref(documentId: string, versionId: string) {
  return `/sources/${encodeURIComponent(documentId)}?version=${encodeURIComponent(versionId)}`;
}

export function serviceError(error: unknown): string {
  if (isAxiosError<ApiProblem>(error)) {
    const status = error.response?.status;
    if (status === 403) return 'Доступ запрещён для текущей сессии. Административные операции требуют роли ADMIN, назначенной на сервере.';
    if (status === 401) return 'Сессия истекла. Войдите повторно.';
    if (status === 404) return 'Запись не найдена или недоступна этой сессии.';
    if (status === 409) return 'Данные изменились. Загрузите актуальную версию и повторите действие.';
    if (status === 429) return 'Слишком много запросов. Подождите немного и повторите.';
    if (!error.response) return 'Backend недоступен. Проверьте подключение и состояние сервиса.';
    const problem = error.response.data;
    return `${status ?? 'Ошибка'}: ${problem?.detail ?? problem?.code ?? error.message}`;
  }
  return error instanceof Error ? error.message : 'Не удалось выполнить запрос.';
}

/** The server performs full domain validation; reject malformed local input first. */
export function parseCatalogImport(text: string): CatalogImportRequest {
  let value: unknown;
  try { value = JSON.parse(text); } catch { throw new Error('Не удалось прочитать JSON. Проверьте кавычки, запятые и скобки.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Ожидается JSON-объект каталога.');
  if (!('schemaVersion' in value) || value.schemaVersion !== 1) throw new Error('Укажите schemaVersion: 1.');
  if (!('version' in value) || typeof value.version !== 'string' || !value.version.trim()) throw new Error('Укажите непустую version каталога.');
  if (!('products' in value) || !Array.isArray(value.products) || !value.products.length) throw new Error('Массив products должен содержать хотя бы один товар.');
  if (value.products.some((product: unknown) => !product || typeof product !== 'object' || !('article' in product) || typeof product.article !== 'string')) {
    throw new Error('Артикул каждого товара должен быть строкой: это сохраняет ведущие нули.');
  }
  return value as CatalogImportRequest;
}

export function catalogJobId(value: string): number {
  const id = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(id) || id <= 0) throw new Error('ID задания каталога должен быть положительным целым числом.');
  return id;
}

export function isJobPending(state?: string) {
  return state === 'PENDING' || state === 'QUEUED' || state === 'RUNNING';
}
