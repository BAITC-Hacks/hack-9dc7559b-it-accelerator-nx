import { isAxiosError } from 'axios';

export function apiError(error: unknown): string {
  if (isAxiosError(error)) {
    const code = error.response?.data?.code ?? error.response?.data?.detail;
    const status = error.response?.status;
    if (status === 401) return 'Сессия истекла. Войдите снова.';
    if (status === 403) return 'Недостаточно прав для этого действия.';
    if (status === 409) return 'Данные изменились. Обновите их и повторите действие.';
    if (status === 429) return 'Слишком много запросов. Подождите немного и повторите.';
    if (code === 'partner_identity_required') return 'Гостевой вход отключён на сервере. Используйте выданный токен сессии.';
    return typeof code === 'string' ? code : 'Не удалось связаться с сервером. Повторите попытку.';
  }
  return error instanceof Error ? error.message : 'Не удалось выполнить запрос.';
}
