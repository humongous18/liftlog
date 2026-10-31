# Liftlog

A workout log for your phone: FitNotes-style set tracking with WHOOP-style strain and readiness scores. Data stays on each phone (IndexedDB); move it with the backup file under More.

## Put it online with GitHub Pages

1. Upload every file in this folder to the root of a public GitHub repo (keep the `fonts` and `icons` folders).
2. In the repo, open Settings, then Pages. Under "Build and deployment", pick "Deploy from a branch", branch `main`, folder `/ (root)`, and save.
3. After a minute the app is at `https://<your-username>.github.io/<repo-name>/`.

## Install on a phone

- iPhone: open the link in Safari, tap Share, then Add to Home Screen.
- Android: open the link in Chrome, then choose Install app (or Add to Home screen) from the menu.

Once installed it opens full screen and works without signal.

## Habits link

Liftlog ticks off your "X days a week" habit in the [Habits](https://github.com/humongous18/Streaks) app.

- **Liftlog Android app:** automatic. Every workout you log syncs to Habits in the background. The first time, Habits sends a notification asking which habit to link (or creates a "Work Out" habit from your weekly goal). Status is under More › Habits.
- **Browser version on Android:** tap **Done · send to Habits** after a workout.

## Android app

The `android/` folder wraps this web app in a small native app (WebView, files bundled, works offline). It adds background sync to Habits, rest-timer notifications that work with the app closed, and native backup save/open. GitHub Actions builds it on every push; tag `v1.2.3` or run the workflow with a version to publish a release APK.

It must be signed with the same key as Habits (same four `KEYSTORE_*`/`KEY_*` secrets) — Android only lets apps with matching signatures use the private sync permission.

Moving from the browser version: in the browser go to More › Save backup, then in the app More › Restore backup.

## Shipping an update

Change the files, then bump `VERSION` in `sw.js` so phones pick up the new version the next time the app is opened online.

### Releasing a new version

Change the number in the `VERSION` file (e.g. `1.0.1`) and push to `main`. The workflow builds the APK and publishes it as a GitHub Release `v1.0.1` (only once per version). Builds without a new version are attached to the workflow run as artifacts.
