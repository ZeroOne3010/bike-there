# Bike There

Bike There is a personal Android ride recorder for everyday cycling. Rides, saved places and distance totals stay on the phone. Android 8.0 (API 26) or later is required.

## Record a ride

1. Open Bike There and tap **Arm** on the first run. Allow precise location and notifications. On subsequent launches, the app arms itself if that setting is enabled.
2. Add places such as Home and Daycare under **Places**. You can enter coordinates or use your current GPS position.
3. When the app says **Ready**, leave the phone in your pocket and cycle. A stop of about 12 seconds pauses moving time. A stop of about 30 seconds within a saved place ends and names the ride, trims off the wait and arms the next leg.
4. Use **Disarm** or **Finish & disarm** in the app or the status notification to turn off GPS. The Ready state also expires after an adjustable delay, initially 20 minutes.
5. Tap a finished ride to scrub its route or save a timestamped GPX. The Rides tab shows distance this month and this year.

The activity estimate uses GPS speed, so very slow riding and brisk walking can be hard to tell apart. Use **Start ride** for a manual start. Test the timing with your usual routes and stops.

OpenStreetMap tiles are optional. The route and time slider work without network access. Optional weather data uses Open-Meteo and sends the approximate starting area and date. The app records routes without a network connection.

GPX files can be uploaded manually to Strava. Strava API uploads and Solid Pod sync are planned.

## Build

Install JDK 17 or later and Android SDK Platform 36 with Build Tools 35.0.0, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug build uses a separate application ID. Use the signed release for rides you want to keep across release updates.

## Publish a signed APK

On the Mac where you want to keep the signing backup, install GitHub CLI and sign into the repository owner account:

```sh
gh auth login
bash scripts/setup-signing.sh
```

The script creates the signing key once, stores it and its password under `~/.bike-there-signing`, and writes the repository's Actions secrets using GitHub CLI. Keep a private backup of both files. To restore the same key, pass its backup directory to the script. The app ID and signing certificate must stay the same for Android to update an installed release.

Create and push a version tag from the intended release commit:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The Android workflow tests the app, builds the APK, checks its signing certificate and attaches it to a GitHub Release. Each higher version tag produces a higher Android version code. If the secrets are set after a tag was pushed, rerun the failed release job in GitHub Actions.
