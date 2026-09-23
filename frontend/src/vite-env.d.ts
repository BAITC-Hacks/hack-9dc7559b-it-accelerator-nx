/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Адрес backend ИЗ БРАУЗЕРА. Build-time переменная — запекается в бандл. */
  readonly VITE_API_URL: string;
  /** Comma-separated parent origins allowed for embed hello handshake. */
  readonly VITE_EMBED_ALLOWED_ORIGINS: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
