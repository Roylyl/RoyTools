(() => {
  const records = new Map();
  const labels = () => document.documentElement.lang === 'en'
    ? { retry: 'Retry image', failed: 'Image unavailable · tap to retry' }
    : document.documentElement.lang === 'zh-TW'
      ? { retry: '重新載入圖片', failed: '圖片未載入 · 點擊重試' }
      : { retry: '重新加载图片', failed: '图片未加载 · 点击重试' };
  const visibility = new IntersectionObserver(entries => entries.forEach(({ target, isIntersecting }) => {
    records.get(target)?.overlay.toggleAttribute('data-visible', isIntersecting && !document.hidden);
  }));
  function setup(img) {
    if (records.has(img)) return;
    const host = img.parentElement;
    if (!host) return;
    const overlay = document.createElement('span');
    overlay.className = 'image-placeholder';
    overlay.hidden = true;
    overlay.setAttribute('aria-hidden', 'true');
    if (getComputedStyle(host).position === 'static') host.classList.add('image-loading-host');
    host.append(overlay);
    const interactive = img.closest('button,a');
    let generation = 0;
    const place = () => {
      const a = img.getBoundingClientRect(), b = host.getBoundingClientRect();
      const sx = b.width / (host.offsetWidth || b.width || 1), sy = b.height / (host.offsetHeight || b.height || 1);
      Object.assign(overlay.style, { left: `${(a.left-b.left)/sx-host.clientLeft+host.scrollLeft}px`, top: `${(a.top-b.top)/sy-host.clientTop+host.scrollTop}px`, width: `${a.width/sx}px`, height: `${a.height/sy}px` });
    };
    const retry = () => {
      start();
      // Reset selection as well as src so srcset images retry the chosen source.
      const src = img.getAttribute('src'), srcset = img.getAttribute('srcset');
      img.removeAttribute('srcset'); img.removeAttribute('src');
      if (srcset) img.setAttribute('srcset', srcset);
      if (src) img.setAttribute('src', src);
    };
    function failed() {
      generation++;
      img.dataset.imageState = 'error';
      img.removeAttribute('aria-busy');
      overlay.hidden = false; overlay.setAttribute('data-error', '');
      overlay.replaceChildren();
      const text = document.createElement(interactive ? 'span' : 'button');
      text.className = 'image-placeholder-message';
      text.textContent = interactive ? labels().failed : labels().retry;
      if (!interactive) { text.type = 'button'; text.setAttribute('aria-label', `${labels().retry} · ${img.alt}`); text.addEventListener('click', retry); overlay.removeAttribute('aria-hidden'); }
      overlay.append(text); place();
    }
    async function loaded() {
      const version = ++generation;
      if (!img.naturalWidth) { failed(); return; }
      try { await img.decode(); } catch (_) { /* A load event already confirms a usable image. */ }
      if (version !== generation) return;
      img.dataset.imageState = 'ready'; img.removeAttribute('aria-busy'); overlay.hidden = true;
      if (!matchMedia('(prefers-reduced-motion: reduce)').matches) img.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 350, easing: 'ease-out' });
    }
    function start() {
      generation++;
      if (!img.getAttribute('src') && !img.getAttribute('srcset')) { overlay.hidden = true; return; }
      if (img.complete && img.naturalWidth) { img.dataset.imageState = 'ready'; img.removeAttribute('aria-busy'); overlay.hidden = true; return; }
      img.dataset.imageState = 'loading'; img.setAttribute('aria-busy', 'true');
      overlay.hidden = false; overlay.removeAttribute('data-error'); overlay.setAttribute('aria-hidden', 'true');
      const orbit = document.createElement('span'); orbit.className = 'image-placeholder-orbit'; overlay.replaceChildren(orbit); place();
      if (img.complete && !img.naturalWidth) failed();
    }
    if (interactive) interactive.addEventListener('click', event => {
      if (img.dataset.imageState !== 'error') return;
      event.preventDefault(); event.stopImmediatePropagation(); retry();
    }, true);
    records.set(img, { overlay, place, start, failed });
    img.addEventListener('load', loaded); img.addEventListener('error', failed);
    new ResizeObserver(place).observe(img);
    visibility.observe(img); start();
  }
  document.querySelectorAll('img').forEach(setup);
  new MutationObserver(changes => changes.forEach(change => {
    if (change.type === 'attributes') records.get(change.target)?.start();
    else change.addedNodes.forEach(node => {
      if (node.nodeType !== 1) return;
      if (node.matches('img')) setup(node);
      node.querySelectorAll('img').forEach(setup);
    });
  })).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['src', 'srcset'] });
  window.addEventListener('resize', () => records.forEach(record => record.place()));
  window.addEventListener('site-language-change', () => records.forEach((record, img) => { if (img.dataset.imageState === 'error') record.failed(); }));
  document.addEventListener('visibilitychange', () => records.forEach((record, img) => {
    const r = img.getBoundingClientRect(); record.overlay.toggleAttribute('data-visible', !document.hidden && r.bottom > 0 && r.top < innerHeight);
  }));
})();
