(() => {
  const storageKey = 'roylyl.music.region-notice.shown.v1';
  try { if (localStorage.getItem(storageKey) === '1') return; } catch (_) {}

  const normalize = value => String(value || '').trim().toUpperCase();
  let pending = false;
  let shown = false;

  async function countryFrom(url, text = false) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3000);
    try {
      const response = await fetch(url, { signal: controller.signal, cache: 'no-store', mode: 'cors' });
      if (!response.ok) return '';
      const data = text ? await response.text() : await response.json();
      return normalize(text ? data : data?.country);
    } catch (_) {
      return '';
    } finally {
      clearTimeout(timer);
    }
  }

  async function detectCountry() {
    const first = await countryFrom('https://api.country.is/');
    if (/^[A-Z]{2}$/.test(first)) return first;
    const second = await countryFrom('https://ipapi.co/country/', true);
    return /^[A-Z]{2}$/.test(second) ? second : '';
  }

  function showWhenAvailable() {
    if (!pending || shown || document.hidden || document.querySelector('dialog[open]')) return;
    try { if (localStorage.getItem(storageKey) === '1') { pending = false; return; } } catch (_) {}

    const notice = document.createElement('dialog');
    notice.className = 'music-region-notice';
    notice.setAttribute('aria-labelledby', 'music-region-title');
    notice.setAttribute('aria-describedby', 'music-region-body');
    notice.innerHTML = '<h2 id="music-region-title">访问提示</h2><p id="music-region-body">音乐音频和歌词从GitHub加载。在中国大陆访问时，资源可能加载缓慢，请耐心等待。</p><button type="button" autofocus>我知道了</button>';
    document.body.appendChild(notice);
    const returnFocus = document.activeElement;
    notice.querySelector('button').addEventListener('click', () => notice.close());
    notice.addEventListener('close', () => {
      notice.remove();
      if (returnFocus instanceof HTMLElement && returnFocus.isConnected) returnFocus.focus({ preventScroll: true });
    }, { once: true });
    notice.showModal();
    notice.querySelector('button').focus({ preventScroll: true });
    pending = false;
    shown = true;
    try { localStorage.setItem(storageKey, '1'); } catch (_) {}
  }

  document.addEventListener('visibilitychange', showWhenAvailable);
  document.addEventListener('close', () => queueMicrotask(showWhenAvailable), true);
  const countryPromise = window.__roylylCountryPromise || detectCountry();
  Promise.resolve(countryPromise).then(country => {
    pending = normalize(country) === 'CN';
    showWhenAvailable();
  }).catch(() => {});
})();
