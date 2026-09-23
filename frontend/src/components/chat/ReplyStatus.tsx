import { useEffect, useState } from 'react';
import { AlertCircle, Check, LoaderCircle, RotateCcw, Square } from 'lucide-react';
import type { ReplyView } from './model';

export function ReplyStatus({ reply, onResume, onStop }: { reply: ReplyView | null; onResume: () => void; onStop: () => void }) {
  const [clock, setClock] = useState(() => Date.now());
  useEffect(() => {
    const deadline = reply?.retryAt;
    if (!deadline) return;
    const timer = setInterval(() => {
      const current = Date.now();
      setClock(current);
      if (current >= deadline) clearInterval(timer);
    }, 250);
    return () => clearInterval(timer);
  }, [reply?.retryAt]);
  if (!reply) return null;
  const waitSeconds = reply.retryAt ? Math.max(0, Math.ceil((reply.retryAt - clock) / 1000)) : 0;
  const active = ['sending', 'streaming', 'recovering'].includes(reply.phase);

  if (active) return <div className="reply-status" role="status"><LoaderCircle size={14} className="spin" />
    {reply.phase === 'sending' ? 'Отправляем сообщение…' : reply.phase === 'recovering' ? 'Восстанавливаем ответ…' : 'Формируем ответ…'}
  </div>;
  if (reply.phase === 'completed') return <div className="reply-status completed" role="status"><Check size={14} />Ответ готов</div>;
  if (reply.phase === 'cancelled') return <div className="reply-status" role="status"><Square size={12} />Ответ остановлен · текст сохранён</div>;
  return (
    <div className="reply-recovery" role="status">
      <AlertCircle size={18} />
      <div><p>{reply.notice ?? 'Не удалось получить ответ.'}</p>
        {reply.phase === 'interrupted' && <div className="recovery-actions">
          <button onClick={onResume} disabled={waitSeconds > 0}><RotateCcw size={13} />{waitSeconds ? `Повтор через ${waitSeconds} с` : 'Продолжить ответ'}</button>
          <button onClick={onStop} className="secondary-action">Остановить</button>
        </div>}
      </div>
    </div>
  );
}
