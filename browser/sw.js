const CACHE = "streamforge-web-v3";
const APP_FILES = ["./", "./index.html", "./styles.css", "./app.js", "./manifest.webmanifest"];

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(APP_FILES))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;

  const isAppFile = APP_FILES.some(path => new URL(path, self.location.href).pathname === new URL(request.url).pathname)
    || /\\.(?:html|css|js|webmanifest)$/.test(new URL(request.url).pathname);

  if (!isAppFile) return;

  // Prefer the newest deployed app files; use cache only when offline.
  event.respondWith(
    fetch(request, { cache: "no-store" })
      .then(response => {
        if (response && response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then(cache => cache.put(request, copy));
        }
        return response;
      })
      .catch(async () => {
        const cached = await caches.match(request);
        return cached || caches.match("./index.html");
      })
  );
});
