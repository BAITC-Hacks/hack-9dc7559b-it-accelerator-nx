import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useApiSession } from '../hooks/useApiSession';
import { connectSession, disconnectSession } from '../lib/live-session';

export default function SessionPage() {
  const state = useApiSession();
  const [token, setToken] = useState('');
  return <main className="session-screen"><section className="session-card"><Link to="/">← К диалогам</Link>
    <h1>Моя сессия</h1><p>Обновление продлевает доступ, сохраняя диалоги и корзину. Выход отзывает текущую сессию.</p>
    <dl><dt>Идентификатор пользователя</dt><dd>{state.identity?.principalId}</dd><dt>Корзина</dt><dd>{state.identity?.cartId}</dd><dt>Доступ до</dt><dd>{state.identity?.expiresAt ? new Date(state.identity.expiresAt).toLocaleString('ru-RU') : '—'}</dd></dl>
    {state.error && <p role="alert">{state.error}</p>}
    <div className="live-actions"><button disabled={state.busy} onClick={() => void connectSession()}>Продлить сессию</button><button disabled={state.busy} onClick={() => void disconnectSession()}>Выйти</button></div>
    <details><summary>Другая сессия</summary><form onSubmit={(event) => { event.preventDefault(); void connectSession(token); setToken(''); }}><label>Выданный токен<input type="password" autoComplete="off" value={token} onChange={(event) => setToken(event.target.value)} required /></label><button disabled={state.busy || !token.trim()}>Сменить сессию</button></form></details>
  </section></main>;
}
