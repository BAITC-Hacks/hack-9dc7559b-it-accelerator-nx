import { Route, Routes } from 'react-router-dom';
import HomePage from '@/pages/HomePage';
import ChatPage from '@/pages/ChatPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/widget" element={<ChatPage />} />
      <Route path="/status" element={<HomePage />} />
    </Routes>
  );
}
