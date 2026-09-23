import { Check, ClipboardList, ShieldCheck, SlidersHorizontal } from 'lucide-react';
import type { ContextField } from './model';

export function ContextPanel({ fields }: { fields: ContextField[] }) {
  return (
    <div className="context-panel-inner">
      <div className="context-heading"><SlidersHorizontal size={16} /><h2>Ваш подбор</h2></div>
      <p className="context-caption">Параметры текущего диалога</p>
      {fields.length ? <dl className="context-fields">{fields.map((field) => (
        <div key={field.label}><dt>{field.label}</dt><dd><Check size={13} />{field.value}</dd></div>
      ))}</dl> : <div className="context-empty"><ClipboardList size={23} strokeWidth={1.4} /><p>Пока без параметров</p><span>Название, количество и ваши пожелания появятся здесь.</span></div>}
      <div className="context-guide"><span className="eyebrow">КАК ЭТО РАБОТАЕТ</span>
        <ol><li><i>1</i><span>Опишите вашу задачу</span></li><li><i>2</i><span>Уточните важные параметры</span></li><li><i>3</i><span>Выберите подходящий вариант</span></li></ol>
      </div>
      <div className="cart-assurance"><ShieldCheck size={19} /><p>Корзина меняется<br /><strong>только с вашего согласия.</strong></p></div>
    </div>
  );
}
