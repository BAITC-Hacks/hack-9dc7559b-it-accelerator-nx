/**
 * Versioned host-page loader. Creates a launcher + iframe on the widget origin.
 * Session lives in the iframe's first-party storage — no tokens in URL or parent messages.
 */
(function () {
  'use strict';
  if (window.__hackalemWidgetLoaded) return;
  window.__hackalemWidgetLoaded = true;

  var script = document.currentScript;
  if (!script || !script.src) return;

  var widgetOrigin;
  try {
    widgetOrigin = new URL(script.src).origin;
  } catch (err) {
    return;
  }

  var OPEN = false;
  var panel = document.createElement('div');
  panel.id = 'hackalem-widget-root';
  panel.setAttribute('data-hackalem', 'launcher');
  panel.style.cssText = [
    'all: initial',
    'position: fixed',
    'z-index: 2147483000',
    'right: 16px',
    'bottom: 16px',
    'font-family: system-ui, sans-serif',
  ].join(';');

  var frameWrap = document.createElement('div');
  frameWrap.style.cssText = [
    'display: none',
    'position: fixed',
    'right: 16px',
    'bottom: 76px',
    'width: min(420px, calc(100vw - 24px))',
    'height: min(720px, calc(100dvh - 100px))',
    'border-radius: 16px',
    'overflow: hidden',
    'box-shadow: 0 18px 50px rgba(20, 40, 28, 0.28)',
    'border: 1px solid #d7e0d0',
    'background: #fcfcf9',
  ].join(';');

  var iframe = document.createElement('iframe');
  iframe.title = 'Онлайн-консультант EKT';
  iframe.src = widgetOrigin + '/widget';
  iframe.allow = 'clipboard-write';
  iframe.style.cssText = 'width:100%;height:100%;border:0;display:block;background:#fcfcf9';
  frameWrap.appendChild(iframe);

  var button = document.createElement('button');
  button.type = 'button';
  button.setAttribute('aria-label', 'Открыть консультанта');
  button.setAttribute('aria-expanded', 'false');
  button.textContent = 'Чат';
  button.style.cssText = [
    'all: unset',
    'box-sizing: border-box',
    'display: inline-flex',
    'align-items: center',
    'justify-content: center',
    'min-width: 56px',
    'height: 56px',
    'padding: 0 18px',
    'border-radius: 999px',
    'background: #203a2f',
    'color: #d1ed95',
    'font: 600 14px/1 system-ui, sans-serif',
    'cursor: pointer',
    'box-shadow: 0 10px 28px rgba(20, 40, 28, 0.28)',
  ].join(';');

  function setOpen(next) {
    OPEN = next;
    frameWrap.style.display = next ? 'block' : 'none';
    button.setAttribute('aria-expanded', next ? 'true' : 'false');
    button.setAttribute('aria-label', next ? 'Скрыть консультанта' : 'Открыть консультанта');
    button.textContent = next ? 'Скрыть' : 'Чат';
  }

  button.addEventListener('click', function () {
    setOpen(!OPEN);
  });

  window.addEventListener('message', function (event) {
    if (event.origin !== widgetOrigin) return;
    if (event.source !== iframe.contentWindow) return;
    var data = event.data;
    if (!data || typeof data !== 'object') return;
    if (data.token || data.cartId || data.principal || data.authorization) return;

    if (data.type === 'hackalem:ready' && data.audience === 'host') {
      iframe.contentWindow.postMessage(
        { type: 'hackalem:hello', audience: 'widget', nonce: String(Date.now()) },
        widgetOrigin,
      );
      return;
    }
    if (data.type === 'hackalem:close' && data.audience === 'host') {
      setOpen(false);
      return;
    }
    if (data.type === 'hackalem:open-cart' && data.audience === 'host' && typeof data.url === 'string') {
      try {
        var cartUrl = new URL(data.url);
        if (cartUrl.origin !== widgetOrigin) return;
        window.open(cartUrl.href, '_blank', 'noopener,noreferrer');
      } catch (err) {
        /* ignore forged URLs */
      }
    }
  });

  panel.appendChild(frameWrap);
  panel.appendChild(button);
  document.documentElement.appendChild(panel);
})();
