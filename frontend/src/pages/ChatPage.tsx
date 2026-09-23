import { useEffect, useRef, useState } from 'react';
import { ChevronRight, Menu, SlidersHorizontal, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useCommerce } from '../hooks/useCommerce';
import { useAttachments } from '../hooks/useAttachments';
import { AttachmentPanel } from '../components/attachments/AttachmentPanel';
import { CommercePanel } from '../components/catalog/CommercePanel';
import { useChatWorkspace } from '../hooks/useChatWorkspace';
import { ConversationSidebar } from '../components/chat/ConversationSidebar';
import { ContextPanel } from '../components/chat/ContextPanel';
import { MessageTimeline } from '../components/chat/MessageTimeline';
import { Composer } from '../components/chat/Composer';
import { isReplyActive, needsRecovery, type DemoScenario } from '../components/chat/model';
import { useVisualViewport } from '../widget/useVisualViewport';
import '../components/chat/chat.css';

const scenarios: { value: DemoScenario; label: string }[] = [
  { value: 'normal', label: 'Обычный ответ' },
  { value: 'disconnect', label: 'Обрыв и восстановление' },
  { value: 'duplicate', label: 'Повтор событий' },
  { value: 'expired', label: 'Истёкший replay → snapshot' },
  { value: 'unauthorized', label: 'Сессия истекла · 401' },
  { value: 'busy', label: 'Ожидание · 429' },
  { value: 'send-timeout', label: 'Потеря ответа на отправку' },
];

export type ChatPageProps = {
  embed?: boolean;
  onOpenCart?: () => void;
  onRequestClose?: () => void;
  parentTrusted?: boolean;
};

