import { ArrowUpRight, MessageSquare, Plus, Trash2, X, Zap } from 'lucide-react';
import type { ConversationView } from './model';
import { Link } from 'react-router-dom';

interface Props {
  conversations: ConversationView[];
  selectedKey: string | null;
  enabled: boolean;
  onNew: () => void;
  onSelect: (key: string) => void;
  onClear: () => void;
  onClose?: () => void;
}

export function ConversationSidebar({ conversations, selectedKey, enabled, onNew, onSelect, onClear, onClose }: Props) {
  return (
    <div className="conversation-sidebar">
      <div className="sidebar-brand">
        <a className="ekt-wordmark" href="/" aria-label="Электрокомплект — главная">ekt<span>.</span></a>
        <span className="brand-caption">ЭЛЕКТРОКОМПЛЕКТ</span>
        {onClose && <button className="icon-button sidebar-close" onClick={onClose} aria-label="Закрыть список диалогов"><X size={20} /></button>}
      </div>
      <div className="sidebar-heading"><span className="eyebrow">ВАШ ПОМОЩНИК</span><span className="sidebar-version">AI</span></div>
      <button className="new-conversation" onClick={onNew} disabled={!enabled}><Plus size={19} />Новый диалог</button>
      <div className="history-heading"><span>История диалогов</span><span>{conversations.filter((chat) => chat.messages.length).length}</span></div>
      <nav className="conversation-list" aria-label="Диалоги">
        {conversations.length === 0 && <p className="sidebar-empty">Здесь появятся ваши диалоги.</p>}
        {conversations.map((chat) => (
          <button key={chat.key} className={`conversation-item ${chat.key === selectedKey ? 'selected' : ''}`}
            aria-current={chat.key === selectedKey ? 'page' : undefined} onClick={() => onSelect(chat.key)}>
            <MessageSquare size={16} aria-hidden="true" />
            <span><strong>{chat.title}</strong><small>{chat.messages.length ? `${chat.messages.length} сообщений` : 'Начните с вашего вопроса'}</small></span>
            {chat.reply && ['sending', 'streaming', 'recovering'].includes(chat.reply.phase) && <i className="conversation-busy" aria-label="Ответ формируется" />}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="sidebar-note"><Zap size={19} /><p>От задачи —<br /><strong>к нужному решению.</strong></p></div>
        <Link className="catalog-link" to="/catalog">Перейти в каталог<ArrowUpRight size={16} /></Link>
        {enabled && <button className="clear-history" onClick={onClear}><Trash2 size={14} />Очистить локальную историю</button>}
        <div className="sidebar-footnote">Электротехника для ваших проектов</div>
      </div>
    </div>
  );
}
