import { useEffect } from 'react';

/** Keeps composer visible when the mobile virtual keyboard resizes the visual viewport. */
export function useVisualViewport(enabled = true) {
  useEffect(() => {
    if (!enabled || typeof window === 'undefined') return;
    const root = document.documentElement;
    const sync = () => {
      const viewport = window.visualViewport;
      const height = viewport?.height ?? window.innerHeight;
      root.style.setProperty('--vvh', `${height}px`);
      root.style.setProperty('--vv-offset', `${viewport?.offsetTop ?? 0}px`);
    };
    sync();
    const viewport = window.visualViewport;
    viewport?.addEventListener('resize', sync);
    viewport?.addEventListener('scroll', sync);
    window.addEventListener('resize', sync);
    return () => {
      viewport?.removeEventListener('resize', sync);
      viewport?.removeEventListener('scroll', sync);
      window.removeEventListener('resize', sync);
    };
  }, [enabled]);
}
