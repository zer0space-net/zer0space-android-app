/* zer0space Android app: the page side of the native bridge.
 *
 * Injected by the app at document start, and ONLY into frames whose origin is
 * exactly the zer0space site (WebViewCompat.addDocumentStartJavaScript with
 * an origin rule). The native object it talks to, `zs_native`, is bound to the
 * same origin rule, so neither exists in a hoster iframe or on any other site.
 *
 * It defines three things, all in shapes the existing site code already uses,
 * so the site needs no app-specific branches to benefit:
 *
 *  - window.CrimsonExtension: the exact companion-extension API crimson-sources
 *    probes for (types.ts ExtensionBridge). With it present the Crimson engine
 *    runs every source on the phone (its "E3" tier), from the phone's own IP.
 *  - a wrapper around navigator.mediaSession: zer0space Music already reports
 *    what plays and which buttons it handles. That is forwarded to a native
 *    media session, so lock screen, notification and headset buttons work and
 *    the music survives the screen going off.
 *  - window.zer0spaceApp: version info and the in-app updater, used by the
 *    dashboard's Settings → App tab (static/js/app-download.js).
 */
(function () {
  'use strict';
  if (window.__zsAppBridge) return;
  var port = window.zs_native;
  if (!port || typeof port.postMessage !== 'function') return;
  Object.defineProperty(window, '__zsAppBridge', { value: true });

  var VERSION = '__ZS_VERSION__';
  var seq = 0;
  var pending = {};

  function call(method, params, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      var timer = setTimeout(function () {
        if (!pending[id]) return;
        delete pending[id];
        reject(new Error(method + ' timed out'));
      }, timeoutMs || 60000);
      pending[id] = { resolve: resolve, reject: reject, timer: timer };
      try {
        port.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
      } catch (e) {
        clearTimeout(timer);
        delete pending[id];
        reject(e);
      }
    });
  }

  function notify(method, params) {
    try { port.postMessage(JSON.stringify({ id: 0, method: method, params: params || {} })); } catch (e) { /* page unloading */ }
  }

  port.onmessage = function (ev) {
    var msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }
    if (msg && msg.id) {
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      clearTimeout(p.timer);
      if (msg.ok) p.resolve(msg.result); else p.reject(new Error(msg.error || 'native error'));
      return;
    }
    if (!msg || !msg.event) return;
    if (msg.event === 'media.action') onMediaAction(msg.data || {});
    else if (msg.event === 'update') {
      window.dispatchEvent(new CustomEvent('zer0space-app:update', { detail: msg.data || {} }));
    }
  };

  /* --- Crimson: the companion-extension contract ---------------------------- */

  function clampMs(v, def, min, max) {
    var n = Number(v);
    if (!isFinite(n) || n <= 0) n = def;
    return Math.min(Math.max(n, min), max);
  }

  var ext = Object.freeze({
    available: true,
    version: 'app-' + VERSION,
    hello: function () { return call('hello', {}, 10000); },
    status: function () { return call('status', {}, 10000).then(Boolean); },
    fetch: function (url, opts) {
      return call('fetch', { url: String(url), opts: opts || {} }, 60000);
    },
    installMediaRules: function (rules, opts) {
      return call('installMediaRules', { rules: rules || [], replace: !!(opts && opts.replace) }, 10000);
    },
    clearMediaRules: function (ids) {
      return call('clearMediaRules', { ids: ids || null }, 10000).then(Boolean);
    },
    // One hidden page runs at a time natively, so a call may queue behind
    // others; the JS timeout allows for that wait.
    resolveInPage: function (url, opts) {
      var o = opts || {};
      var t = clampMs(o.timeoutMs, 30000, 3000, 90000);
      return call('resolveInPage', {
        url: String(url),
        timeoutMs: t,
        mustInclude: Array.isArray(o.mustInclude) ? o.mustInclude.map(String) : [],
        frame: !!o.frame
      }, t * 4 + 10000);
    },
    // App-only: open a Turnstile-gated link (s.to /r?t=) in a real browser and
    // return where it lands. crimson-sources feature-detects it.
    resolveRedirect: function (url, opts) {
      var t = clampMs(opts && opts.timeoutMs, 25000, 3000, 60000);
      return call('resolveRedirect', { url: String(url), timeoutMs: t }, t * 6 + 10000);
    }
  });
  Object.defineProperty(window, 'CrimsonExtension', { value: ext, writable: false, configurable: false });

  /* --- The app itself --------------------------------------------------------- */

  var app = Object.freeze({
    platform: 'android',
    version: VERSION,
    info: function () { return call('app.info', {}, 10000); },
    installUpdate: function (o) {
      o = o || {};
      return call('app.installUpdate', {
        url: String(o.url || ''),
        sha256: String(o.sha256 || ''),
        versionCode: Number(o.versionCode) || 0
      }, 30 * 60000);
    }
  });
  Object.defineProperty(window, 'zer0spaceApp', { value: app, writable: false, configurable: false });

  /* --- Media Session forwarding ----------------------------------------------- */

  var handlers = {};
  var media = { title: '', artist: '', album: '', artwork: '', state: 'none', duration: 0, position: 0, rate: 1 };
  var lastSent = '';

  function send() {
    var actions = Object.keys(handlers).filter(function (k) { return typeof handlers[k] === 'function'; });
    var payload = {
      title: media.title, artist: media.artist, album: media.album, artwork: media.artwork,
      state: media.state, duration: media.duration, position: media.position, rate: media.rate,
      actions: actions
    };
    var json = JSON.stringify(payload);
    if (json === lastSent) return;
    lastSent = json;
    notify('media.update', payload);
  }

  function pickArtwork(list) {
    if (!list || !list.length) return '';
    var best = null, bestSize = -1;
    for (var i = 0; i < list.length; i++) {
      var a = list[i] || {};
      var size = parseInt(String(a.sizes || '0').split('x')[0], 10) || 0;
      if (size > bestSize) { best = a; bestSize = size; }
    }
    try {
      var u = new URL(String(best && best.src || ''), location.href);
      return u.protocol === 'https:' ? u.href : '';
    } catch (e) { return ''; }
  }

  function readMetadata(m) {
    media.title = m && m.title ? String(m.title) : '';
    media.artist = m && m.artist ? String(m.artist) : '';
    media.album = m && m.album ? String(m.album) : '';
    media.artwork = pickArtwork(m && m.artwork);
  }

  (function wrapMediaSession() {
    var ms = navigator.mediaSession;
    if (!ms) {
      ms = { setActionHandler: null, setPositionState: null };
      try { Object.defineProperty(navigator, 'mediaSession', { value: ms, configurable: true }); } catch (e) { return; }
    }
    if (typeof window.MediaMetadata !== 'function') {
      window.MediaMetadata = function MediaMetadata(init) {
        init = init || {};
        this.title = init.title || '';
        this.artist = init.artist || '';
        this.album = init.album || '';
        this.artwork = init.artwork || [];
      };
    }

    var proto = Object.getPrototypeOf(ms);
    function accessor(name) {
      var d = proto && Object.getOwnPropertyDescriptor(proto, name);
      return d && typeof d.set === 'function' ? d : null;
    }
    var metaDesc = accessor('metadata');
    var stateDesc = accessor('playbackState');
    var currentMeta = null;
    var currentState = 'none';

    Object.defineProperty(ms, 'metadata', {
      configurable: true,
      get: function () { return currentMeta; },
      set: function (v) {
        currentMeta = v;
        if (metaDesc) { try { metaDesc.set.call(ms, v); } catch (e) { /* ignore */ } }
        readMetadata(v);
        send();
      }
    });
    Object.defineProperty(ms, 'playbackState', {
      configurable: true,
      get: function () { return currentState; },
      set: function (v) {
        currentState = v === 'playing' || v === 'paused' ? v : 'none';
        if (stateDesc) { try { stateDesc.set.call(ms, v); } catch (e) { /* ignore */ } }
        media.state = currentState;
        send();
      }
    });

    var origAction = typeof ms.setActionHandler === 'function' ? ms.setActionHandler.bind(ms) : null;
    ms.setActionHandler = function (action, fn) {
      handlers[action] = typeof fn === 'function' ? fn : null;
      send();
      // The WebView's own implementation may reject actions it does not know;
      // the native session handles them, so that must not reach the page.
      if (origAction) { try { origAction(action, fn); } catch (e) { /* ignore */ } }
    };

    var origPos = typeof ms.setPositionState === 'function' ? ms.setPositionState.bind(ms) : null;
    ms.setPositionState = function (s) {
      s = s || {};
      if (isFinite(s.duration)) media.duration = Number(s.duration);
      if (isFinite(s.position)) media.position = Number(s.position);
      if (isFinite(s.playbackRate) && s.playbackRate > 0) media.rate = Number(s.playbackRate);
      send();
      if (origPos) { try { origPos(s); } catch (e) { /* ignore */ } }
    };
  })();

  // Pages that never touch the Media Session API still get a notification for
  // an <audio> element that plays, with play/pause working.
  function onMediaEvent(ev) {
    var el = ev.target;
    if (!el || el.tagName !== 'AUDIO') return;
    if (ev.type === 'playing') media.state = 'playing';
    // A track that ran to its end fires "pause" too. That is the gap before the
    // next track, not the user pausing, so the state stays as it was.
    else if (ev.type === 'pause' && !el.ended) media.state = 'paused';
    if (isFinite(el.duration)) media.duration = el.duration;
    media.position = el.currentTime || 0;
    send();
  }
  document.addEventListener('playing', onMediaEvent, true);
  document.addEventListener('pause', onMediaEvent, true);

  function firstMedia() {
    return document.querySelector('audio') || document.querySelector('video');
  }

  function onMediaAction(d) {
    var action = String(d.action || '');
    var fn = handlers[action];
    var details = { action: action };
    if (typeof d.seekTime === 'number') details.seekTime = d.seekTime;
    if (fn) {
      try { fn(details); } catch (e) { /* page handler failed */ }
      return;
    }
    var el = firstMedia();
    if (!el) return;
    if (action === 'play') el.play().catch(function () {});
    else if (action === 'pause') el.pause();
    else if (action === 'seekto' && typeof d.seekTime === 'number') el.currentTime = d.seekTime;
  }

  // Leaving the page ends its audio; take the notification down with it.
  window.addEventListener('pagehide', function () {
    media.state = 'none';
    lastSent = '';
    send();
  });

  window.dispatchEvent(new Event('crimson-extension-ready'));
})();
