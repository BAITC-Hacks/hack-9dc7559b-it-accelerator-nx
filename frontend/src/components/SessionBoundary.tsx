import { useState, type ReactNode } from 'react';
import { useApiSession } from '../hooks/useApiSession';
import { connectSession } from '../lib/live-session';
import { auth } from '../lib/api';
import { useSyncExternalStore } from 'react';

function LiveBoundary({ children }: { children: ReactNode }) {
  const session = useApiSession();
  const token = useSyncExternalStore(auth.subscribe, auth.get, auth.get);
  const [existingToken, setExistingToken] = useState('');
  if (session.ready && token) return <div key={token} className="live-session-root">{children}</div>;
  return <main className="session-screen"><section className="session-card">
    <span className="eyebrow">ЭЛЕКТРОКОМПЛЕКТ</span><h1>{session.busy ? 'Подключаемся…' : 'Вход в рабочую сессию'}</h1>
    <p>Диалоги, файлы и корзина сохраняются на сервере и доступны вашей сессии.</p>
    {session.error && <p role="alert" className="session-error">{session.error}</p>}
    <button className="live-primary" disabled={session.busy} onClick={session.retry}>{auth.get() ? 'Повторить подключение' : 'Начать гостевую сессию'}</button>
    <details><summary>Войти с существующим токеном</summary><form onSubmit={(event) => { event.preventDefault(); void connectSession(existingToken); setExistingToken(''); }}>
      <label>Токен сессии<input type="password" value={existingToken} onChange={(event) => setExistingToken(event.target.value)} autoComplete="off" required /></label>
      <button disabled={session.busy || !existingToken.trim()}>Подключиться</button>
    </form></details>
  </section></main>;
}

export function SessionBoundary({ children }: { children: ReactNode }) {
  if (import.meta.env.DEV && import.meta.env.MODE === 'mock') return children;
  return <LiveBoundary>{children}</LiveBoundary>;
}
