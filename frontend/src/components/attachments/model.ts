/** UI presentation state only. Backend ATT DTOs and capabilities must be generated. */
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
}
export interface AttachmentView {
  mode: 'loading' | 'unavailable' | 'demo';
  jobs: AttachmentJob[];
  notice: string | null;
  busy: boolean;
}
export interface AttachmentDriver {
  getSnapshot: () => AttachmentView;
  subscribe: (listener: () => void) => () => void;
  upload: (conversation: string, file: File) => Promise<void>;
  review: (jobKey: string, rowKey: string, expectedVersion: number, patch: Partial<Pick<ReviewRow, 'quantity' | 'unit' | 'selectedKey' | 'excluded'>>) => boolean;
  refresh: () => void;
  attachProposal: (jobKey: string, proposalKey: string) => void;
  clearPrivateData: () => void;
  dispose: () => void;
}
