(() => {
  const records = new Map();
  const pendingPlacement = new Set();
  let placementFrame = null;
  function placePending() {
    placementFrame = null;
    const active = [...pendingPlacement].map(img => records.get(img)).filter(record => record && record.img.isConnected && !record.overlay.hidden && record.overlay.hasAttribute('data-visible'));
    pendingPlacement.clear();
    // Finish style reads before changing hosts, then measure every image before writing overlays.
    const staticHosts = new Set(active.map(record => record.host).filter(host => getComputedStyle(host).position === 'static'));
    staticHosts.forEach(host => host.classList.add('image-loading-host'));
    const positions = active.map(record => [record, record.measure()]);
    positions.forEach(([record, position]) => Object.assign(record.overlay.style, position));
  }
  function queuePlacement(img) {
    pendingPlacement.add(img);
    if (placementFrame === null) placementFrame = requestAnimationFrame(placePending);
  }
  const labels = () => document.documentElement.lang === 'en'
    ? { retry: 'Retry image', failed: 'Image unavailable · tap to retry' }
    : document.documentElement.lang === 'zh-TW'
      ? { retry: '重新載入圖片', failed: '圖片未載入 · 點擊重試' }
      : { retry: '重新加载图片', failed: '图片未加载 · 点击重试' };
  const visibility = new IntersectionObserver(entries => entries.forEach(({ target, isIntersecting }) => {
    const record=records.get(target),visible=isIntersecting && !document.hidden;
    record?.overlay.toggleAttribute('data-visible',visible);
    if(visible && record && !record.overlay.hidden)record.place();
  }));
  const sizing = new ResizeObserver(entries => entries.forEach(({target}) => {
    const record=records.get(target);
    if(record?.overlay.hasAttribute('data-visible') && !record.overlay.hidden)record.place();
  }));
  function release(img) {
    if (img.isConnected) return;
    const record = records.get(img);
    if (!record) return;
    records.delete(img);pendingPlacement.delete(img);
    visibility.unobserve(img);sizing.unobserve(img);record.dispose();
  }
  function setup(img) {
    if (!img.isConnected || records.has(img)) return;
    const host = img.parentElement;
    if (!host) return;
    const overlay = document.createElement('span');
    overlay.className = 'image-placeholder';
    overlay.hidden = true;
    overlay.setAttribute('aria-hidden', 'true');
    host.append(overlay);
    const interactive = img.closest('button,a');
    let generation = 0;
    const place = () => queuePlacement(img);
    const measure = () => {
      const a = img.getBoundingClientRect(), b = host.getBoundingClientRect();
      const sx = b.width / (host.offsetWidth || b.width || 1), sy = b.height / (host.offsetHeight || b.height || 1);
      return { left: `${(a.left-b.left)/sx-host.clientLeft+host.scrollLeft}px`, top: `${(a.top-b.top)/sy-host.clientTop+host.scrollTop}px`, width: `${a.width/sx}px`, height: `${a.height/sy}px` };
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
      img.dataset.coverReady='false';img.dataset.imageState = 'error';
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
      if (!img.hasAttribute('data-cover-path') && !img.dataset.coverBound && !matchMedia('(prefers-reduced-motion: reduce)').matches) img.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 350, easing: 'ease-out' });
    }
    function start() {
      generation++;
      if (!img.getAttribute('src') && !img.getAttribute('srcset')) { overlay.hidden = true; return; }
      if (img.dataset.coverReady === 'true' || img.complete && img.naturalWidth) { img.dataset.imageState = 'ready'; img.removeAttribute('aria-busy'); overlay.hidden = true; return; }
      img.dataset.imageState = 'loading'; img.setAttribute('aria-busy', 'true');
      overlay.hidden = false; overlay.removeAttribute('data-error'); overlay.setAttribute('aria-hidden', 'true');
      const orbit = document.createElement('span'); orbit.className = 'image-placeholder-orbit'; overlay.replaceChildren(orbit); place();
      if (img.complete && !img.naturalWidth) failed();
    }
    const retryClick = event => {
      if (img.dataset.imageState !== 'error') return;
      event.preventDefault(); event.stopImmediatePropagation(); retry();
    };
    if (interactive) interactive.addEventListener('click', retryClick, true);
    records.set(img, { img, host, overlay, place, measure, start, failed, dispose() {
      generation++;
      img.removeEventListener('load', loaded);img.removeEventListener('error', failed);
      interactive?.removeEventListener('click', retryClick, true);overlay.remove();
    } });
    img.addEventListener('load', loaded); img.addEventListener('error', failed);
    sizing.observe(img);
    visibility.observe(img); start();
  }
  document.querySelectorAll('img').forEach(setup);
  new MutationObserver(changes => {
    const restart = new Set();
    for (const change of changes) {
      if (change.type === 'attributes') { restart.add(change.target);continue; }
      for (const node of change.removedNodes) {
        if (node.nodeType !== 1) continue;
        if (node.matches('img')) release(node);
        node.querySelectorAll('img').forEach(release);
      }
      for (const node of change.addedNodes) {
        if (node.nodeType !== 1) continue;
        if (node.matches('img')) setup(node);
        node.querySelectorAll('img').forEach(setup);
      }
    }
    restart.forEach(img => records.get(img)?.start());
  }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['src', 'srcset'] });
  window.addEventListener('resize', () => records.forEach(record => record.place()));
  window.addEventListener('site-language-change', () => records.forEach((record, img) => { if (img.dataset.imageState === 'error') record.failed(); }));
  document.addEventListener('visibilitychange', () => records.forEach((record, img) => {
    const r = img.getBoundingClientRect(); record.overlay.toggleAttribute('data-visible', !document.hidden && r.bottom > 0 && r.top < innerHeight);
  }));
})();
