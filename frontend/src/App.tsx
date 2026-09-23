import { Route, Routes } from 'react-router-dom';
import HomePage from '@/pages/HomePage';
import ChatPage from '@/pages/ChatPage';
import CartPage from '@/pages/CartPage';
import SourcePage from '@/pages/SourcePage';
import { CommerceProvider } from '@/components/cart/CommerceProvider';
import { AttachmentProvider } from '@/components/attachments/AttachmentProvider';
import '@/components/cart/commerce.css';

export default function App() {
  return (
    <CommerceProvider><AttachmentProvider><Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/widget" element={<ChatPage />} />
      <Route path="/status" element={<HomePage />} />
      <Route path="/cart" element={<CartPage />} />
      <Route path="/sources/:key" element={<SourcePage />} />
    </Routes></AttachmentProvider></CommerceProvider>
  );
}
