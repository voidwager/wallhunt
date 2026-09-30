# Wallhunt

Describe a wallpaper, and Claude finds it. Wallhunt turns a prompt like *"rainy neon street, calm"* into
searches on [Wallhaven](https://wallhaven.cc), looks at what comes back, and ranks the candidates as phone
wallpapers. Pick one and set it on the home screen, lock screen, or both.

Android 11+.

## How it works

1. **Plan.** Claude (Opus 5.5, low effort) turns your prompt into 2–3 short, tag-style Wallhaven queries.
2. **Search.** Wallhaven's public API returns SFW portrait wallpapers at least 1080×1920. If fewer than
   four match, each query is retried on its leading keyword.
3. **Look.** Up to 12 thumbnails are centre-cropped to your screen's exact aspect ratio, so Claude judges what
   will actually be on screen, then ranks up to five (medium effort) with a one-line reason each.
4. **Set.** The full image is downloaded, cropped to the screen and applied. *Next pick* steps down the ranking.

**Revert** undoes the last set, one step at a time, per screen. The first time you set a wallpaper, Wallhunt
offers to save the one you have now, so Revert can take you all the way back. Android 13+ only lets apps read the
current wallpaper with *All files access*; Wallhunt uses it for that single read, and you can skip it.

## Setup

You need a Claude API key from [console.anthropic.com](https://console.anthropic.com). Wallhunt asks for it on
first launch (or tap **Key**) and stores it only on the phone. Each hunt makes two Claude requests, and the
second one includes the thumbnails.

## Build

Needs JDK 17–21 and the Android SDK (platform 36).

```
set JAVA_HOME=<path to JDK 21>
gradlew assembleRelease
```

Uses the official [Anthropic Java SDK](https://github.com/anthropics/anthropic-java). Structured-output schemas
are written by hand, because the SDK's class-derived schemas call `Field.getAnnotatedType()`, which Android's
runtime doesn't have.

## License

MIT
