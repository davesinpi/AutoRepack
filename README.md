# AutoRepack

AutoRepack makes an **Android Auto copy** of an app you already have installed. It
rewrites a copy of the app so Android Auto will show it on the car screen, signs
that copy with a key generated on your own phone, and installs it next to the
original — leaving the original app untouched.

It's a single, dependency-light Android app (only the platform SDK plus Google's
`apksig` signing library). All the rewriting happens on the phone; nothing is
uploaded anywhere.

> **Heads-up:** copies are signed by a key made on your phone, not downloaded from
> Google Play, so Android shows a **Play Protect "unsafe app blocked" prompt** at
> install. That's expected for any sideloaded, re-signed app — tap *More details →
> Install anyway*. AutoRepack can't (and shouldn't) hide that warning.

## What it does

Pick an app and AutoRepack:

1. **Rewrites the binary `AndroidManifest.xml`:**
   - gives the copy a new package name and label
   - adds `com.google.android.gms.car.application` metadata (or keeps the app's own, if it already has it)
   - makes relative class names absolute
   - renames permissions the app defines, provider authorities and task affinities, so the copy doesn't clash with the original
2. **Adds `res/aarepack_automotive_app_desc.xml`** and registers it in `resources.arsc`.
3. **Re-signs** every APK (base and splits) with v1, v2 and v3 signatures, using an RSA key generated once on the phone in the Android Keystore and reused afterwards (so copies can be updated in place).
4. **Installs** all of them in one `PackageInstaller` session, next to the original.

The UI is a single screen: a search field, filter chips (All / Car-ready /
Copies), and a bottom sheet for choosing how the copy shows in the car. Light and
dark themes are both supported.

## Install

Grab `AutoRepack.apk` from the [Releases](../../releases) page (or build it — see
below), install it on the phone, then open AutoRepack and pick an app.

To make copies actually appear in the car, turn on **Unknown sources** in Android
Auto: open Android Auto's settings, tap the version number ~10 times to unlock
**Developer settings**, then enable **Unknown sources**.

## Limits

- Android Auto can only show **media** apps (they have a `MediaBrowserService`), **messaging** notifications, and **Car App Library** apps. A copy of any other app installs, but won't appear in the car.
- The copy has its own, separate data, so you'll need to sign in again.
- Apps that check their own signature, or tie Google Sign-In, Firebase or Maps keys to the original package or key, may refuse to run or lose some features.
- Content provider URIs hard-coded in the app's own code still point at the original app's providers.
- Heavily obfuscated or protected apps may use a manifest/resource format AutoRepack can't rewrite safely; the install will fail cleanly if so.

## Build

Requires a JDK (Android Studio's bundled JBR works well) and the Android SDK.

Point Gradle at your SDK, either with an `ANDROID_HOME` environment variable or a
`local.properties` file in the project root (this file is git-ignored). Use forward
slashes, even on Windows, to avoid escaping issues:

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

Then build:

```
gradlew assembleRelease          # JAVA_HOME = Android Studio's jbr
```

The signed APK lands in `app/build/outputs/apk/release/`.

### Desktop test

`desktop-test/RepackTest.java` runs the same rewriting engine
(`app/src/main/java/com/autorepack/core`) on a PC, so you can rewrite a sample APK
and check the output with `aapt2` and `apksigner` without a phone.

## How it's organised

| Area | Files |
|------|-------|
| Rewriting engine (pure Java, no Android deps) | `app/src/main/java/com/autorepack/core/` |
| On-device signing, install, UI | `app/src/main/java/com/autorepack/` |
| Desktop harness | `desktop-test/` |

## Disclaimer

AutoRepack is provided for **personal use** — making a copy of an app *you already
have installed* work on your own car screen. Use it at your own risk.

- **It modifies and re-signs apps.** Each copy is repackaged and signed with a key
  generated on your phone, not by the original developer. Android and Google Play
  Protect will warn you about this at install; that warning is correct and
  expected. AutoRepack does not try to hide or bypass it.
- **It may break apps or violate their terms.** Some apps forbid modification or
  redistribution in their terms of service, check their own signature, or tie
  online features to the original package. A copy may refuse to run, lose features,
  or put your account at risk. Only repack apps you are entitled to use this way,
  and respect their licenses.
- **No warranty.** This software is provided "as is", without warranty of any kind
  (see [LICENSE](LICENSE)). The author is not responsible for any damage, data
  loss, account action, or other consequence of using it. It is not affiliated with
  Google, Android, or Android Auto.
- **Your responsibility to have the rights.** You must have the legal right to use
  and modify any app you repack, and you must comply with that app's license and
  with applicable law — including anti-circumvention laws such as the DMCA (17
  U.S.C. § 1201) and its equivalents elsewhere. You are solely responsible for
  determining whether your use is lawful where you are.
- **Scope of the license.** The [MIT license](LICENSE) covers AutoRepack's own
  source code only. It does **not** grant you any rights to the apps you process
  with it — those remain the property of their respective owners under their own
  licenses. A copy you create is a derivative of someone else's app, not of
  AutoRepack.
- **No data collected.** AutoRepack collects and transmits nothing. All work happens
  on your device; there are no servers, analytics, accounts, or network calls.
- **Trademarks.** Android, Android Auto, and Google Play are trademarks of Google
  LLC, used here only to describe what this software does. AutoRepack is not
  endorsed by, sponsored by, or affiliated with Google.

### Safe driving / distraction waiver

Android Auto deliberately blocks video and other distracting content while the
vehicle is moving. Repacking an app onto the car screen can defeat those
safeguards.

**Do not use AutoRepack to watch video, play games, or otherwise interact with a
distracting app while driving.** Doing so is dangerous, likely illegal where you
are, and can cause injury or death.

By using this software you accept **full and sole responsibility** for how and
where you use any copy it creates. You agree to obey all applicable traffic and
distracted-driving laws, and to keep your attention on the road. The author
accepts no liability for any accident, injury, death, fine, or other harm or loss
resulting from distracted driving or any other misuse. If you cannot use it safely
and lawfully, do not use it.

Do not use AutoRepack to pirate paid apps, remove license or subscription checks,
or redistribute modified apps.

## License

[MIT](LICENSE)
