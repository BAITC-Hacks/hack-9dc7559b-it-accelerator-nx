import { useEffect, useSyncExternalStore } from 'react';
import { connectSession, ensureSession, liveSession } from '../lib/live-session';

export function useApiSession() {
  const state = useSyncExternalStore(liveSession.subscribe, liveSession.getSnapshot, liveSession.getSnapshot);
  useEffect(ensureSession, []);
  return { ...state, retry: () => { void connectSession(); } };
}
