import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
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

export function useChatWorkspace(onSessionExpired?: () => void) {
  const sessionExpired = useRef(onSessionExpired);
  useEffect(() => { sessionExpired.current = onSessionExpired; }, [onSessionExpired]);
  const [driver, setDriver] = useState<ChatDriver>(unavailable);
  const view = useSyncExternalStore(driver.subscribe, driver.getSnapshot, driver.getSnapshot);

  useEffect(() => {
    let disposed = false;
    let active: ChatDriver | undefined;
    let unsubscribe = noop;
    // Only initialize UI state here. Send/create-run happens in a user handler.
    const factory = DEMO
      ? import('../mocks/chat-demo').then(({ createDemoChatDriver }) => () => createDemoChatDriver(() => sessionExpired.current?.()))
      : import('../lib/chat-live').then(({ createLiveChatDriver }) => createLiveChatDriver);
    void factory.then((createDriver) => {
      if (disposed) return;
      active = createDriver();
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
