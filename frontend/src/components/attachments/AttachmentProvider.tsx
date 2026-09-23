import { useEffect, useState, type ReactNode } from 'react';
import { auth } from '../../lib/api';
import { AttachmentContext, inactiveDriver, inactiveView } from './context';
import type { AttachmentDriver, AttachmentView } from './model';

export function AttachmentProvider({ children }: { children: ReactNode }) {
  const [driver, setDriver] = useState<AttachmentDriver>(inactiveDriver);
  useEffect(() => {
    if (!import.meta.env.DEV || import.meta.env.MODE !== 'mock') return;
    let cancelled = false;
    let active: AttachmentDriver | undefined;
    let unsubscribe = () => {};
    void import('../../mocks/attachment-demo').then(({ createDemoAttachmentDriver }) => {
      if (cancelled) return;
      active = createDemoAttachmentDriver();
      unsubscribe = auth.subscribe(() => active?.clearPrivateData());
      setDriver(active);
    }).catch(() => {
      if (cancelled) return;
      const failed: AttachmentView = { ...inactiveView, mode: 'unavailable', notice: 'Не удалось открыть проверку файлов.' };
      setDriver({ ...inactiveDriver, getSnapshot: () => failed });
    });
    return () => { cancelled = true; unsubscribe(); active?.dispose(); };
  }, []);
  return <AttachmentContext.Provider value={driver}>{children}</AttachmentContext.Provider>;
}
