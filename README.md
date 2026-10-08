# OpenTV Android TV
Clean-room Android TV IPTV player inspired by the user-facing navigation of DMC Smart TV, without copying proprietary DMC code or artwork.

Uses IPTV-org public playlist links at runtime. Includes D-pad navigation, Canada/Alberta/category presets, favorites, channel logos, Media3 playback, and Up/Down channel zapping.

The GitHub Actions workflow builds a debug APK for sideloading on Android TV / Google TV.

## EriTV Mobile — Android phones / Google Pixel

A separate `eritv-mobile` Android application with package ID `com.eritv.mobile`.
It **does not replace** OpenTV or the EriTV Android TV application.

- Tap the Eritrean flag launcher icon to open directly to the live EriTV stream.
- No menus, channel lists or permanent playback controls; stream scales to fit portrait and landscape.
- Screen stays awake while watching; playback pauses when the app goes into the background.
- Preserves the successful EriTV TV v1.1.1 playback profile: 15s minimum / 120s maximum buffer, 5s startup / 10s rebuffer, 90s frozen-stream threshold and retries for up to 10 minutes.
- Small translucent reconnection indicator only while retrying.
- `./gradlew :eritv-mobile:assembleDebug` (or `gradle :eritv-mobile:assembleDebug`) builds `eritv-mobile/build/outputs/apk/debug/eritv-mobile-debug.apk`.

The GitHub Actions workflow builds all three apps and publishes the mobile test APK
under the `eritv-mobile-v1.0.0-test` GitHub Release on main.
