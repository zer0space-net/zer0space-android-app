# Releasing and updating

## Two layers, two update paths

| What changed | How it reaches phones |
|---|---|
| Anything on the site: dashboard, Crimson UI, Crimson sources, Music | Normal deploy of that service. The app loads the site live, so the next page load has it. **No app update.** |
| This repo: bridge, player service, updater, fetcher | New tag here → signed APK → in-app update banner. |

Keep the native part small so the second row stays rare.

## Cutting a release

```bash
git tag -a v1.2.0 -m "What changed, one line per item"
git push origin v1.2.0
```

`.github/workflows/android.yml` then:

1. checks the tag is `vMAJOR.MINOR.PATCH` (minor/patch below 100);
2. builds with `versionName = 1.2.0`, `versionCode = 10200`
   (`MAJOR*10000 + MINOR*100 + PATCH`; the dashboard computes the same number
   in `src/app_release.py`, keep the two in step);
3. signs with the release key from the Actions secrets, verifies the
   signature with `apksigner`;
4. publishes a GitHub Release with `zer0space-1.2.0.apk` + `SHA256SUMS`. The
   tag message becomes the release notes shown in the app.

Within ~10 minutes (the dashboard's cache) Settings → App shows the new
version, and the app shows an "Update available" banner on the dashboard.

## The signing key

Four Actions secrets on this repo:

| Secret | Content |
|---|---|
| `ZS_KEYSTORE_BASE64` | the PKCS12 keystore, base64 |
| `ZS_KEYSTORE_PASSWORD` | its password |
| `ZS_KEY_ALIAS` | `zer0space` |
| `ZS_KEY_PASSWORD` | same as the keystore password |

The keystore and its password are also kept **offline by the owner** (password
vault). This matters: Android only installs an update signed with the same key
as the installed app. A lost key means every user has to uninstall and
install fresh (and sign in again). A leaked key means someone could build an
"update" the phones would accept, so rotate the GitHub secrets if it ever
leaks, and tell users to reinstall.

Pushes to `main` (and PRs) do not need the key: they lint and build a debug
APK, uploaded as the `zer0space-debug-apk` artifact of the run.
