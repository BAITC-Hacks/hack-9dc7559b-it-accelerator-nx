import { useEffect, useState } from 'react';
import {
  acceptParentMessage,
  cartPageUrl,
  parseAllowedOrigins,
  type EmbedOutgoing,
} from './bridge';

function allowedOrigins() {
  return parseAllowedOrigins(import.meta.env.VITE_EMBED_ALLOWED_ORIGINS);
}

function postToParent(message: EmbedOutgoing) {
  if (window.parent === window) return;
  window.parent.postMessage(message, '*');
}

/**
 * Handshake with the host page. Parent may confirm itself via hello,
 * but must never assign token/owner/cart — those keys are rejected.
 */
export function useEmbedBridge(enabled: boolean) {
  const [parentTrusted, setParentTrusted] = useState(false);

  useEffect(() => {
    if (!enabled || window.parent === window) return;
    const allowed = allowedOrigins();
    const onMessage = (event: MessageEvent) => {
      const incoming = acceptParentMessage(event, allowed, window.parent);
      if (!incoming) return;
      if (incoming.type === 'hackalem:hello') setParentTrusted(true);
      if (incoming.type === 'hackalem:close') {
        postToParent({ type: 'hackalem:close', audience: 'host' });
      }
    };
    window.addEventListener('message', onMessage);
    postToParent({ type: 'hackalem:ready', audience: 'host' });
    return () => window.removeEventListener('message', onMessage);
  }, [enabled]);

  return {
    parentTrusted,
    openCart: () => {
      const url = cartPageUrl();
      if (window.parent !== window) {
        postToParent({ type: 'hackalem:open-cart', audience: 'host', url });
        return;
      }
      window.open(url, '_blank', 'noopener,noreferrer');
    },
    requestClose: () => postToParent({ type: 'hackalem:close', audience: 'host' }),
  };
}
