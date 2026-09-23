/** Presentation models only. Live values must be mapped from generated server
 * DTOs. The dev driver below uses explicitly synthetic, deterministic records.
 */
export interface SourceView {
  key: string;
  title: string;
  version: string;
  kind: 'certificate' | 'faq';
  content: string;
}
export interface ProductView {
  key: string;
  article: string;
  title: string;
  unit: string;
  quantityStep: string;
  price: string | null;
  currency: string;
  stock: string | null;
  warehouse: string;
  freshness: 'fresh' | 'stale' | 'unknown';
  observedAt: string;
  offerVersion: number;
  specs: { label: string; value: string }[];
  certificateKeys: string[];
}
export interface SelectionView {
  mode: 'single' | 'split' | 'replace';
  productKey: string;
  quantity: string;
}
export interface LineView {
  productKey: string;
  article: string;
  title: string;
  quantity: string;
  unit: string;
  warehouse: string;
  unitPrice: string;
  total: string;
  currency: string;
  offerVersion: number;
}
export interface CartView {
  version: number;
  lines: LineView[];
  total: string;
  currency: string;
  observedAt: string;
}
export type ProposalState = 'pending' | 'confirming' | 'confirmed' | 'rejected'
  | 'expired' | 'superseded' | 'outcome_unknown' | 'failed';
export interface ProposalView {
  key: string;
  revision: number;
  conversationKey: string;
  expectedCartVersion: number;
  lines: readonly LineView[];
  total: string;
  currency: string;
  expiresAt: string;
  state: ProposalState;
  operationKey: string | null;
  note: string | null;
  origin?: 'attachment' | 'catalog';
}
export type CommerceScenario = 'normal' | 'stock-changed' | 'price-changed'
  | 'unknown-outcome' | 'stale-cart' | 'offer-unavailable' | 'not-found';
export interface CommerceView {
  mode: 'unavailable' | 'loading' | 'demo' | 'live';
  products: ProductView[];
  sources: SourceView[];
  selections: Record<string, SelectionView>;
  proposals: ProposalView[];
  cart: CartView | null;
  scenario: CommerceScenario;
  busy: boolean;
  notice: string | null;
}
export interface CommerceDriver {
  getSnapshot: () => CommerceView;
  subscribe: (listener: () => void) => () => void;
  select: (conversation: string, selection: SelectionView) => void;
  prepare: (conversation: string) => void;
  prepareSelection?: (conversation: string, source: { resultSetId?: string; fulfillmentOptionId?: string; lines: import('../../client/types.gen').Selection[] }) => Promise<string | null>;
  hydrateConversation?: (conversation: string) => Promise<void>;
  prepareReviewed: (conversation: string, source: { jobKey: string; version: number | string; lines: { productKey: string; quantity: string; article?: string; unit?: string; warehouse?: string }[] }) => string | null | Promise<string | null>;
  confirm: (conversation: string, proposal: string, revision: number) => void;
  reject: (conversation: string, proposal: string) => void;
  lookup: (operation: string) => void;
  refreshCart: () => void;
  invalidateSelection: (conversation: string) => void;
  setScenario: (scenario: CommerceScenario) => void;
  clearPrivateData: () => void;
  dispose: () => void;
}
