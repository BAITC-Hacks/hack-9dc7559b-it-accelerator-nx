import { NavLink } from 'react-router-dom';

export function FeatureNav() {
  return <nav className="feature-nav" aria-label="Разделы приложения">
    <NavLink to="/" end>Диалоги</NavLink><NavLink to="/catalog">Каталог</NavLink><NavLink to="/attachments">Файлы</NavLink>
    <NavLink to="/knowledge">Условия покупки</NavLink><NavLink to="/cart">Корзина</NavLink><NavLink to="/admin">Управление</NavLink>
    <NavLink to="/session">Сессия</NavLink><NavLink to="/status">Связь с сервером</NavLink>
  </nav>;
}
