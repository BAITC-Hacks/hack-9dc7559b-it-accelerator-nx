import type { LineView } from './model';
import { formatMoney, formatQuantity } from '../catalog/decimal';

export function LineItems({ lines }: { lines: readonly LineView[] }) {
  return <ul className="commerce-lines">{lines.map((line, index) => <li key={`${line.productKey}-${index}`}>
    <div><strong>{line.title}</strong><small>{line.article} · {line.warehouse} · оффер v{line.offerVersion}</small>
      <span>{formatQuantity(line.quantity)} {line.unit} × {formatMoney(line.unitPrice, line.currency)}</span></div>
    <strong>{formatMoney(line.total, line.currency)}</strong>
  </li>)}</ul>;
}
