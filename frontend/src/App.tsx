import { Route, Routes } from 'react-router-dom';
import HomePage from '@/pages/HomePage';
import ChatPage from '@/pages/ChatPage';
import WidgetPage from '@/pages/WidgetPage';
import CartPage from '@/pages/CartPage';
import SourcePage from '@/pages/SourcePage';
import { CommerceProvider } from '@/components/cart/CommerceProvider';
import { AttachmentProvider } from '@/components/attachments/AttachmentProvider';
import '@/components/cart/commerce.css';
import '@/components/live.css';
import { SessionBoundary } from '@/components/SessionBoundary';
import SessionPage from '@/pages/SessionPage';
import KnowledgePage from '@/pages/KnowledgePage';
import AdminPage from '@/pages/AdminPage';
import AttachmentsPage from '@/pages/AttachmentsPage';
import { FeatureNav } from '@/components/FeatureNav';
import CatalogPage from '@/pages/CatalogPage';

export default function App() {
  return (
    <SessionBoundary><CommerceProvider><AttachmentProvider><Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/widget" element={<WidgetPage />} />
      <Route path="/status" element={<><FeatureNav /><HomePage /></>} />
      <Route path="/cart" element={<><FeatureNav /><CartPage /></>} />
      <Route path="/sources/:key" element={<><FeatureNav /><SourcePage /></>} />
      <Route path="/session" element={<><FeatureNav /><SessionPage /></>} />
      <Route path="/knowledge" element={<><FeatureNav /><KnowledgePage /></>} />
      <Route path="/admin" element={<><FeatureNav /><AdminPage /></>} />
      <Route path="/attachments" element={<><FeatureNav /><AttachmentsPage /></>} />
      <Route path="/catalog" element={<><FeatureNav /><CatalogPage /></>} />
    </Routes></AttachmentProvider></CommerceProvider></SessionBoundary>
  );
}
