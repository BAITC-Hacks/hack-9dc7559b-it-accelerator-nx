import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createLiveCommerce, proposalView } from '../src/lib/commerce-live';
import type { CommerceDriver } from '../src/components/cart/model';
import { queryClient } from '../src/lib/query';
import * as sdk from '../src/client/sdk.gen';
import { dialogueQueryKey, publishDialogue } from '../src/lib/dialogue-state';
vi.mock('../src/lib/api', () => ({ auth: { get: () => null } }));
vi.mock('../src/client/sdk.gen', () => ({ getDialogue: vi.fn(), getResultSet: vi.fn(), updateDialogue: vi.fn(), prepareProposal: vi.fn(), confirmProposal: vi.fn(), getCart: vi.fn(), getProposal: vi.fn(), rejectProposal: vi.fn(), getCartOperation: vi.fn() }));
const proposal = { id: 'proposal-1', conversationId: 'conversation-1', revision: '3', digest: 'server-digest', expectedCartVersion: '0', operationId: 'operation-1', status: 'pending', expiresAt: '2099-01-01T00:00:00Z', lines: [{ article: '000001', addQuantity: { value: '2', unit: 'pcs', step: '1' }, unitPrice: { amount: '1250.05', currency: 'KZT' }, warehouse: 'ALA', offerVersion: '8' }] };
let driver: CommerceDriver;
beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal('window', new EventTarget()); vi.stubGlobal('sessionStorage', { getItem: () => null });
  vi.mocked(sdk.getDialogue).mockResolvedValue({ data: { version: '1' } } as never);
  vi.mocked(sdk.updateDialogue).mockResolvedValue({ data: { version: '2', lastResultSetId: 'bound-result' } } as never);
  vi.mocked(sdk.prepareProposal).mockResolvedValue({ data: proposal } as never);
  vi.mocked(sdk.getCart).mockResolvedValue({ data: { version: '0', lines: [] } } as never);
  driver = createLiveCommerce();
});
afterEach(() => { driver.dispose(); queryClient.clear(); vi.unstubAllGlobals(); vi.clearAllMocks(); });
describe('live commerce API integration', () => {
  it('binds search to the conversation before preparing and never confirms implicitly', async () => {
    const id = await driver.prepareSelection!('conversation-1', { resultSetId: 'saved-search', lines: [{ article: '000001', addQuantity: '2', unit: 'pcs', warehouse: 'ALA' }] });
    expect(id).toBe('proposal-1');
    expect(sdk.updateDialogue).toHaveBeenCalledWith(expect.objectContaining({ body: expect.objectContaining({ resultSetId: 'saved-search', expectedVersion: '1' }) }));
    expect(sdk.prepareProposal).toHaveBeenCalledWith(expect.objectContaining({ body: expect.objectContaining({ resultSetId: 'bound-result', expectedStateVersion: '2' }) }));
    expect(sdk.confirmProposal).not.toHaveBeenCalled();
    expect(driver.getSnapshot().proposals[0].total).toBe('2500.10');
  });
  it('preserves unknown confirmation outcome until operation lookup resolves it', async () => {
    await driver.prepareSelection!('conversation-1', { resultSetId: 'search', lines: [] });
    vi.mocked(sdk.confirmProposal).mockRejectedValue(new Error('Network disconnected'));
    driver.confirm('conversation-1', 'proposal-1', 3);
    await vi.waitFor(() => expect(driver.getSnapshot().busy).toBe(false));
    expect(driver.getSnapshot().proposals[0].state).toBe('outcome_unknown');
    expect(sdk.confirmProposal).toHaveBeenCalledWith(expect.objectContaining({ headers: { 'Idempotency-Key': 'browser-confirm-proposal-1-3' }, body: { revision: '3', digest: 'server-digest', origin: 'button' } }));
    vi.mocked(sdk.getCartOperation).mockResolvedValue({ data: { id: 'operation-1', proposalId: 'proposal-1', status: 'succeeded' } } as never);
    driver.lookup('operation-1'); await vi.waitFor(() => expect(driver.getSnapshot().busy).toBe(false));
    expect(driver.getSnapshot().proposals[0].state).toBe('confirmed');
    expect(sdk.confirmProposal).toHaveBeenCalledTimes(1);
  });
  it('maps decimal amounts without floating-point drift and rejects missing price', () => {
    expect(proposalView(proposal).total).toBe('2500.10');
    expect(() => proposalView({ ...proposal, lines: [{ article: 'x', addQuantity: { value: '1' } }] })).toThrow('цену');
  });
  it.each(['999999', undefined])('uses the selected product article after attachment review instead of extracted article %s', async article => {
    vi.mocked(sdk.getResultSet).mockResolvedValue({ data: { id: 'bound-result', products: [{ id: 'selected-product-id', article: '000001' }], offers: [] } } as never);
    const result = await driver.prepareReviewed('conversation-1', { jobKey: 'attachment-1', version: '7', lines: [{ productKey: 'selected-product-id', article, quantity: '2', unit: 'pcs', warehouse: 'ALA' }] });
    expect(result).toBe('proposal-1');
    expect(sdk.getResultSet).toHaveBeenCalledWith(expect.objectContaining({ path: { id: 'conversation-1', resultId: 'bound-result' } }));
    expect(sdk.prepareProposal).toHaveBeenCalledWith(expect.objectContaining({ body: expect.objectContaining({ lines: [{ article: '000001', addQuantity: '2', unit: 'pcs', warehouse: 'ALA' }] }) }));
    expect(sdk.confirmProposal).not.toHaveBeenCalled();
  });
  it('rejects an attachment selection that is absent from the bound reviewed result', async () => {
    vi.mocked(sdk.getResultSet).mockResolvedValue({ data: { id: 'bound-result', products: [{ id: 'other-product', article: '999999' }], offers: [] } } as never);
    expect(await driver.prepareReviewed('conversation-1', { jobKey: 'attachment-1', version: '7', lines: [{ productKey: 'selected-product-id', article: '999999', quantity: '2', unit: 'pcs', warehouse: 'ALA' }] })).toBeNull();
    expect(sdk.prepareProposal).not.toHaveBeenCalled();
    expect(driver.getSnapshot().notice).toContain('отсутствует');
  });
  it('clears the product list when switching to a conversation without results', async () => {
    vi.mocked(sdk.getDialogue).mockImplementation(async options => ({ data: options.path.id === 'conversation-1' ? { version: '1', lastResultSetId: 'results-1' } : { version: '1' } }) as never);
    vi.mocked(sdk.getResultSet).mockResolvedValue({ data: { id: 'results-1', products: [{ id: 'product-1', article: '000001' }] } } as never);
    await driver.hydrateConversation!('conversation-1');
    expect(driver.getSnapshot().products[0].article).toBe('000001');
    const switching = driver.hydrateConversation!('conversation-2');
    expect(driver.getSnapshot().products).toEqual([]);
    await switching;
    expect(driver.getSnapshot().products).toEqual([]);
  });
  it('does not show a late result from the previous conversation', async () => {
    let resolveFirst!: (value: never) => void;
    vi.mocked(sdk.getDialogue).mockImplementation(async options => ({ data: { version: '1', lastResultSetId: `results-${options.path.id}` } }) as never);
    vi.mocked(sdk.getResultSet).mockImplementation(options => options.path.id === 'conversation-1' ? new Promise<never>(resolve => { resolveFirst = resolve; }) : Promise.resolve({ data: { id: 'results-2', products: [{ article: '000002' }] } }) as never);
    const first = driver.hydrateConversation!('conversation-1');
    await vi.waitFor(() => expect(sdk.getResultSet).toHaveBeenCalledTimes(1));
    await driver.hydrateConversation!('conversation-2');
    expect(driver.getSnapshot().products[0].article).toBe('000002');
    resolveFirst({ data: { id: 'results-1', products: [{ article: '000001' }] } } as never);
    await first;
    expect(driver.getSnapshot().products[0].article).toBe('000002');
  });
  it('publishes the server state after preparing a proposal', async () => {
    vi.mocked(sdk.getDialogue).mockResolvedValueOnce({ data: { version: '1' } } as never).mockResolvedValueOnce({ data: { version: '3', lastResultSetId: 'bound-result', activeProposalId: 'proposal-1' } } as never);
    await driver.prepareSelection!('conversation-1', { resultSetId: 'saved-search', lines: [] });
    expect(queryClient.getQueryData(dialogueQueryKey('conversation-1'))).toMatchObject({ version: '3', activeProposalId: 'proposal-1' });
  });
  it('keeps newer shared parameters when a delayed dialogue read returns', async () => {
    let resolveState!: (value: never) => void;
    vi.mocked(sdk.getDialogue).mockImplementation(() => new Promise<never>(resolve => { resolveState = resolve; }));
    const hydration = driver.hydrateConversation!('conversation-1');
    await vi.waitFor(() => expect(sdk.getDialogue).toHaveBeenCalledTimes(1));
    publishDialogue('conversation-1', { version: '3', category: 'new-category' });
    resolveState({ data: { version: '1', category: 'old-category' } } as never);
    await hydration;
    expect(queryClient.getQueryData(dialogueQueryKey('conversation-1'))).toMatchObject({ version: '3', category: 'new-category' });
  });
});
