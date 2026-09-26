VBDCP Online Attendance - Android/Netlify Ready Build

Files:
- index.html: application
- logo.png: app icon / logo
- manifest.webmanifest: PWA/Android wrapper metadata
- sw.js: update-aware service worker
- mosquito-red.svg: header artwork

Firebase project is unchanged. Deploy the folder contents as the site root on Netlify.
For Android packaging, use this same web root/URL. The app's Firebase data remains in the existing Firebase project.

IMPORTANT: Do not rename or remove manifest.webmanifest, sw.js, logo.png, or mosquito-red.svg.

GPS testing note:
- Chrome Geolocation requires HTTPS or a trusted localhost origin.
- When testing with Localhost Lite on the same phone, open http://localhost:8080 if available.
- A LAN address such as http://100.xx.xx.xx:8080 is not a secure context, so Chrome may block GPS permission.
- On Netlify HTTPS, the GPS API can request the phone location normally after permission is granted.


GPS location summary update:
- Work Start saves GPS coordinates and a GPS-derived area name (reverse geocoding).
- Attendance Submit captures a fresh GPS position when available, then stores the submit area name, coordinates, and Start-to-Submit distance in km.
- Admin Attendance Summary shows Start and Submit GPS details in compact line-by-line form; the old red “গ্রাম mismatch” warning is no longer shown.
- Attendance is not blocked just because the Start village and Submit village differ.
- Firebase/network problems use a professional in-app notice/status instead of raw browser error popups.
