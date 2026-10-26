# Security model

The app gives one website native powers: fetch any URL from the phone's IP,
rewrite headers on media requests, run hidden browser pages, install updates.
Everything below exists to keep those powers with the zer0space site and away
from everything else that runs in the WebView: hoster iframes, ad scripts,
players, and any page a link might lead to.

## 1. One origin, compared exactly

`AppConfig.origin` (`https://zer0space.com`, from `BuildConfig.BASE_URL`) is the
only origin with privileges.

- The bridge uses androidx.webkit `addWebMessageListener` +
  `addDocumentStartJavaScript`, both with the allow-list `{origin}`. WebView
  only creates `zs_native` and runs `bridge.js` in frames of exactly that
  origin. A cross-origin iframe inside Crimson (a movie player) gets neither.
  This replaces `addJavascriptInterface`, which exposes an object to every
  frame of every page.
- `Bridge.onPostMessage` re-checks the origin string and accepts main-frame
  messages only.
- The top-level WebView never leaves the origin: any other http(s) navigation
  opens in the real browser, any other scheme (`intent:`, `market:`) is
  dropped. Iframes may navigate freely; they hold no privileges.
- Popups are refused (`onCreateWindow` returns false), file and content access
  are off, mixed content is blocked, permission prompts are denied.

## 2. The native fetcher cannot reach your network

`CrimsonExtension.fetch` is, by design, a proxy that runs on the phone. Without
a guard it would also let any script on the site read the user's LAN. So
(`UrlGuard`):

- http/https only; never the app's own host or its subdomains;
- no LAN names: `localhost`, `*.local`, `*.lan`, `*.internal`, `*.home.arpa`,
  `*.home`, `*.corp`, `*.intranet`, Tailscale `*.ts.net`;
- IP literals and every DNS answer must be public: loopback, RFC 1918,
  link-local, CGNAT `100.64/10` (Tailscale), ULA `fc00::/7`, NAT64, multicast
  and reserved ranges are refused;
- OkHttp resolves through the filtering DNS, so a public name that resolves to
  a private address (DNS rebinding) fails at connect time; every redirect hop
  is re-checked by a network interceptor;
- Cronet's resolver cannot be hooked, so the host is resolved and checked
  before the request and before each redirect is followed;
- the page cannot set `Cookie`, `Host`, `Proxy-*` or hop-by-hop headers; the
  bridge keeps its **own** in-memory cookie jar, separate from the WebView's,
  so a hoster never sees or sets a zer0space cookie;
- bodies are capped at 25 MB, at most 8 fetches run at once.

## 3. Media header rules are narrow

`installMediaRules` lets the engine say "requests to this CDN need this
Referer". Rules (`MediaRules`):

- apply to GET/HEAD only, to public hosts only, never to the app's own host;
- may only set `Referer`, `Origin`, `User-Agent`, `Accept`, `Accept-Language`,
  `X-Requested-With`, `Sec-Fetch-*`, `Sec-CH-*`. Never `Cookie` or
  `Authorization`;
- open CORS for the app origin only (`Access-Control-Allow-Origin:
  https://zer0space.com`), never `*`;
- a rule needs a domain or a URL filter (no match-everything rule), max 64,
  and the engine clears them at the start of every episode.

## 4. Hidden pages hold nothing

`PageResolver` runs hoster pages the user never sees. Each run gets a fresh
WebView with no bridge, no file access, no popups, no downloads, no
permission grants, dialogs auto-dismissed, all media muted, and requests to
IP-literal private addresses blocked. It is destroyed afterwards. Safe
Browsing is off for these pages only, because an interstitial would stall the
resolve and nothing on them is ever shown or downloaded.

## 5. Updates are checked twice before Android checks them again

`Updater` only installs when:

1. the URL starts with this repo's release-download prefix (compiled in, not
   taken from the server);
2. the release is newer than the installed version (no downgrades) and the
   APK's own versionCode equals the one announced;
3. the file's SHA-256 matches the release's published digest;
4. the APK has this app's package name **and** the same signing certificate as
   the installed app.

Android then refuses any update with a different key anyway; check 4 also
stops a different package from being pushed through the button. The user
confirms every install in the system dialog.

The signing key lives only in GitHub Actions secrets and in the owner's
offline backup. It is never in this repository. Losing it means existing
installs can no longer be updated in place (see release.md).

## 6. What the phone keeps

- `allowBackup=false` + data-extraction rules excluding everything: the
  WebView cookie store (the zer0space session) is never copied to a cloud
  backup or a new phone.
- User-installed CAs are not trusted (network security config), so a
  configuration profile on the device cannot intercept the session.
- WebView remote debugging is on in debug builds only.

## Known, accepted limits

- Cleartext http is allowed for the native fetcher (a few hoster mirrors are
  http-only). The site itself is https-only and pinned by `isAppUrl`.
- A cross-site script that runs *on zer0space.com itself* would get the bridge,
  like any extension API. The dashboard's CSP (`script-src 'self'`, no inline
  scripts) is the line of defence there, and the guards above limit what the
  bridge can be used for even then: no LAN, no zer0space cookies, no foreign
  APKs.
