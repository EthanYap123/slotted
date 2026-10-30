/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the API, without a trailing slash, e.g. http://localhost:8080 */
  readonly VITE_API_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
