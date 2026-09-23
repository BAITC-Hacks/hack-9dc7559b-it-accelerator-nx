import axios from 'axios';
import { client } from '@/client/client.gen';

/**
 * Единственное место, где живёт адрес API. Хардкодить localhost где-то ещё — нельзя.
 * VITE_API_URL — переменная времени СБОРКИ (см. frontend/AGENTS.md).
 */
export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

export const http = axios.create({
  baseURL: API_URL,
  headers: { 'Content-Type': 'application/json' },
});

const TOKEN_KEY = 'hackalem.token';

export const auth = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

// Появится JWT — токен поедет в каждый запрос отсюда.
http.interceptors.request.use((config) => {
  const token = auth.get();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Сгенерированный клиент (src/client) ходит через тот же axios-инстанс и тот же baseURL.
// Без этого он использует адрес из OpenAPI-контракта, а не VITE_API_URL.
client.setConfig({ baseURL: API_URL, axios: http });
