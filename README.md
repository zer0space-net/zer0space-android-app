# zer0space for Android

The zer0space site as a native Android app. It is the same site you get in the
browser (dashboard, **Crimson**, **Music**), loaded from `https://zer0space.com`
in a WebView, plus three things a browser tab cannot do:

1. **Crimson resolves on your phone.** The app provides the companion API the
   Crimson source engine (`crimson-sources`) already looks for
   (`window.CrimsonExtension`). With it, every source runs from the phone's own
   IP: searches, hoster pages, VOE/Doodstream/Vidmoly tokens, and the
   Cloudflare-Turnstile gate in front of s.to's German hosters, which a hidden
   real browser page passes on its own. The video then streams CDN → phone
   directly, not through the homelab tunnel. The server keeps resolving in
   parallel as before, so nothing gets worse when the phone cannot.
2. **Music keeps playing with the screen off**, with lock-screen, notification,
   Bluetooth and headset controls. zer0space Music already speaks the Media
   Session API; the app forwards it to a native media session and keeps the
   process alive while something plays.
3. **Updates in place.** Settings → App on the dashboard shows the current
   version; inside the app a banner offers the update, downloads it, checks
   that it is signed with the same key, and hands it to Android's installer.
   You stay signed in.

What it deliberately is **not**: a second copy of the site. All UI and all
source logic stay on the server (and in Python where they were Python), so a
fix to the site or a source ships with a normal deploy, no app update needed.
The app only adds the native primitives listed above.

## Layout

```
app/src/main/
├── assets/bridge.js        page side of the bridge (injected only into zer0space.com)
└── java/net/zer0space/app/
    ├── MainActivity.kt     the WebView, navigation pinning, fullscreen video, offline screen
    ├── Bridge.kt           native side of the bridge: origin-bound message router
    ├── NativeFetcher.kt    CrimsonExtension.fetch(): Cronet (Chrome's stack) or OkHttp
    ├── UrlGuard.kt         public-internet-only guard for every native request
    ├── MediaRules.kt       per-CDN header rules for the player's own requests
    ├── PageResolver.kt     hidden real-browser page: resolveInPage + Turnstile gates
    ├── MediaHub.kt         what is playing, as the page reports it
    ├── PlaybackService.kt  media session + foreground service for background music
    ├── Updater.kt          download, verify (hash, package, signer), install
    ├── Http.kt             shared HTTP clients, the bridge's own cookie jar
    └── AppConfig.kt        the one origin everything is bound to
```

Server-side counterparts (other repos):

- `zer0space-dashboard`: `src/app_release.py` + Settings → App
  (`static/js/app-download.js`), routes `/api/app/release`, `/api/app/qr`,
  `/app/download`.
- `zer0space-crimson-sources` (+ the vendored copy in `zer0space-crimson-client`):
  the optional `resolveRedirect` / `resolveGate` hook s.to uses.

## Docs

- [docs/security.md](docs/security.md): the trust model and every guard.
- [docs/release.md](docs/release.md): versioning, signing, how an update reaches phones.
- [docs/testing.md](docs/testing.md): the manual test plan (German).

## Building

There is no Gradle wrapper in the repo; CI uses Gradle 8.11.1 via
`gradle/actions/setup-gradle`. Locally, with JDK 17 and the Android SDK:

```bash
gradle assembleDebug                       # app/build/outputs/apk/debug/
gradle -PappVersion=v1.2.3 assembleRelease # unsigned unless ZS_KEYSTORE_* are set
```

The debug build installs as `net.zer0space.app.debug` next to the real app and
has WebView remote debugging on (`chrome://inspect`).

## Credits

Crimson's source engine is Crimson Haven's work
(https://github.com/crimsonhaven-to); this app only implements the companion
contract it defines.
