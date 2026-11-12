# AutoRepack

Makes an Android Auto copy of an app that's already installed on the phone:

1. Rewrites the app's binary `AndroidManifest.xml`:
   - gives the copy a new package name and label
   - adds `com.google.android.gms.car.application` metadata (or keeps the app's own, if it has one)
   - makes relative class names absolute
   - renames permissions it defines, provider authorities and task affinities, so the copy doesn't clash with the original
2. Adds `res/aarepack_automotive_app_desc.xml` and registers it in `resources.arsc`.
3. Re-signs every APK (base and splits) with v1, v2 and v3 signatures, using an RSA key generated on the phone in the Android Keystore.
4. Installs all of them in one `PackageInstaller` session, next to the original.

Install `AutoRepack.apk` on the phone, then open AutoRepack and pick an app.

## Limits

- Android Auto can only show **media** apps (they have a `MediaBrowserService`), messaging notifications and Car App Library apps. A copy of any other app installs, but won't appear in the car.
- Android Auto hides sideloaded apps until you turn on **Settings › Developer settings › Unknown sources** in Android Auto.
- The copy has its own, separate data, so you'll need to sign in again.
- Apps that check their own signature, or tie Google Sign-In, Firebase or Maps keys to the original package or key, may refuse to run or lose some features.
- Hard-coded content provider URIs in the app's code still point at the original app's providers.

## Build

```
gradlew assembleRelease      (JAVA_HOME = Android Studio's jbr)
```

`desktop-test/RepackTest.java` runs the same rewriting engine (`app/src/main/java/com/autorepack/core`) on a PC, so you can check the output with `aapt2` and `apksigner`.
