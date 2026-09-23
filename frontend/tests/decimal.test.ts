import { describe, expect, it } from 'vitest';
import { formatMoney, scaled, validQuantity } from '../src/components/catalog/decimal';

describe('decimal strings returned by the live catalog', () => {
  it('formats database scale-six prices without treating trailing zeroes as missing data', () => {
    expect(formatMoney('1500.000000')).toBe(formatMoney('1500'));
    expect(formatMoney('1250.050000')).toBe(formatMoney('1250.05'));
    expect(formatMoney('0.000000')).toBe('0 ₸');
    expect(formatMoney('12.500000', 'USD')).toBe('12,50 USD');
  });
  it('preserves meaningful fractional prices and rejects invalid values', () => {
    expect(formatMoney('1.000001')).toBe('1,000001 ₸');
    expect(formatMoney(null)).toBe('Цена не подтверждена');
    expect(formatMoney('not-a-price')).toBe('Цена не подтверждена');
  });
  it('accepts scale-six quantity steps without discarding meaningful precision', () => {
    expect(scaled('1500.000000', 2)).toBe(150000n);
    expect(validQuantity('2', '1.000000')).toBe(true);
    expect(validQuantity('0.5', '0.500000')).toBe(true);
    expect(scaled('1.000001', 2)).toBeNull();
  });
});
