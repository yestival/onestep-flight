'use strict';

const CACHE_PREFIX = 'onestep-flight-shell-';
const CACHE_NAME = CACHE_PREFIX + 'v29';
const APP_SHELL = ['/', '/index.html', '/styles.css?v=29', '/locale.js?v=29', '/app.js?v=29',
  '/share.html', '/share.css?v=29', '/share.js?v=29'];
const APP_SHELL_CACHE_KEY = APP_SHELL.reduce((keys, path) => {
  keys[new URL(path, self.location.origin).pathname] = path;
  return keys;
}, {});

function fetchFresh(request) {
  return fetch(new Request(request, { cache: 'reload' }));
}

async function cacheFreshResponse(request, cacheKey) {
  const response = await fetchFresh(request);
  if (response && response.ok) {
    const cache = await caches.open(CACHE_NAME);
    await cache.put(cacheKey, response.clone());
  }
  return response;
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(() => Promise.all(APP_SHELL.map((path) => cacheFreshResponse(path, path))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names
        .filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
        .map((name) => caches.delete(name))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.toLowerCase().endsWith('.pdf')) return;

  if (request.mode === 'navigate') {
    const fallbackPath = url.pathname.startsWith('/share/') ? '/share.html' : '/index.html';
    event.respondWith(
      cacheFreshResponse(request, fallbackPath)
        .catch(() => caches.match(fallbackPath)
          .then((cached) => cached || caches.match('/')))
    );
    return;
  }

  const cacheKey = APP_SHELL_CACHE_KEY[url.pathname];
  if (!cacheKey) return;
  event.respondWith(
    cacheFreshResponse(cacheKey, cacheKey)
      .catch(() => caches.match(cacheKey))
  );
});
