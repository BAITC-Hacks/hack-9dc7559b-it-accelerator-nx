const KEYWORDS: [RegExp, string][] = [
  [/автомат/i, '/catalog/breaker.svg'],
  [/кабель/i, '/catalog/cable.svg'],
  [/лампа/i, '/catalog/lamp.svg'],
];

export function productImage(title: string): string {
  return KEYWORDS.find(([pattern]) => pattern.test(title))?.[1] ?? '/catalog/generic.svg';
}
