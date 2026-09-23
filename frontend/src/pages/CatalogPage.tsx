import { Link } from 'react-router-dom';
import { LiveCatalogPanel } from '../components/catalog/LiveCatalogPanel';
import { CommercePanel } from '../components/catalog/CommercePanel';
import { useCommerce } from '../hooks/useCommerce';
export default function CatalogPage() {
  const { view } = useCommerce();
  return <main className="commerce-page"><header><Link to="/">← Вернуться в чат</Link><Link to="/cart">Корзина →</Link></header><h1>Каталог</h1><p>Проверьте поиск, наличие, документы и аналоги. Добавление происходит только после подтверждения предложения.</p>{view.mode === 'demo' ? <CommercePanel conversation="demo-catalog" /> : view.mode === 'loading' ? <p role="status">Открываем каталог…</p> : <LiveCatalogPanel />}</main>;
}
