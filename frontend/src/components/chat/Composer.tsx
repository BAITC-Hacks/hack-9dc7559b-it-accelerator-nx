import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { ArrowUp, Paperclip, Square } from 'lucide-react';

interface Props {
  draft: string;
  enabled: boolean;
  active: boolean;
  interrupted: boolean;
  onDraft: (text: string) => void;
  onSend: () => void;
  onStop: () => void;
  attachmentAction?: ReactNode;
}

export function Composer({ draft, enabled, active, interrupted, onDraft, onSend, onStop, attachmentAction }: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    if (!input.current) return;
    input.current.style.height = '0px';
    input.current.style.height = `${Math.min(input.current.scrollHeight, 160)}px`;
  }, [draft]);
  const canSend = enabled && !active && !interrupted && draft.trim().length > 0;

  return (
    <div className="composer-area">
      <form className="chat-composer" onSubmit={(event) => {
        event.preventDefault();
        if (canSend) onSend();
      }}>
        <label className="sr-only" htmlFor="chat-message">Сообщение консультанту</label>
        <textarea ref={input} id="chat-message" value={draft} rows={1} maxLength={4000}
          disabled={!enabled} onChange={(event) => onDraft(event.target.value)}
          placeholder={enabled ? 'Какое оборудование вы ищете?' : 'Чат пока не подключён'}
          aria-describedby="composer-help"
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
              event.preventDefault();
              if (canSend) onSend();
            }
          }} />
        <div className="composer-toolbar">
          <div className="composer-attachments">
            {attachmentAction ?? <button type="button" className="icon-button" disabled title="Вложения пока недоступны" aria-label="Вложения пока недоступны"><Paperclip size={18} /></button>}
            <span>Текст или артикул</span>
          </div>
          <div className="composer-controls">
            {draft.length > 3500 && <span className="character-count">{draft.length} / 4000</span>}
            {active ? <button type="button" className="stop-button" onClick={onStop}><Square size={12} fill="currentColor" />Остановить</button>
              : <button className="send-button" type="submit" disabled={!canSend} aria-label="Отправить сообщение"><ArrowUp size={21} /></button>}
          </div>
        </div>
      </form>
      <p className="composer-help" id="composer-help">
        {interrupted ? 'Продолжите или остановите незавершённый ответ перед новой отправкой.'
          : 'Enter — отправить · Shift + Enter — новая строка'}
      </p>
    </div>
  );
}
