(function(root) {
  'use strict';

  // A separate background layer leaves the played portion free to stay white.
  function bufferGradient(buffered, duration) {
    if (!Number.isFinite(duration) || duration <= 0) return 'none';
    const ranges = [];
    for (let i = 0; i < (buffered?.length || 0); i++) {
      try {
        const start = Math.max(0, Math.min(duration, buffered.start(i)));
        const end = Math.max(0, Math.min(duration, buffered.end(i)));
        if (Number.isFinite(start) && Number.isFinite(end) && end > start) ranges.push([start, end]);
      } catch { /* A source replacement can invalidate a TimeRanges snapshot. */ }
    }
    if (!ranges.length) return 'none';
    ranges.sort((a, b) => a[0] - b[0]);
    const merged = [];
    for (const range of ranges) {
      const last = merged[merged.length - 1];
      if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
      else merged.push(range);
    }
    const percent = time => Number((time / duration * 100).toFixed(5)) + '%';
    const stops = [];
    let position = 0;
    for (const [start, end] of merged) {
      if (start > position) stops.push(`transparent ${percent(position)} ${percent(start)}`);
      stops.push(`var(--seek-buffered,#ffffff66) ${percent(start)} ${percent(end)}`);
      position = end;
    }
    if (position < duration) stops.push(`transparent ${percent(position)} 100%`);
    return `linear-gradient(to right,${stops.join(',')})`;
  }

  /**
   * Begin once for a new playback request; an automatic retry keeps that ticket.
   * Cancel on pause, interruption or source changes. A retry callback receives an
   * AbortSignal and must recheck isCurrent(ticket) after any awaited work before
   * changing playback. This complements the player's existing request token.
   */
  function createRetryController(options = {}) {
    const schedule = options.setTimeout || root.setTimeout.bind(root);
    const unschedule = options.clearTimeout || root.clearTimeout.bind(root);
    const isOnline = options.isOnline || (() => root.navigator?.onLine !== false);
    const delayMs = Number.isFinite(options.delayMs) ? Math.max(0, options.delayMs) : 350;
    let serial = 0;
    let active = null;

    function cancel() {
      if (!active) return;
      const previous = active;
      active = null;
      if (previous.timer !== null) unschedule(previous.timer);
      previous.abort.abort();
    }

    function begin(trackId, source) {
      cancel();
      const abort = new root.AbortController();
      const ticket = Object.freeze({id: ++serial, trackId, source, signal: abort.signal});
      active = {ticket, abort, attempts: 0, timer: null};
      return ticket;
    }

    function isCurrent(ticket) {
      return !!active && active.ticket === ticket && !ticket.signal.aborted;
    }

    function retry(ticket, error, callback, {online = true} = {}) {
      if (!isCurrent(ticket) || active.attempts >= 1 || !online || !isOnline()) return false;
      if (error?.name === 'NotAllowedError' || error?.name === 'AbortError' || error?.code === 1) return false;
      if (typeof callback !== 'function') return false;
      const request = active;
      request.attempts++;
      request.timer = schedule(() => {
        request.timer = null;
        if (isCurrent(ticket) && isOnline()) callback(ticket);
      }, delayMs);
      return true;
    }

    return Object.freeze({begin, retry, cancel, isCurrent});
  }

  root.RoylylPlaybackFeedback = Object.freeze({bufferGradient, createRetryController});
})(globalThis);
