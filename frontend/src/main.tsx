import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';

import App from './App';
import { queryClient } from './lib/query';
import './lib/api'; // конфигурирует сгенерированный клиент (baseURL, токен)
import './index.css';

async function start() {
  // Vite replaces DEV with false in production, excluding this dynamic import.
  if (import.meta.env.DEV && import.meta.env.MODE === 'mock') {
    const { startDevelopmentMocks } = await import('./mocks/browser');
    await startDevelopmentMocks();
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </StrictMode>,
  );
}

void start().catch(() => {
  const message = document.createElement('p');
  message.setAttribute('role', 'alert');
  message.textContent = 'Не удалось запустить приложение. Обновите страницу.';
  document.getElementById('root')?.replaceChildren(message);
});
