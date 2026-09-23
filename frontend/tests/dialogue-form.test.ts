import { describe, expect, it } from 'vitest';
import { dialogueForm, dialogueUpdate } from '../src/lib/dialogue-form';
import type { DialogueState } from '../src/client';

const state: DialogueState = {
  version: '12', category: 'breakers', budget: { amount: '10000.50', currency: 'KZT' },
  quantity: { value: '2', step: '1', unit: 'pcs' }, hardConstraints: { currentA: '16' },
  selectedArticles: ['000001'], attachmentId: 'upload-1', attachmentVersion: '4',
};

describe('editable dialogue parameters', () => {
  it('loads a persisted state and defaults an empty dialogue without inventing filters', () => {
    expect(dialogueForm(state)).toEqual({ category: 'breakers', budget: '10000.50', currency: 'KZT', quantity: '2', step: '1', unit: 'pcs', constraints: [{ key: 'currentA', value: '16' }] });
    expect(dialogueUpdate(dialogueForm({ version: '0' }), { version: '0' })).toEqual({ expectedVersion: '0' });
  });
  it('retains captured version and never rewrites selections or linked files', () => {
    const body = dialogueUpdate({ ...dialogueForm(state), category: 'cables' }, state);
    expect(body.expectedVersion).toBe('12');
    expect(body.category).toBe('cables');
    expect(body).not.toHaveProperty('selectedIndices');
    expect(body).not.toHaveProperty('attachmentId');
    expect(body).not.toHaveProperty('resultSetId');
  });
  it('explicitly clears removed filters rather than sending null which means retain', () => {
    const form = { ...dialogueForm(state), category: '', budget: '', quantity: '', constraints: [] };
    expect(dialogueUpdate(form, state)).toEqual({ expectedVersion: '12', clearFields: ['category', 'budget', 'quantity', 'hardConstraints'] });
  });
  it('keeps decimal money and quantity as exact strings including comma input', () => {
    const body = dialogueUpdate({ ...dialogueForm(state), budget: '123456789012,123456', quantity: '1,5', step: '0,5', unit: 'm' }, state);
    expect(body.budget).toEqual({ amount: '123456789012.123456', currency: 'KZT' });
    expect(body.quantity).toEqual({ value: '1.5', step: '0.5', unit: 'm' });
  });
  it('rejects non-numbers, invalid currency and quantities incompatible with the step', () => {
    expect(() => dialogueUpdate({ ...dialogueForm(state), budget: 'NaN' }, state)).toThrow('Бюджет');
    expect(() => dialogueUpdate({ ...dialogueForm(state), budget: '1234567890123.123456' }, state)).toThrow('Бюджет');
    expect(() => dialogueUpdate({ ...dialogueForm(state), quantity: '1.0000000' }, state)).toThrow('Количество');
    expect(() => dialogueUpdate({ ...dialogueForm(state), currency: 'тенге' }, state)).toThrow('Валюта');
    expect(() => dialogueUpdate({ ...dialogueForm(state), quantity: '1.5', step: '1' }, state)).toThrow('кратно');
    expect(() => dialogueUpdate({ ...dialogueForm(state), quantity: '0' }, state)).toThrow('Количество');
    expect(() => dialogueUpdate({ ...dialogueForm(state), step: '-1' }, state)).toThrow('Шаг');
  });
  it('rejects half-completed and duplicate constraints', () => {
    expect(() => dialogueUpdate({ ...dialogueForm(state), constraints: [{ key: 'currentA', value: '' }] }, state)).toThrow('название и значение');
    expect(() => dialogueUpdate({ ...dialogueForm(state), constraints: [{ key: 'currentA', value: '16' }, { key: 'currentA', value: '10' }] }, state)).toThrow('дважды');
  });
  it('retains user-defined constraint names without modifying the object prototype', () => {
    const body = dialogueUpdate({ ...dialogueForm(state), constraints: [{ key: '__proto__', value: 'own-property' }, { key: 'custom', value: 'yes' }] }, state);
    expect(Object.hasOwn(body.hardConstraints!, '__proto__')).toBe(true);
    expect(Object.getPrototypeOf(body.hardConstraints)).toBe(Object.prototype);
    expect(body.hardConstraints?.custom).toBe('yes');
  });
  it('requires a loaded server version before writing', () => {
    expect(() => dialogueUpdate(dialogueForm({}), {})).toThrow('Загрузите');
  });
});
