# Contributing to Landing

Thanks for helping. Landing is small on purpose: one job (make any AI your home screen), done invisibly and fast.

## Ground rules
- **Keep it neutral.** No features tied to one AI vendor, no accounts, no analytics, no network calls. Landing has no INTERNET permission, and it stays that way.
- **Keep the fast path empty.** A Home press must not inflate views or do work on the main thread before handing off.
- **No new dependencies.** It's plain Java and framework views, with no AndroidX and no Gradle. If you need a library, open an issue first.
- **Never commit keys** (`*.jks`, `*.keystore`, passwords). `.gitignore` covers the usual names.

## Adding an AI app to the "AI apps" list
Edit `app/src/com/crit/landing/AiApps.java` and add the package name (preferred) or the exact app label. It's only a sort hint, since any app can already be picked.

## Building
See "Build from source" in the README. `./build.sh` with no keystore produces a debug-signed APK in `dist/`.

## Pull requests
1. Fork, branch from `main`, and keep each PR to one change.
2. Test it on a real phone: setup, a Home press, a double press, unlock, and reboot.
3. Say which phone and Android version you tested on, and add a line to `CHANGELOG.md`.

## Reporting bugs
Open an issue with your phone model, Android version, the app you picked as Home, and what happened versus what you expected. Skin-specific quirks (MIUI, One UI, ColorOS, and others) are very welcome.
