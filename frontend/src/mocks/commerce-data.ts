import type { ProductView, SourceView } from '../components/cart/model';

// Synthetic records, NOT current prices/stock/certificates from ekt.kz.
export function demoProducts(): ProductView[] {
  const observedAt = new Date().toISOString();
  const common = {
    unit: 'шт.', quantityStep: '1', currency: 'KZT', warehouse: 'Демо-склад Алматы',
    freshness: 'fresh' as const, observedAt, offerVersion: 1,
  };
  return [
    { ...common, key: 'demo-original', article: 'DEMO-3P40-A', title: 'Автомат 3P · 40 А · серия A',
      price: '26930.00', stock: '12', certificateKeys: ['demo-certificate-a'],
      specs: [{ label: 'Полюса', value: '3' }, { label: 'Ток', value: '40 А' }, { label: 'Напряжение', value: '400 В' }, { label: 'Отключение', value: '10 кА' }] },
    { ...common, key: 'demo-alternative', article: 'DEMO-3P40-B', title: 'Автомат 3P · 40 А · серия B',
      price: '29810.00', stock: '40', certificateKeys: ['demo-certificate-b'],
      specs: [{ label: 'Полюса', value: '3' }, { label: 'Ток', value: '40 А' }, { label: 'Напряжение', value: '400 В' }, { label: 'Отключение', value: '20 кА' }] },
    { ...common, key: 'demo-unknown', article: 'DEMO-1P16-C', title: 'Автомат 1P · 16 А · серия C',
      price: null, stock: null, freshness: 'unknown', certificateKeys: [],
      specs: [{ label: 'Полюса', value: '1' }, { label: 'Ток', value: '16 А' }, { label: 'Напряжение', value: '230 В' }] },
  ];
}
export const demoSources: SourceView[] = [
  { key: 'demo-certificate-a', title: 'Учебный паспорт серии A', version: 'demo-v1', kind: 'certificate',
    content: 'Синтетический документ для демонстрации ссылки на источник. Это не сертификат реального изделия и не подтверждение соответствия.\n\nВ учебной записи: 3 полюса, 40 А, 400 В, 10 кА. Перед покупкой необходим подлинный документ поставщика.' },
  { key: 'demo-certificate-b', title: 'Учебный паспорт серии B', version: 'demo-v1', kind: 'certificate',
    content: 'Синтетический документ. Не является действующим сертификатом.\n\nУчебная запись: 3 полюса, 40 А, 400 В, 20 кА. Совместимость примера задаётся fixture, а не определяется моделью.' },
  { key: 'demo-delivery', title: 'Пример источника: доставка и оплата', version: 'demo-v1', kind: 'faq',
    content: 'Это пример versioned FAQ, а не условия ekt.kz.\n\nДемо не задаёт реальные тарифы, сроки доставки или минимальную партию. После подключения базы знаний ответы должны ссылаться на актуальную версию документа партнёра.' },
];
