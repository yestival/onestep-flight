'use strict';

const CACHE_PREFIX = 'onestep-flight-shell-';
const CACHE_NAME = CACHE_PREFIX + 'v6';
const APP_SHELL = ['/', '/index.html', '/styles.css', '/app.js', '/share.html', '/share.css', '/share.js'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL))
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
    event.respondWith(
      fetch(request).catch(() => (url.pathname.startsWith('/share/') ? caches.match('/share.html') : caches.match('/index.html'))
        .then((cached) => cached || caches.match('/')))
    );
    return;
  }

  if (!APP_SHELL.includes(url.pathname)) return;
  event.respondWith(
    caches.match(url.pathname).then((cached) => cached || fetch(request))
  );
});
