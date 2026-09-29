self.addEventListener('install', () => {
    self.skipWaiting();
});

self.addEventListener('activate', () => {
    self.registration.unregister()
        .then(() => console.log('Service Worker unregistered'))
        .catch((err) => console.error('Error unregistering Service Worker', err));
});
