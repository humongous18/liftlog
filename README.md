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

## Unbroken (habit tracker) link

On Android, Liftlog can mark your workouts in the [Unbroken](https://github.com/humongous18/Streaks) habit app. After a workout, tap **Done · send to Unbroken** on the log screen. The first time, Unbroken asks which task to use or creates a "Work Out" task from your weekly goal. Earlier workouts that weren't sent yet go along too. Hide the buttons under More › Unbroken.

## Shipping an update

Change the files, then bump `VERSION` in `sw.js` so phones pick up the new version the next time the app is opened online.
