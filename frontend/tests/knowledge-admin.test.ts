import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import { catalogJobId, isJobPending, parseCatalogImport, serviceError, sourceHref } from '../src/lib/knowledge';

describe('knowledge and administration boundaries', () => {
  it('keeps leading zero article identifiers and the full import document', () => {
    const document = { schemaVersion: 1, version: 'v1', products: [{ article: '000123', name: 'Sample', price: 500 }] };
    expect(parseCatalogImport(JSON.stringify(document))).toEqual(document);
    expect(() => parseCatalogImport(JSON.stringify({ ...document, products: [{ article: 123 }] }))).toThrow('строкой');
  });

  it('rejects malformed and empty catalog uploads before replacement', () => {
    expect(() => parseCatalogImport('{broken')).toThrow('JSON');
    expect(() => parseCatalogImport('[]')).toThrow('объект');
    expect(() => parseCatalogImport('{"schemaVersion":1,"version":"v1","products":[]}')).toThrow('хотя бы один');
    expect(() => parseCatalogImport('{"schemaVersion":1,"products":[{"article":"1"}]}')).toThrow('version');
  });

  it('never rounds catalog job IDs through the generated numeric path type', () => {
    expect(catalogJobId('123')).toBe(123);
    for (const value of ['9007199254740993', '1e3', '0', '-1', '1.5']) expect(() => catalogJobId(value)).toThrow();
  });

  it('stops polling on failed, completed or superseded jobs', () => {
    for (const state of ['PENDING', 'QUEUED', 'RUNNING']) expect(isJobPending(state)).toBe(true);
    for (const state of ['SUCCEEDED', 'FAILED', 'SUPERSEDED', undefined]) expect(isJobPending(state)).toBe(false);
  });

  it('constructs internal citation links without allowing query or path injection', () => {
    expect(sourceHref('document-id', 'version-id')).toBe('/sources/document-id?version=version-id');
    expect(sourceHref('../outside', 'version&token=value')).toBe('/sources/..%2Foutside?version=version%26token%3Dvalue');
  });

  it('explains denied admin rights, stale versions, and unavailable backend', () => {
    const failure = (status: number) => new AxiosError('failed', undefined, undefined, undefined, { status, data: {} } as AxiosResponse);
    expect(serviceError(failure(403))).toContain('ADMIN');
    expect(serviceError(failure(409))).toContain('актуальную версию');
    expect(serviceError(new AxiosError('Network Error'))).toContain('Backend недоступен');
  });
});
