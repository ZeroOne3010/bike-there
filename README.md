# Bike There

Bike There is a personal Android ride recorder for everyday cycling. Rides, saved places and distance totals stay on the phone. Android 8.0 (API 26) or later is required.

## Record a ride

1. Open Bike There and tap **Arm tracker** on the first run. Allow precise location and notifications. On subsequent launches, the app arms itself if that setting is enabled.
2. Add places such as Home and Daycare under **Places**. Tap a map location or use **My location**, then set the arrival circle with the radius slider.
3. When the app says **Ready**, leave the phone in your pocket and cycle. A stop of about 12 seconds pauses moving time. A stop of about 30 seconds within a saved place ends and names the ride, trims off the wait and arms the next leg.
4. Use **Disarm** or **Finish & disarm** in the app or the status notification to turn off GPS. The Ready state also expires after an adjustable delay, initially 20 minutes.
5. Tap a finished ride to scrub its map, speed and elevation together or save a timestamped GPX. The Rides tab shows distance this month and this year.

The activity estimate uses GPS speed, so very slow riding and brisk walking can be hard to tell apart. Use **Start ride** for a manual start. Test the timing with your usual routes and stops.

In **Settings → Appearance**, choose System, Light or Dark. OpenStreetMap tiles are optional. The route and profiles work without a network connection. New rides record GPS elevation when available. Older rides can show estimated speed from the saved route and timestamps. Optional weather data uses Open-Meteo and sends the approximate starting area and date. The app records routes without a network connection.

GPX files can be uploaded manually to Strava. Strava API uploads and Solid Pod sync are planned.

## Build

Install JDK 17 or later and Android SDK Platform 36 with Build Tools 35.0.0, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug build uses a separate application ID. Use the signed release for rides you want to keep across release updates.

## Publish a signed APK

On a computer with JDK 17 or later and GitHub CLI, sign into the repository owner account from a checkout of this repository:

```sh
gh auth login
bash scripts/setup-signing.sh
```

The script creates the signing key once, stores it and its password under `~/.bike-there-signing`, and writes the repository's Actions secrets using GitHub CLI. Keep a private backup of both files. To restore the same key, pass its backup directory to the script. The app ID and signing certificate must stay the same for Android to update an installed release.

Create and push a version tag from the intended release commit:

```sh
git tag v0.2.0
git push origin v0.2.0
```

The Android workflow tests the app, builds the APK, checks its signing certificate and attaches it to a GitHub Release. Each higher version tag produces a higher Android version code. If the secrets are set after a tag was pushed, rerun the failed release job in GitHub Actions.

### Set up signing on Android

Install [Termux from F-Droid](https://f-droid.org/packages/com.termux/). In Termux, run the following and complete the GitHub device sign-in in your phone browser:

```sh
pkg update
pkg install git gh openjdk-17 openssl
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git
git clone https://github.com/ZeroOne3010/bike-there.git
cd bike-there
bash scripts/setup-signing.sh
git tag v0.2.0
git push origin v0.2.0
```

GitHub builds and signs the APK. Termux generates the key and sends it to Actions as an encrypted repository secret. If a release key already exists, restore that key and pass its backup directory to the script.

Termux keeps `~/.bike-there-signing` in app-private storage. Back up both files before uninstalling Termux or resetting the phone. To make an encrypted backup in Downloads, grant Termux storage access and choose a strong backup passphrase:

```sh
pkg install gnupg
termux-setup-storage
tar -C "$HOME" -czf - .bike-there-signing |
  gpg --symmetric --cipher-algo AES256 --output "$HOME/storage/downloads/bike-there-signing.tar.gz.gpg"
```
