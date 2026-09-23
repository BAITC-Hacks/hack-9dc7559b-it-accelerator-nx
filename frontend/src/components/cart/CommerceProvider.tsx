import { useEffect, useState, type ReactNode } from 'react';
import { auth } from '../../lib/api';
import { CommerceContext, initialCommerce, unavailableCommerce } from './context';
import type { CommerceDriver } from './model';

export function CommerceProvider({ children }: { children: ReactNode }) {
  const [driver, setDriver] = useState<CommerceDriver>(unavailableCommerce);
  useEffect(() => {
    let cancelled = false;
    let active: CommerceDriver | undefined;
    let unsubscribe = () => {};
    const load = import.meta.env.DEV && import.meta.env.MODE === 'mock'
      ? import('../../mocks/commerce-demo').then(({ createDemoCommerce }) => createDemoCommerce)
      : import('../../lib/commerce-live').then(({ createLiveCommerce }) => createLiveCommerce);
    void load.then((createDriver) => {
      if (cancelled) return;
      active = createDriver();
      unsubscribe = auth.subscribe(() => active?.clearPrivateData());
      setDriver(active);
    }).catch(() => {
      if (cancelled) return;
      const failed = { ...initialCommerce, mode: 'unavailable' as const, notice: 'Не удалось открыть демонстрационный каталог.' };
      setDriver({ ...unavailableCommerce, getSnapshot: () => failed });
    });
    return () => { cancelled = true; unsubscribe(); active?.dispose(); };
  }, []);
  return <CommerceContext.Provider value={driver}>{children}</CommerceContext.Provider>;
}
