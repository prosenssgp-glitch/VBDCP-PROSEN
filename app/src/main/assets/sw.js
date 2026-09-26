const CACHE_NAME = "vbdcp-shell-v3";
const APP_SHELL = ["./", "./index.html", "./manifest.webmanifest", "./logo.png", "./mosquito-red.svg"];
self.addEventListener("install", event => { event.waitUntil(caches.open(CACHE_NAME).then(c => c.addAll(APP_SHELL)).then(()=>self.skipWaiting())); });
self.addEventListener("activate", event => { event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))).then(()=>self.clients.claim())); });
self.addEventListener("fetch", event => {
  if(event.request.method !== "GET") return;
  const url = new URL(event.request.url);
  if(url.origin !== self.location.origin) return;
  if(url.pathname.endsWith("/index.html") || url.pathname === "/") {
    event.respondWith(fetch(event.request, {cache:"no-store"}).then(r => { const copy=r.clone(); caches.open(CACHE_NAME).then(c=>c.put(event.request,copy)); return r; }).catch(()=>caches.match(event.request)));
    return;
  }
  event.respondWith(caches.match(event.request).then(cached => cached || fetch(event.request).then(r=>{const copy=r.clone(); caches.open(CACHE_NAME).then(c=>c.put(event.request,copy)); return r;})));
});
