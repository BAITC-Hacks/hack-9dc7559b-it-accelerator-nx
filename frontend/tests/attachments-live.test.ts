import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Snapshot } from '../src/client/types.gen';
import { queryClient } from '../src/lib/query';
import * as sdk from '../src/client/sdk.gen';
import { createLiveAttachmentDriver, mapAttachment, validReviewRow } from '../src/lib/attachments-live';
import type { AttachmentDriver } from '../src/components/attachments/model';

vi.mock('../src/client/sdk.gen', () => ({ capabilities: vi.fn(), status: vi.fn(), upload: vi.fn(), review: vi.fn(), reprocess: vi.fn(), delete_: vi.fn(), source1: vi.fn(), getDialogue: vi.fn(), updateDialogue: vi.fn() }));
const snapshot: Snapshot = { attachmentId: 'file-1', conversationId: 'chat-1', version: '2', status: 'READY', filename: 'order.xlsx',
  rows: [{ extracted: { id: 'row-1', rawText: '001 - 2,5 м', article: '001', quantity: '2.5', unit: 'm', source: { sheet: 'Заказ', row: 2 } }, status: 'MATCHED',
    candidates: [{ productId: 'product-1', article: '001', name: 'Кабель', unit: 'm', minimum: '0.5', step: '0.5', warehouses: ['MAIN'] }] }], selections: [] };
let driver: AttachmentDriver | undefined;
beforeEach(() => {
  vi.clearAllMocks(); queryClient.clear();
  const values = new Map<string, string>();
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) });
  vi.stubGlobal('document', { visibilityState: 'visible' });
  vi.mocked(sdk.capabilities).mockResolvedValue({ data: { maxBytes: 10485760, extensions: ['xlsx'], ocrAvailable: true } } as never);
  vi.mocked(sdk.status).mockResolvedValue({ data: snapshot } as never);
  vi.mocked(sdk.upload).mockResolvedValue({ data: { attachmentId: 'file-1', version: '1', deduplicated: false } } as never);
});
afterEach(() => { driver?.dispose(); driver = undefined; queryClient.clear(); vi.unstubAllGlobals(); });
async function ready() {
  driver = createLiveAttachmentDriver();
  await vi.waitFor(() => expect(driver?.getSnapshot().mode).toBe('live'));
  return driver;
}
describe('live attachment workflow', () => {
  it('keeps candidate suggestions separate from explicit selection and unknown quantity empty', () => {
    const mapped = mapAttachment(snapshot);
    expect(mapped.rows[0]).toMatchObject({ quantity: '2.5', selectedKey: null, reviewed: false, location: 'Лист Заказ · Строка 2' });
    const unknown = mapAttachment({ ...snapshot, rows: [{ extracted: { id: 'unknown', rawText: '?' }, candidates: [] }] });
    expect(unknown.rows[0].quantity).toBe('');
    expect(validReviewRow(unknown.rows[0])).toBe(false);
  });
  it('validates decimal steps without money/quantity float rounding', () => {
    const row = { ...mapAttachment(snapshot).rows[0], selectedKey: 'product-1', warehouse: 'MAIN' };
    expect(validReviewRow(row)).toBe(true);
    expect(validReviewRow({ ...row, quantity: '2.6' })).toBe(false);
    expect(validReviewRow({ ...row, quantity: '0' })).toBe(false);
  });
  it('uploads the actual file and saves explicit correction with server revision and warehouse', async () => {
    const active = await ready();
    const file = new File(['data'], 'order.xlsx');
    await active.upload('chat-1', file);
    expect(sdk.upload).toHaveBeenCalledWith(expect.objectContaining({ body: { file }, path: { id: 'chat-1' } }));
    vi.mocked(sdk.review).mockResolvedValue({ data: { ...snapshot, version: '3', selections: [{ rowId: 'row-1', productId: 'product-1', quantity: '2.5', unit: 'm', warehouse: 'MAIN', selected: true }] } } as never);
    expect(await active.review('file-1', 'row-1', 2, { selectedKey: 'product-1', warehouse: 'MAIN' })).toBe(true);
    expect(sdk.review).toHaveBeenCalledWith(expect.objectContaining({ body: { expectedVersion: '2', selections: [{ rowId: 'row-1', productId: 'product-1', quantity: '2.5', unit: 'm', warehouse: 'MAIN', selected: true }] } }));
    expect(active.getSnapshot().jobs[0].rows[0].reviewed).toBe(true);
    expect(active.getSnapshot().jobs[0].version).toBe(3);
  });
  it('refreshes a stale review and does not claim it was saved', async () => {
    const active = await ready(); await active.open?.('file-1');
    vi.mocked(sdk.review).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'stale_attachment' } } });
    vi.mocked(sdk.status).mockResolvedValue({ data: { ...snapshot, version: '4' } } as never);
    expect(await active.review('file-1', 'row-1', 2, { selectedKey: 'product-1', warehouse: 'MAIN' })).toBe(false);
    expect(active.getSnapshot().jobs[0].version).toBe(4);
    expect(active.getSnapshot().notice).toContain('актуальная версия');
  });
  it('does not restore a late upload after private data is cleared', async () => {
    const active = await ready();
    let resolve!: (result: never) => void;
    vi.mocked(sdk.upload).mockImplementation(() => new Promise<never>((done) => { resolve = done; }));
    const pending = active.upload('chat-1', new File(['data'], 'order.xlsx'));
    await vi.waitFor(() => expect(resolve).toBeDefined());
    active.clearPrivateData(); resolve({ data: { attachmentId: 'file-1' } } as never); await pending;
    expect(active.getSnapshot().jobs).toEqual([]);
    expect(sdk.status).not.toHaveBeenCalled();
  });
  it('reprocesses the current revision and deletes through authenticated SDK', async () => {
    const active = await ready(); await active.open?.('file-1');
    vi.mocked(sdk.reprocess).mockResolvedValue({ data: { attachmentId: 'file-1', version: '3' } } as never);
    await active.reprocess?.('file-1');
    expect(sdk.reprocess).toHaveBeenCalledWith(expect.objectContaining({ body: { expectedVersion: '2' } }));
    vi.mocked(sdk.delete_).mockResolvedValue({ data: undefined } as never);
    await active.remove?.('file-1');
    expect(sdk.delete_).toHaveBeenCalledWith(expect.objectContaining({ path: { id: 'file-1' } }));
    expect(active.getSnapshot().jobs).toEqual([]);
  });
});
