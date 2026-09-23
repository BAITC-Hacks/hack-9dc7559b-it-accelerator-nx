import { defineConfig } from '@hey-api/openapi-ts';

/**
 * `npm run gen` запускается С ХОСТА, поэтому input — всегда localhost:8080
 * (порт проброшен наружу из compose). Внутри-сетевой backend:8080 здесь не работает.
 * Backend должен быть поднят: curl -s localhost:8080/v3/api-docs | head -c 200
 */
export default defineConfig({
  input: 'http://localhost:8080/v3/api-docs',
  output: { path: 'src/client', format: 'prettier' },
  plugins: ['@hey-api/client-axios', '@hey-api/sdk', '@hey-api/typescript'],
});
