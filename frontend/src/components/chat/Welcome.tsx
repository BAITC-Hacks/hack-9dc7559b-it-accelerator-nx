import { ArrowUpRight, Box, Lightbulb, Search, Truck, Zap } from 'lucide-react';

const starters = [
  { icon: Zap, title: 'Подобрать оборудование', text: 'Нужен автомат Legrand, 40 ампер, 3 штуки', hint: 'Расскажите о вашей задаче' },
  { icon: Search, title: 'Найти по артикулу', text: 'Найти товар по артикулу 027004', hint: 'Перейдём сразу к конкретике' },
  { icon: Lightbulb, title: 'Выбрать освещение', text: 'Нужны светильники для офиса, 20 штук', hint: 'Для офиса, дома или производства' },
  { icon: Truck, title: 'Узнать об условиях', text: 'Какие условия доставки в Алматы?', hint: 'Доставка, оплата и заказ' },
];

export function Welcome({ enabled, onChoose }: { enabled: boolean; onChoose: (text: string) => void }) {
  return (
    <div className="chat-welcome">
      <div className="welcome-mark"><Box size={29} strokeWidth={1.6} /><span><Zap size={12} fill="currentColor" /></span></div>
      <p className="eyebrow welcome-eyebrow">ОНЛАЙН-КОНСУЛЬТАНТ EKT</p>
      <h1>Подберём то,<br /><span>что нужно.</span></h1>
      <p className="welcome-description">Опишите задачу или укажите артикул.<br />Помогу разобраться в параметрах и продолжить выбор.</p>
      <div className="starter-grid">
        {starters.map(({ icon: Icon, title, text, hint }) => (
          <button key={title} className="starter-card" disabled={!enabled} onClick={() => onChoose(text)}>
            <Icon size={20} strokeWidth={1.6} />
            <span><strong>{title}</strong><small>{hint}</small></span>
            <ArrowUpRight size={16} className="starter-arrow" />
          </button>
        ))}
      </div>
      <p className="welcome-example">Можно своими словами: «Нужны автоматы для небольшой мастерской»</p>
    </div>
  );
}
