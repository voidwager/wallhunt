# Wallhunt

Describe a wallpaper, and Wallhunt searches free wallpaper and photo sites for it. Type something like
*"rainy neon street, calm"*, scroll the results, and set the one you like on the home screen, lock screen, or both.
No account or key is needed.

Android 11+.

## How it works

1. **Search.** Your prompt becomes keyword searches ("rainy neon street calm", then "rainy neon"), with filler
   like "wallpaper" or "the" dropped. Every source you can use is searched at once, and the results are mixed
   together, up to 30 SFW portrait images of at least 1080×1920. If fewer than four turn up, each search is
   retried on its first keyword.
2. **Pick.** The results appear as a grid, each tile tagged with its source. Tap one to preview it exactly as it
   will be cropped for your screen.
3. **Set.** Home, Lock or Both applies it. *‹ Results* (or the back gesture) returns to the grid.

### Optional: AI picks with Claude

Claude is a paid API, so it's off unless you add a key under **Keys**. With a key, Claude (Opus 5.5) chooses
the sources and search words that suit your prompt. It then looks at the first 12 results, cropped exactly as
they'll appear, and moves its top five to the front of the grid with a badge and a one-line reason. If Claude
fails (bad key, rate limit, declined), you get the plain results with a note instead.

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

Nothing to set up: Wallhaven and Openverse work without any key. Every key under **Keys** is optional and
stays on the phone:

- **Unsplash** and **Pexels** (free) add those photo sources. A new Unsplash key allows 50 searches an hour.
- **Claude** (paid, [console.anthropic.com](https://console.anthropic.com)) turns on AI picks. Each search then
  makes two Claude requests, and the second one includes 12 thumbnails.

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
