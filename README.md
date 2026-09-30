# Wallhunt

Describe a wallpaper, and Claude finds it. Wallhunt turns a prompt like *"rainy neon street, calm"* into
image searches, looks at what comes back, and ranks the candidates as phone wallpapers. Pick one and set it on the home screen, lock screen, or both.

Android 11+.

## How it works

1. **Plan.** Claude (Opus 5.5, low effort) picks the one or two sources that suit the prompt and writes 2–3
   short keyword queries.
2. **Search.** Each source returns SFW portrait images at least 1080×1920. If fewer than four turn up, Wallhunt
   also tries your other sources, then retries each query on its leading keyword.
3. **Look.** Up to 12 thumbnails are centre-cropped to your screen's exact aspect ratio, so Claude judges what
   will actually be on screen, then ranks up to five (medium effort) with a one-line reason each.
4. **Set.** The full image is downloaded, cropped to the screen and applied. *Next pick* steps down the ranking.

### Sources

| Source | Key | Good for |
|---|---|---|
| [Wallhaven](https://wallhaven.cc) | none | digital art, anime, games, fantasy, abstract |
| [Openverse](https://openverse.org) | none | Creative Commons images, space, museum art; quality varies |
| [Unsplash](https://unsplash.com/developers) | free, optional | high-end photography |
| [Pexels](https://www.pexels.com/api/) | free, optional | broad stock photography |

Photos from Unsplash, Pexels and Openverse show their photographer or creator credit under the preview and in
History, as those sites ask. Tap the credit to open the creator's page.

**Revert** undoes the last set, one step at a time, per screen. The first time you set a wallpaper, Wallhunt
offers to save the one you have now, so Revert can take you all the way back. Android 13+ only lets apps read the
current wallpaper with *All files access*; Wallhunt uses it for that single read, and you can skip it.

**History** shows every wallpaper Wallhunt has set, newest first, with its prompt and the screens it was on. The
same image set on two screens appears once. Tap one to set it again on home, lock or both, open its source page,
or delete it. The last 30 sets are kept, plus your saved original.

## Updates

Wallhunt updates itself from this repo's GitHub releases. While the app is open it asks `api.github.com` for the
latest release at most every 6 hours. When there's a newer version, an **Update** button appears. The downloaded
APK is only installed if it has the same package name, a higher version, and the same signing certificate as
the installed app, and Android's installer asks you to confirm first. The first update asks you to allow
*Install unknown apps* for Wallhunt. To check right away, tap the **Wallhunt** title (it shows your version)
and choose **Check now**.

Versions before 1.2 have no updater: install 1.2 once by hand, and later versions arrive in the app.

## Setup

You need a Claude API key from [console.anthropic.com](https://console.anthropic.com). Wallhunt asks for it on
first launch (or tap **Keys**) and stores it only on the phone. Unsplash and Pexels keys are optional. Add them in
the same dialog to switch those sources on. A new Unsplash key allows 50 searches an hour. Each hunt makes two Claude requests, and the
second one includes the thumbnails.

## Build

Needs JDK 17–21 and the Android SDK (platform 36).

```
set JAVA_HOME=<path to JDK 21>
gradlew assembleRelease
```

Releases must all be signed with one key, or updates can't install over each other. Put the keystore's path and
passwords in the gitignored `local.properties` as `whsign.store`, `whsign.storePassword`, `whsign.alias`, and
`whsign.keyPassword`. Without them, builds fall back to the machine's debug key.

Uses the official [Anthropic Java SDK](https://github.com/anthropics/anthropic-java). Structured-output schemas
are written by hand, because the SDK's class-derived schemas call `Field.getAnnotatedType()`, which Android's
runtime doesn't have.

## License

MIT
