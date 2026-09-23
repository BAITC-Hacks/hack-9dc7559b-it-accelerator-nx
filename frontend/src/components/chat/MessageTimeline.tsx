import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { ArrowDown, LoaderCircle, Zap } from 'lucide-react';
import type { ConversationView, MessageView } from './model';
import { Welcome } from './Welcome';
import { ReplyStatus } from './ReplyStatus';
import { SafeMarkdown } from '../sources/SafeMarkdown';

interface Props {
  chat: ConversationView | null;
  enabled: boolean;
  loading: boolean;
  onChoose: (text: string) => void;
  onLoadEarlier: () => void;
  onResume: () => void;
  onStop: () => void;
  renderMessageExtras?: (message: MessageView) => ReactNode;
  demo?: boolean;
}

export function MessageTimeline({ chat, enabled, loading, onChoose, onLoadEarlier, onResume, onStop, renderMessageExtras, demo }: Props) {
  const viewport = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const previousChat = useRef<string | null>(null);
  const olderAnchor = useRef<{ height: number; top: number } | null>(null);
  const [scrollNotice, setScrollNotice] = useState<{ key: string | null; show: boolean }>({ key: null, show: false });

  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) return;
    if (previousChat.current !== (chat?.key ?? null)) {
      previousChat.current = chat?.key ?? null;
      stickToBottom.current = true;
      olderAnchor.current = null;
    }
    if (olderAnchor.current && !chat?.loadingEarlier) {
      element.scrollTop = olderAnchor.current.top + element.scrollHeight - olderAnchor.current.height;
      olderAnchor.current = null;
    } else if (stickToBottom.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [chat?.key, chat?.messages, chat?.visibleCount, chat?.loadingEarlier]);

  const jumpToBottom = () => {
    const element = viewport.current;
    if (!element) return;
    stickToBottom.current = true;
    element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' });
    setScrollNotice({ key: chat?.key ?? null, show: false });
  };

  return (
    <div className="timeline-wrap">
      <div ref={viewport} className="message-viewport" onScroll={() => {
        const element = viewport.current;
        if (!element) return;
        const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 100;
        stickToBottom.current = atBottom;
        setScrollNotice((current) => current.key === chat?.key && current.show === !atBottom
          ? current : { key: chat?.key ?? null, show: !atBottom });
      }}>
        {loading ? <div className="chat-loading" role="status"><LoaderCircle className="spin" size={24} /><p>Открываем диалог…</p></div>
          : !chat?.messages.length ? <Welcome enabled={enabled} onChoose={onChoose} />
            : <div className="message-column">
              {chat.visibleCount < chat.messages.length && <button className="history-more" disabled={chat.loadingEarlier} onClick={() => {
                const element = viewport.current;
                if (element) olderAnchor.current = { height: element.scrollHeight, top: element.scrollTop };
                stickToBottom.current = false;
                onLoadEarlier();
              }}>{chat.loadingEarlier ? 'Загружаем историю…' : 'Загрузить предыдущие сообщения'}</button>}
              <div className="conversation-date">Этот диалог сохраняет контекст подбора</div>
              <div role="log" aria-label="История сообщений" aria-live="off">
                {chat.messages.slice(-chat.visibleCount).map((message) => (
                  <article key={message.key} className={`chat-message ${message.author}`}>
                    {message.author === 'assistant' && <span className="assistant-avatar" aria-hidden="true"><Zap size={16} /></span>}
                    <div className="message-content">
                      <div className="message-byline"><strong>{message.author === 'customer' ? 'Вы' : 'Консультант EKT'}</strong>
                        <time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</time>
                        {message.author === 'assistant' && demo && <span className="message-demo-tag">демо</span>}
                      </div>
                      <div className="message-text">
                        {message.text ? <SafeMarkdown text={message.text} /> : <span className="message-placeholder">{chat.reply?.phase === 'cancelled' ? 'Ответ остановлен до получения текста.'
                          : chat.reply?.phase === 'interrupted' ? 'Сообщение сохранено. Ожидаем восстановления ответа.' : 'Готовим ответ…'}</span>}
                      </div>
                      {renderMessageExtras?.(message)}
                    </div>
                  </article>
                ))}
              </div>
              <ReplyStatus reply={chat.reply} onResume={onResume} onStop={onStop} />
              {chat.reply?.phase === 'completed' && <div className="followup-chips" aria-label="Продолжить подбор">
                {['Есть дешевле?', 'Сравни первые два', 'Нужно 20 штук'].map((text) => <button key={text} onClick={() => onChoose(text)}>{text}</button>)}
              </div>}
            </div>}
      </div>
      {scrollNotice.show && scrollNotice.key === chat?.key && <button className="jump-to-latest" onClick={jumpToBottom}><ArrowDown size={15} />К последним сообщениям</button>}
    </div>
  );
}
