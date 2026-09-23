import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createDemoCommerce } from '../src/mocks/commerce-demo';
import type { CommerceDriver, CommerceScenario } from '../src/components/cart/model';

// Prepared for the verification gate. Not executed during implementation.
let driver: CommerceDriver;
beforeEach(() => {
  vi.useFakeTimers();
  const values = new Map<string, string>();
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  });
  driver = createDemoCommerce();
});
afterEach(() => { driver.dispose(); vi.useRealTimers(); vi.unstubAllGlobals(); });
function prepare(scenario: CommerceScenario = 'normal') {
  driver.select('chat-1', { mode: 'split', productKey: 'demo-original', quantity: '20' });
  driver.setScenario(scenario);
  driver.prepare('chat-1');
  return driver.getSnapshot().proposals.at(-1)!;
}
function confirm(scenario: CommerceScenario = 'normal') {
  const item = prepare(scenario);
  driver.confirm('chat-1', item.key, item.revision);
  return item;
}

describe('local commerce presentation driver — not backend acceptance', () => {
  it('selection and proposal do not mutate the cart; reject keeps it empty', () => {
    const item = prepare();
    expect(item.lines.map((line) => line.quantity)).toEqual(['12.000', '8.000']);
    expect(item.total).toBe('561640.00');
    expect(driver.getSnapshot().cart?.lines).toEqual([]);
    driver.reject('chat-1', item.key);
    driver.confirm('chat-1', item.key, item.revision);
    vi.runAllTimers();
    expect(driver.getSnapshot().cart?.version).toBe(0);
  });
  it('double click and replay commit once, with no optimistic cart', () => {
    const item = confirm();
    const operationKey = driver.getSnapshot().proposals[0].operationKey;
    driver.confirm('chat-1', item.key, item.revision);
    expect(driver.getSnapshot().cart?.version).toBe(0);
    vi.runAllTimers();
    driver.confirm('chat-1', item.key, item.revision);
    expect(driver.getSnapshot().cart?.version).toBe(1);
    expect(driver.getSnapshot().cart?.lines).toHaveLength(2);
    expect(driver.getSnapshot().proposals[0].operationKey).toBe(operationKey);
  });
  it('stock 12 → 7 creates 7+13 and requires new consent', () => {
    const original = confirm('stock-changed');
    vi.runAllTimers();
    const replacement = driver.getSnapshot().proposals.at(-1)!;
    expect(driver.getSnapshot().cart?.version).toBe(0);
    expect(original.lines.map((line) => line.quantity)).toEqual(['12.000', '8.000']);
    expect(replacement.lines.map((line) => line.quantity)).toEqual(['7.000', '13.000']);
    expect(replacement.total).toBe('576040.00');
    expect(replacement.state).toBe('pending');
    driver.confirm('chat-1', original.key, original.revision);
    expect(driver.getSnapshot().busy).toBe(false);
    driver.confirm('chat-1', replacement.key, replacement.revision);
    vi.runAllTimers();
    expect(driver.getSnapshot().cart?.total).toBe('576040.00');
  });
  it('price changes replace the proposal without inheriting consent', () => {
    confirm('price-changed'); vi.runAllTimers();
    expect(driver.getSnapshot().cart?.version).toBe(0);
    expect(driver.getSnapshot().proposals.at(-1)?.total).toBe('573640.00');
    expect(driver.getSnapshot().proposals.at(-1)?.state).toBe('pending');
  });
  it('unknown outcome is resolved by lookup, not by another mutation', () => {
    confirm('unknown-outcome'); vi.runAllTimers();
    const item = driver.getSnapshot().proposals[0];
    expect(item.state).toBe('outcome_unknown');
    expect(driver.getSnapshot().cart?.version).toBe(0);
    driver.prepare('chat-1');
    expect(driver.getSnapshot().proposals).toHaveLength(1);
    driver.lookup(item.operationKey!);
    driver.lookup(item.operationKey!);
    expect(driver.getSnapshot().cart?.version).toBe(1);
    expect(driver.getSnapshot().proposals[0].state).toBe('confirmed');
  });
  it('reload preserves a committed unknown operation and allows lookup', () => {
    confirm('unknown-outcome'); vi.runAllTimers(); driver.dispose();
    driver = createDemoCommerce();
    const item = driver.getSnapshot().proposals[0];
    expect(item.state).toBe('outcome_unknown');
    driver.lookup(item.operationKey!);
    expect(driver.getSnapshot().cart?.version).toBe(1);
  });
  it('reload during confirmation does not assume success', () => {
    confirm(); driver.dispose(); driver = createDemoCommerce();
    const item = driver.getSnapshot().proposals[0];
    expect(item.state).toBe('outcome_unknown');
    driver.lookup(item.operationKey!);
    expect(driver.getSnapshot().proposals[0].state).toBe('failed');
    expect(driver.getSnapshot().cart?.version).toBe(0);
  });
  it('quantity edits invalidate the exact previous proposal', () => {
    const item = prepare();
    driver.select('chat-1', { mode: 'replace', productKey: 'demo-alternative', quantity: '20' });
    driver.confirm('chat-1', item.key, item.revision);
    expect(driver.getSnapshot().proposals[0].state).toBe('superseded');
    expect(driver.getSnapshot().cart?.version).toBe(0);
  });
  it('expired, wrong-conversation and wrong-version confirmations cannot commit', () => {
    const item = prepare();
    driver.confirm('chat-2', item.key, item.revision);
    driver.confirm('chat-1', item.key, item.revision + 1);
    expect(driver.getSnapshot().busy).toBe(false);
    vi.advanceTimersByTime(300_001);
    driver.confirm('chat-1', item.key, item.revision);
    expect(driver.getSnapshot().proposals[0].state).toBe('expired');
    expect(driver.getSnapshot().cart?.version).toBe(0);
  });
  it('unknown stock does not silently replace everything with the analogue', () => {
    confirm('offer-unavailable'); vi.runAllTimers();
    expect(driver.getSnapshot().proposals).toHaveLength(1);
    expect(driver.getSnapshot().proposals[0].state).toBe('superseded');
    expect(driver.getSnapshot().cart?.version).toBe(0);
  });
  it('session cleanup cancels pending work and erases the sample cart', () => {
    confirm(); driver.clearPrivateData(); vi.runAllTimers();
    expect(driver.getSnapshot().cart?.version).toBe(0);
    expect(driver.getSnapshot().proposals).toEqual([]);
    driver.dispose(); driver = createDemoCommerce();
    expect(driver.getSnapshot().cart?.lines).toEqual([]);
  });
});
