// This SSH client needs a live server. Never cache API responses, credentials,
// downloads or the app shell; server upgrades must remain visible on reload.
self.addEventListener('install', event => {
  event.waitUntil(self.skipWaiting())
})

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', event => {
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request))
  }
})
