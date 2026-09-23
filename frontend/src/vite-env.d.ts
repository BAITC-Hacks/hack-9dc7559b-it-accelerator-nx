/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Адрес backend ИЗ БРАУЗЕРА. Build-time переменная — запекается в бандл. */
  readonly VITE_API_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
