import { useEffect, useState, useSyncExternalStore } from 'react';
import { auth } from '../lib/api';
import type { ChatDriver, WorkspaceView } from '../components/chat/model';

const DEMO = import.meta.env.DEV && import.meta.env.MODE === 'mock';
const inactiveSnapshot: WorkspaceView = {
  mode: DEMO ? 'loading' : 'unavailable', conversations: [], selectedKey: null,
  scenario: 'normal', banner: null, storageAvailable: true,
};
const noop = () => {};
const unavailable: ChatDriver = {
  getSnapshot: () => inactiveSnapshot, subscribe: () => noop,
  newConversation: noop, selectConversation: noop, setDraft: noop, send: noop,
  resume: noop, stop: noop, loadEarlier: noop, setScenario: noop,
  clearPrivateData: noop, dispose: noop,
};

export function useChatWorkspace() {
  const [driver, setDriver] = useState<ChatDriver>(unavailable);
  const view = useSyncExternalStore(driver.subscribe, driver.getSnapshot, driver.getSnapshot);

  useEffect(() => {
    if (!DEMO) return;
    let disposed = false;
    let active: ChatDriver | undefined;
    let unsubscribe = noop;
    // Only initialize UI state here. Send/create-run happens in a user handler.
    void import('../mocks/chat-demo').then(({ createDemoChatDriver }) => {
      if (disposed) return;
      active = createDemoChatDriver();
      unsubscribe = auth.subscribe(() => active?.clearPrivateData());
      setDriver(active);
    }).catch(() => {
      if (disposed) return;
      const failed: WorkspaceView = {
        ...inactiveSnapshot, mode: 'unavailable', banner: 'Не удалось открыть демонстрационный чат. Обновите страницу.',
      };
      setDriver({ ...unavailable, getSnapshot: () => failed });
    });
    return () => {
      disposed = true;
      unsubscribe();
      active?.dispose();
    };
  }, []);

  return { driver, view, chat: view.conversations.find((item) => item.key === view.selectedKey) ?? null };
}
