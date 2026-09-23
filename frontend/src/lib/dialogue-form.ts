import type { DialogueState, Money, Quantity, UpdateDialogue } from '../client';
import { scaled } from '../components/catalog/decimal';

export const dialogueCategories = [
  { value: 'breakers', label: 'Автоматические выключатели' },
  { value: 'cables', label: 'Кабели и провода' },
  { value: 'lamps', label: 'Лампы и освещение' },
];

export const constraintLabels: Record<string, string> = {
  poles: 'Количество полюсов', currentA: 'Ток, А', voltageV: 'Напряжение, В',
  curve: 'Характеристика отключения', breakingCapacityKa: 'Отключающая способность, кА',
  cores: 'Количество жил', crossSectionMm2: 'Сечение, мм²', material: 'Материал',
  insulation: 'Изоляция', base: 'Цоколь', powerW: 'Мощность, Вт',
  colorTemperatureK: 'Цветовая температура, К', brand: 'Бренд',
};

export type DialogueForm = {
  category: string;
  budget: string;
  currency: string;
  quantity: string;
  unit: string;
  step: string;
  constraints: Array<{ key: string; value: string }>;
};

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
function text(value: unknown, fallback = '') { return typeof value === 'string' ? value : fallback; }

export function dialogueForm(state: DialogueState): DialogueForm {
  const budget = record(state.budget);
  const quantity = record(state.quantity);
  return {
    category: text(state.category), budget: text(budget.amount), currency: text(budget.currency, 'KZT'),
    quantity: text(quantity.value), unit: text(quantity.unit, 'pcs'), step: text(quantity.step, '1'),
    constraints: Object.entries(state.hardConstraints ?? {}).map(([key, value]) => ({ key, value })),
  };
}

function decimalInput(value: string, label: string, allowZero = false): string {
  const normalized = value.trim().replace(',', '.');
  const parsed = scaled(normalized, 6);
  const [integer, fraction = ''] = normalized.split('.');
  const exceedsPrecision = integer.replace(/^0+/, '').length + fraction.length > 18 || fraction.length > 6;
  if (parsed === null || exceedsPrecision || (allowZero ? parsed < 0n : parsed <= 0n)) {
    throw new Error(`${label}: введите ${allowZero ? 'неотрицательное' : 'положительное'} число, до 18 цифр, из них не более 6 после запятой.`);
  }
  return normalized;
}

export function dialogueUpdate(form: DialogueForm, base: DialogueState): UpdateDialogue {
  if (!base.version) throw new Error('Загрузите параметры перед сохранением.');
  const initial = dialogueForm(base);
  const clearFields: string[] = [];
  const category = form.category.trim();
  if (!category && initial.category) clearFields.push('category');
  let budget: Money | undefined;
  if (form.budget.trim()) {
    const currency = form.currency.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(currency)) throw new Error('Валюта: укажите трёхбуквенный код, например KZT.');
    budget = { amount: decimalInput(form.budget, 'Бюджет', true), currency };
  } else if (initial.budget) clearFields.push('budget');
  let quantity: Quantity | undefined;
  if (form.quantity.trim()) {
    const value = decimalInput(form.quantity, 'Количество');
    const step = decimalInput(form.step, 'Шаг количества');
    if (!form.unit.trim()) throw new Error('Выберите единицу количества.');
    if (scaled(value, 6)! % scaled(step, 6)! !== 0n) throw new Error('Количество должно быть кратно шагу.');
    quantity = { value, step, unit: form.unit.trim() };
  } else if (initial.quantity) clearFields.push('quantity');
  const hardConstraints: Record<string, string> = {};
  for (const entry of form.constraints) {
    const key = entry.key.trim();
    const value = entry.value.trim();
    if (!key && !value) continue;
    if (!key || !value) throw new Error('Заполните название и значение каждого ограничения.');
    if (Object.hasOwn(hardConstraints, key)) throw new Error('Один параметр нельзя указать дважды.');
    Object.defineProperty(hardConstraints, key, { value, enumerable: true, configurable: true, writable: true });
  }
  if (!Object.keys(hardConstraints).length && initial.constraints.length) clearFields.push('hardConstraints');
  return {
    expectedVersion: base.version,
    ...(category ? { category } : {}),
    ...(budget ? { budget } : {}),
    ...(quantity ? { quantity } : {}),
    ...(Object.keys(hardConstraints).length ? { hardConstraints } : {}),
    ...(clearFields.length ? { clearFields } : {}),
  };
}
