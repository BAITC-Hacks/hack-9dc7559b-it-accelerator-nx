/** Local presentation state. These are NOT HTTP DTOs or a ChatEvent wire schema.
 * A future backend driver maps generated DTOs into this view model.
 */
export type ReplyPhase = 'sending' | 'streaming' | 'recovering' | 'interrupted'
  | 'completed' | 'cancelled' | 'failed';

export interface MessageView {
  key: string;
  author: 'customer' | 'assistant';
  text: string;
  createdAt: string;
}

export interface ContextField {
  label: string;
  value: string;
}

export interface ReplyView {
  key: string;
  messageKey: string;
  submissionKey: string;
  prompt: string;
  phase: ReplyPhase;
  generation: number;
  lastChunk: number;
  recoveryCount: number;
  faultShown: boolean;
  notice: string | null;
  retryAt: number | null;
}

export interface ConversationView {
  key: string;
  title: string;
  updatedAt: string;
  draft: string;
  messages: MessageView[];
  context: ContextField[];
  reply: ReplyView | null;
  visibleCount: number;
  loadingEarlier: boolean;
}

export type DemoScenario = 'normal' | 'disconnect' | 'duplicate' | 'expired'
  | 'unauthorized' | 'busy' | 'send-timeout';

export interface WorkspaceView {
  mode: 'unavailable' | 'loading' | 'demo';
  conversations: ConversationView[];
  selectedKey: string | null;
  scenario: DemoScenario;
  banner: string | null;
  storageAvailable: boolean;
}

/** Frontend UI boundary only. No URLs, provider DTOs or invented API responses. */
export interface ChatDriver {
  getSnapshot: () => WorkspaceView;
  subscribe: (listener: () => void) => () => void;
  newConversation: () => void;
  selectConversation: (key: string) => void;
  setDraft: (key: string, text: string) => void;
  send: (key: string, text: string) => void;
  resume: (key: string) => void;
  stop: (key: string) => void;
  loadEarlier: (key: string) => void;
  setScenario: (scenario: DemoScenario) => void;
  clearPrivateData: () => void;
  dispose: () => void;
}

export function isReplyActive(reply: ReplyView | null) {
  return reply !== null && ['sending', 'streaming', 'recovering'].includes(reply.phase);
}

export function needsRecovery(reply: ReplyView | null) {
  return reply?.phase === 'interrupted';
}
