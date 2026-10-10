(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.OneStepPlatform = factory(root, root.OneStepNative || null, root.OneStepMobileConfig || {});
}(typeof window !== 'undefined' ? window : this, function (host, native, config) {
  'use strict';
  var isNative = Boolean(native && native.isNative);
  var cache = new Map();
  var writes = Promise.resolve();
  var ready;
  var active = true;
  var connected = host.navigator.onLine !== false;
  var apiReachable = true;
  var listeners = [];
  var resumeTimer;
  var lastResume = 0;
  var storageHealthy = true;
  var routePending = false;
  // Credentials live in separate private keys, never in the display snapshot.
  var durable = /^onestep-flight-(locale|offline-journey-v1|share-(session|pending|revocation-pending)-v1)$/;

  function emit(type) { listeners.forEach(function (fn) { fn(type); }); }
  function enqueue(operation) {
    var result = writes.then(operation);
    writes = result.catch(function () { storageHealthy = false; emit('storage-error'); });
    return result;
  }
  var storage = {
    getItem: function (key) { return isNative && durable.test(key) ? cache.get(key) || null : host.localStorage.getItem(key); },
    setItem: function (key, value) {
      if (!isNative || !durable.test(key)) return host.localStorage.setItem(key, value);
      value = String(value);
      cache.set(key, value);
      // A failed set never removes the previous durable value.
      enqueue(function () { return native.Preferences.set({ key: key, value: value }); }).catch(function () {});
    },
    removeItem: function (key) {
      if (!isNative || !durable.test(key)) return host.localStorage.removeItem(key);
      cache.delete(key);
      enqueue(function () {
        // If writing the revocation journal failed, retain the durable credential pair.
        // On the next launch an ended journey can retry revocation from this private key.
        if (key === 'onestep-flight-share-session-v1' && !storageHealthy) return;
        return native.Preferences.remove({ key: key });
      }).catch(function () {});
    }
  };
  function resume() {
    if (!active || !connected) return;
    clearTimeout(resumeTimer);
    resumeTimer = setTimeout(function () {
      if (Date.now() - lastResume < 1500) return;
      lastResume = Date.now();
      emit('resume');
    }, 250);
  }
  function route(url) {
    if (/^onestepflight:\/\/journey\/?$/.test(url || '')) { routePending = true; emit('route'); }
  }
  function init() {
    if (ready) return ready;
    ready = (async function () {
      if (!isNative) return;
      var keys = ['onestep-flight-locale', 'onestep-flight-offline-journey-v1',
        'onestep-flight-share-session-v1', 'onestep-flight-share-pending-v1',
        'onestep-flight-share-revocation-pending-v1'];
      // Do not interpret a failed native read as an empty journey, or revoke its link.
      await Promise.all(keys.map(async function (key) {
        var result = await native.Preferences.get({ key: key });
        var value = result.value;
        if (value == null) {
          try { value = host.localStorage.getItem(key); } catch (_) { value = null; }
          if (value != null) await native.Preferences.set({ key: key, value: value });
        }
        if (value != null) cache.set(key, value);
        try { host.localStorage.removeItem(key); } catch (_) { /* Native storage is authoritative. */ }
      }));
      await native.App.addListener('appStateChange', function (state) {
        if (active === state.isActive) return;
        active = state.isActive;
        if (active) resume(); else { clearTimeout(resumeTimer); emit('pause'); }
      });
      await native.Network.addListener('networkStatusChange', function (state) {
        if (connected === state.connected) return;
        connected = state.connected;
        if (connected) resume(); else emit('offline');
      });
      connected = (await native.Network.getStatus()).connected;
      active = (await native.App.getState()).isActive;
      await native.App.addListener('appUrlOpen', function (event) { route(event.url); });
      var launch = await native.App.getLaunchUrl();
      if (launch) route(launch.url);
      await cleanShareFiles();
    }());
    return ready;
  }
  function apiUrl(path) {
    if (!/^\/api\//.test(path)) throw new Error('Expected a relative API path');
    return config.API_BASE_URL ? new URL(path, config.API_BASE_URL).href : path;
  }
  function publicShareUrl(token, supplied) {
    if (!/^[A-Za-z0-9_-]+$/.test(token || '')) return '';
    var base = config.PUBLIC_WEB_BASE_URL || host.location.origin;
    var canonical = new URL('/share/' + encodeURIComponent(token), base);
    if (isNative && canonical.protocol !== 'https:') throw new Error('A public HTTPS share URL is required');
    if (supplied) {
      try {
        var proposed = new URL(supplied, base);
        // Only accept the expected public origin/path; discard query strings and fragments.
        if (proposed.origin === canonical.origin && proposed.pathname === canonical.pathname) return canonical.href;
      } catch (_) { /* The view token still defines a valid canonical link. */ }
    }
    return canonical.href;
  }
  async function apiFetch(path, options) {
    var settings = Object.assign({}, options || {});
    var controller = new AbortController();
    var callerSignal = settings.signal;
    var abort = function () { controller.abort(); };
    if (callerSignal) {
      if (callerSignal.aborted) abort();
      else callerSignal.addEventListener('abort', abort, { once: true });
    }
    settings.signal = controller.signal;
    settings.headers = Object.assign({ Accept: 'application/json' }, settings.headers || {});
    var timedOut = false;
    var timer = setTimeout(function () { timedOut = true; abort(); }, 20000);
    try {
      var response = await host.fetch(apiUrl(path), settings);
      // Read within the deadline too; return a Response so JSON and DELETE retain their semantics.
      var body = await response.arrayBuffer();
      if (response.status >= 500) { apiReachable = false; emit('api-offline'); }
      else if (!apiReachable) { apiReachable = true; emit('api-online'); }
      return new Response(response.status === 204 || response.status === 205 ? null : body,
        { status: response.status, statusText: response.statusText, headers: response.headers });
    } catch (error) {
      if (!callerSignal || !callerSignal.aborted) { apiReachable = false; emit('api-offline'); }
      if (timedOut) { error.code = 'CLIENT_FLIGHT_TIMEOUT'; }
      throw error;
    } finally {
      clearTimeout(timer);
      if (callerSignal) callerSignal.removeEventListener('abort', abort);
    }
  }
  function cancelled(error) {
    return Boolean(error && (error.name === 'AbortError' || /cancel/i.test(error.message || '')));
  }
  async function shareLink(data) {
    try { return await native.Share.share(data); }
    catch (error) { if (!cancelled(error)) throw error; return { cancelled: true }; }
  }
  async function cleanShareFiles() {
    try {
      var entries = await native.Filesystem.readdir({ path: 'onestep-share', directory: 'CACHE' });
      await Promise.all(entries.files.map(function (file) {
        return native.Filesystem.deleteFile({ path: 'onestep-share/' + file.name, directory: 'CACHE' });
      }));
    } catch (_) { /* First launch has no share directory. */ }
  }
  var sharingImage = false;
  async function sharePng(blob) {
    if (sharingImage) return;
    sharingImage = true;
    var path = 'onestep-share/card-' + Date.now() + '.png';
    try {
      var bytes = new Uint8Array(await blob.arrayBuffer());
      var binary = '';
      for (var i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
      var file = await native.Filesystem.writeFile({ path: path, data: host.btoa(binary), directory: 'CACHE', recursive: true });
      return await shareLink({ title: 'OneStep Flight', files: [file.uri] });
    } finally {
      await native.Filesystem.deleteFile({ path: path, directory: 'CACHE' }).catch(function () {});
      sharingImage = false;
    }
  }
  function externalUrl(value) {
    try {
      var url = new URL(value);
      if (url.username || url.password) return null;
      if (url.protocol === 'https:') return url.href;
      if (/^tel:\+?[0-9 ()-]{5,25}$/.test(value)) return value;
    } catch (_) { /* Reject unsupported schemes and relative URLs. */ }
    return null;
  }
  return {
    isNative: isNative, storage: storage, init: init, apiUrl: apiUrl, apiFetch: apiFetch,
    publicShareUrl: publicShareUrl, shareLink: shareLink, sharePng: sharePng,
    cancelled: cancelled, externalUrl: externalUrl,
    openExternal: function (url) { var allowed = externalUrl(url); return allowed ? native.FlightNative.openExternal({ url: allowed }) : Promise.reject(new Error('Unsupported URL')); },
    on: function (fn) { listeners.push(fn); },
    flush: function () { return writes; },
    storageHealthy: function () { return storageHealthy; },
    isActive: function () { return active; },
    isOnline: function () { return (!isNative || connected) && apiReachable && host.navigator.onLine !== false; },
    resume: resume,
    takeRoute: function () { var pending = routePending; routePending = false; return pending; },
    live: native && native.FlightLiveActivity
  };
}));
