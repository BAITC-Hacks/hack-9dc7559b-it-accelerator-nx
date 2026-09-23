import { createContext } from 'react';
import type { AttachmentDriver, AttachmentView } from './model';

const loading = import.meta.env.DEV && import.meta.env.MODE === 'mock';
export const inactiveView: AttachmentView = { mode: loading ? 'loading' : 'unavailable', jobs: [], notice: null, busy: false };
const noop = () => {};
export const inactiveDriver: AttachmentDriver = {
  getSnapshot: () => inactiveView, subscribe: () => noop, upload: async () => {}, review: () => false,
  refresh: noop, attachProposal: noop, clearPrivateData: noop, dispose: noop,
};
export const AttachmentContext = createContext<AttachmentDriver>(inactiveDriver);
