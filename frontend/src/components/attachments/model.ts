import type { Candidate, Capabilities } from '../../client/types.gen';
/** UI presentation state. Server payloads use the generated DTOs. */
export type FileFamily = 'xls' | 'xlsx' | 'doc' | 'docx' | 'pdf' | 'jpg' | 'jpeg';
export type JobStage = 'queued' | 'extracting' | 'ocr' | 'matching' | 'ready' | 'review' | 'failed';
export type Confidence = 'matched' | 'ambiguous' | 'unmatched' | 'needs_quantity';
export interface ReviewRow {
  key: string;
  location: string;
  rawText: string;
  article: string | null;
  quantity: string;
  unit: string;
  confidence: Confidence;
  warnings: string[];
  candidateKeys: string[];
  selectedKey: string | null;
  excluded: boolean;
  candidates?: Candidate[];
  warehouse?: string;
  reviewed?: boolean;
  observations?: string[];
}
export interface AttachmentJob {
  key: string;
  conversation: string;
  fingerprint: string;
  fileName: string;
  family: FileFamily;
  size: number;
  stage: JobStage;
  progress: number;
  version: number;
  rows: ReviewRow[];
  error: string | null;
  createdAt: string;
  proposalKey: string | null;
  serverVersion?: string;
  warnings?: string[];
}
export interface AttachmentView {
  mode: 'loading' | 'unavailable' | 'demo' | 'live';
  jobs: AttachmentJob[];
  notice: string | null;
  busy: boolean;
  capabilities?: Capabilities;
}
export interface AttachmentDriver {
  getSnapshot: () => AttachmentView;
  subscribe: (listener: () => void) => () => void;
  upload: (conversation: string, file: File) => Promise<void>;
  review: (jobKey: string, rowKey: string, expectedVersion: number, patch: Partial<Pick<ReviewRow, 'quantity' | 'unit' | 'selectedKey' | 'excluded' | 'warehouse'>>) => boolean | Promise<boolean>;
  refresh: () => void;
  attachProposal: (jobKey: string, proposalKey: string) => void;
  clearPrivateData: () => void;
  dispose: () => void;
  remove?: (jobKey: string) => Promise<void>;
  download?: (jobKey: string) => Promise<void>;
  reprocess?: (jobKey: string) => Promise<void>;
  open?: (jobKey: string) => Promise<void>;
  linkConversation?: (jobKey: string) => Promise<boolean>;
}