export default function ChatPage({ embed = false, onOpenCart, onRequestClose, parentTrusted }: ChatPageProps) {
  useVisualViewport(true);
  const commerce = useCommerce();
  const attachments = useAttachments();
  const { driver, view, chat } = useChatWorkspace(() => { commerce.driver.clearPrivateData(); attachments.driver.clearPrivateData(); });
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const [clearDialogOpen, setClearDialogOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLDivElement>(null);
  const clearDialog = useRef<HTMLDialogElement>(null);
  const enabled = view.mode === 'demo';
  const cartLabel = `Корзина${commerce.view.cart ? ` · ${commerce.view.cart.lines.length}` : ''}${commerce.view.proposals.some((item) => item.state === 'outcome_unknown') ? ' ?' : ''}`;

  useEffect(() => {
    if (!drawerOpen) return;
    const panel = drawer.current;
    const menuTrigger = menuButton.current;
    panel?.querySelector<HTMLElement>('button, a')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setDrawerOpen(false); return; }
      if (event.key !== 'Tab' || !panel) return;
      const targets = [...panel.querySelectorAll<HTMLElement>('button:not(:disabled), a[href]')];
      const first = targets[0];
      const last = targets[targets.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); menuTrigger?.focus(); };
  }, [drawerOpen]);

  useEffect(() => {
    if (clearDialogOpen) clearDialog.current?.showModal();
    else clearDialog.current?.close();
  }, [clearDialogOpen]);

  const sidebar = (mobile = false) => <ConversationSidebar conversations={view.conversations} selectedKey={view.selectedKey} enabled={enabled}
    onNew={() => { driver.newConversation(); setDrawerOpen(false); }}
    onSelect={(key) => { driver.selectConversation(key); setDrawerOpen(false); }}
    onClear={() => { setDrawerOpen(false); setClearDialogOpen(true); }}
    onClose={mobile ? () => setDrawerOpen(false) : undefined} />;

  const choosePrompt = (text: string) => {
    if (!chat || !enabled) return;
    driver.setDraft(chat.key, text);
    document.getElementById('chat-message')?.focus();
  };

  return (
    <div className={`chat-shell${embed ? ' chat-shell-embed' : ''}`} data-embed={embed ? 'true' : undefined}>
      <aside className="desktop-sidebar">{sidebar()}</aside>
      {drawerOpen && <div className="mobile-drawer-backdrop">
        <button className="drawer-scrim" aria-label="Закрыть меню" tabIndex={-1} onClick={() => setDrawerOpen(false)} />
        <div ref={drawer} className="mobile-drawer" role="dialog" aria-modal="true" aria-label="Диалоги">{sidebar(true)}</div>
      </div>}
      <main className="chat-workspace" inert={drawerOpen}>
        <header className="chat-header">
          <div className="chat-header-title"><button ref={menuButton} className="icon-button mobile-menu" onClick={() => setDrawerOpen(true)} aria-label="Открыть диалоги" aria-expanded={drawerOpen}><Menu size={21} /></button>
            <span className="header-muted">Онлайн-консультант</span><ChevronRight size={14} /><strong>Помощь с выбором</strong>
          </div>
          <div className="chat-header-actions"><span className={`mode-badge ${enabled ? 'demo' : ''}`}><i />{enabled ? 'Локальное демо' : view.mode === 'loading' ? 'Подключение' : 'Скоро онлайн'}</span>
            {onOpenCart
              ? <button type="button" className="header-cart-link" onClick={onOpenCart}>{cartLabel}</button>
              : <Link className="header-cart-link" to="/cart">{cartLabel}</Link>}
            {onRequestClose && <button type="button" className="icon-button embed-close" onClick={onRequestClose} aria-label="Скрыть виджет"><X size={18} /></button>}
            <button className="icon-button context-toggle" onClick={() => setContextOpen(!contextOpen)} aria-expanded={contextOpen} aria-controls="selection-context" aria-label="Параметры подбора"><SlidersHorizontal size={18} /></button>
          </div>
        </header>
        {embed && parentTrusted === false && typeof window !== 'undefined' && window.parent !== window && (
          <div className="workspace-notice" role="status">Ожидаем подтверждение страницы-хоста. Сессию и корзину хост назначить не может.</div>
        )}
        {enabled && <div className="demo-strip"><span>Демонстрация · ответы и история сохраняются только в этой вкладке</span>
          <label>Сценарий<select value={view.scenario} onChange={(event) => {
            const option = scenarios.find((item) => item.value === event.target.value);
            if (option) driver.setScenario(option.value);
          }}>{scenarios.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}</select></label>
        </div>}
        {view.mode === 'unavailable' && <div className="availability-notice" role="status">Чат пока не подключён. Отправка сообщений станет доступна после подключения сервиса.</div>}
        {view.banner && <div className="workspace-notice" role="status">{view.banner}</div>}
        {!view.storageAvailable && <div className="workspace-notice" role="status">Браузер не разрешил сохранение. История доступна до обновления страницы.</div>}
        <div className="chat-body">
          <section className="conversation-area" aria-label="Чат с консультантом">
            <MessageTimeline chat={chat} enabled={enabled} loading={view.mode === 'loading'} onChoose={choosePrompt}
              renderMessageExtras={(message) => chat && message.author === 'assistant' && message.key === chat.reply?.messageKey
                && (chat.reply.phase === 'completed' || commerce.view.proposals.some((item) => item.conversationKey === chat.key))
                ? <CommercePanel conversation={chat.key} /> : null}
              onLoadEarlier={() => chat && driver.loadEarlier(chat.key)} onResume={() => chat && driver.resume(chat.key)} onStop={() => chat && driver.stop(chat.key)} />
            <Composer draft={chat?.draft ?? ''} enabled={enabled && !!chat} active={isReplyActive(chat?.reply ?? null)} interrupted={needsRecovery(chat?.reply ?? null)}
              attachmentAction={<AttachmentPanel conversation={chat?.key ?? null} enabled={enabled} />}
              onDraft={(text) => chat && driver.setDraft(chat.key, text)} onSend={() => {
                if (!chat) return;
                // Never interpret natural-language assent as an authorized cart write.
                // Until generated replyToProposalId exists, require the exact card button.
                if (!/^(да|ага|ок|окей|yes|подтверждаю|согласен|добавляй)[.!\s]*$/iu.test(chat.draft.trim())) commerce.driver.invalidateSelection(chat.key);
                driver.send(chat.key, chat.draft);
              }} onStop={() => chat && driver.stop(chat.key)} />
          </section>
          <aside id="selection-context" className={`selection-context ${contextOpen ? 'context-open' : ''}`} aria-label="Контекст подбора"
            onKeyDown={(event) => { if (event.key === 'Escape') setContextOpen(false); }}>
            <button className="icon-button close-context" onClick={() => setContextOpen(false)} aria-label="Закрыть параметры"><X size={18} /></button>
            <ContextPanel fields={chat?.context ?? []} />
          </aside>
        </div>
      </main>
      <dialog ref={clearDialog} className="clear-history-dialog" onCancel={() => setClearDialogOpen(false)} onClose={() => setClearDialogOpen(false)}>
        <h2>Очистить локальное демо?</h2><p>Сообщения, файлы, проверки, предложения и учебная корзина этой вкладки будут удалены.</p>
        <div><button onClick={() => setClearDialogOpen(false)} autoFocus>Сохранить</button><button className="confirm-clear" onClick={() => { driver.clearPrivateData(); commerce.driver.clearPrivateData(); attachments.driver.clearPrivateData(); setClearDialogOpen(false); }}>Очистить</button></div>
      </dialog>
    </div>
  );
}
