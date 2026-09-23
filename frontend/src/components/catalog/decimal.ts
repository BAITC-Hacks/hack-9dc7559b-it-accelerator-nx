/** Decimal-string helpers. Never use Number for money or authoritative totals. */
export function scaled(value: string, places: number): bigint | null {
  if (!/^\d+(?:\.\d+)?$/.test(value) || value.length > 24) return null;
  const [integer, fraction = ''] = value.split('.');
  if (fraction.length > places) return null;
  return BigInt(integer) * 10n ** BigInt(places) + BigInt(fraction.padEnd(places, '0') || '0');
}
export function decimal(value: bigint, places: number) {
  const power = 10n ** BigInt(places);
  return `${value / power}.${(value % power).toString().padStart(places, '0')}`;
}
export function formatQuantity(value: string) {
  return value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '').replace('.', ',');
}
export function formatMoney(value: string | null, currency = 'KZT') {
  if (value === null || scaled(value, 2) === null) return 'Цена не подтверждена';
  const [integer, fraction = '00'] = value.split('.');
  const grouped = new Intl.NumberFormat('ru-RU').format(BigInt(integer));
  return `${grouped}${fraction === '00' ? '' : `,${fraction.padEnd(2, '0')}`} ${currency === 'KZT' ? '₸' : currency}`;
}
export function validQuantity(value: string, step: string) {
  const quantity = scaled(value, 3);
  const increment = scaled(step, 3);
  return quantity !== null && increment !== null && quantity > 0n && increment > 0n && quantity % increment === 0n;
}
