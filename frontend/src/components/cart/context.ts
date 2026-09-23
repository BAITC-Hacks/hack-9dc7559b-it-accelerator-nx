import { createContext } from 'react';
import type { CommerceDriver, CommerceView } from './model';

export const initialCommerce: CommerceView = {
  mode: import.meta.env.DEV && import.meta.env.MODE === 'mock' ? 'loading' : 'unavailable',
  products: [], sources: [], selections: {}, proposals: [], cart: null,
  scenario: 'normal', busy: false, notice: null,
};
const noop = () => {};
export const unavailableCommerce: CommerceDriver = {
  getSnapshot: () => initialCommerce, subscribe: () => noop, select: noop,
  prepare: noop, confirm: noop, reject: noop, lookup: noop, refreshCart: noop,
  prepareReviewed: () => null,
  invalidateSelection: noop, setScenario: noop, clearPrivateData: noop, dispose: noop,
};
export const CommerceContext = createContext<CommerceDriver>(unavailableCommerce);
