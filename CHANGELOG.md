# Changelog

## 1.0.1 (2026-10-10)
The look now matches the demo video. Same behavior, permissions, and release key, so it installs over 1.0.0.
- Setup picker: "Featured" and "All apps" sections, roomier cards with a one-line subtitle on featured apps, an accent outline and check on the selected app, and a **Set as Home app** button at the bottom (tap an app to select it, then the button). The step dots are hidden on this screen.
- Buttons are accent-blue pills with dark labels across setup.
- Drawer: solid dark sheet with 24 dp corners, a "Search apps" pill, a ring settings icon in place of the ⚙ character, more space between icon rows, and a deeper dim behind the sheet.
- Drawer opens and closes with a smooth slide and no bounce (still follows your swipe speed).
- Bundles the Geist font (SIL OFL 1.1) for all of Landing's own text.
- The setup's drawer demo uses the same colours as the real drawer.

## 1.0.0 (2026-10-10)
First public release.
- Any app can be your Home. Setup lists AI assistants first (ChatGPT, Claude, Gemini, Perplexity, Copilot, Grok, DeepSeek, Le Chat, Meta AI, Pi, and others), but every installed app is pickable.
- Invisible hand-off: a Home press goes straight to your chosen app with no flash or animation.
- Press Home twice for a spring-animated app drawer with search. Long-press an app to make it Home.
- Optional open-on-unlock: unlock the phone and land in your chosen app.
- Choose what Home opens: the app where you left off, a fresh start, an app shortcut, a specific screen, or a deep link.
- Five-step setup, plus a settings sheet showing status for Home, unlock, and battery.
- Builds without Gradle (build.sh) and is signed with a new release key.

## 0.9.0-beta1
Private beta.
