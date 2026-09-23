import { Link } from 'react-router-dom';
import { serviceError } from '../lib/knowledge';
import './services.css';

export function ServiceError({ error }: { error: unknown }) {
  if (!error) return null;
  return <div className="service-error" role="alert"><p>{serviceError(error)}</p><Link to="/session">Управление сессией</Link></div>;
}

export function JsonDetails({ value, label = 'Полный ответ сервера' }: { value: unknown; label?: string }) {
  return <details className="service-details"><summary>{label}</summary><pre>{JSON.stringify(value, null, 2)}</pre></details>;
}
