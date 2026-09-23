import ChatPage from './ChatPage';
import { useEmbedBridge } from '@/widget/useEmbedBridge';
import { useVisualViewport } from '@/widget/useVisualViewport';

/** Compact embed entry used inside the host-page iframe. */
export default function WidgetPage() {
  useVisualViewport(true);
  const bridge = useEmbedBridge(true);
  const framed = typeof window !== 'undefined' && window.parent !== window;
  return (
    <ChatPage
      embed
      onOpenCart={bridge.openCart}
      onRequestClose={framed ? bridge.requestClose : undefined}
      parentTrusted={bridge.parentTrusted}
    />
  );
}
