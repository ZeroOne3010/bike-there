# Bike There

Bike There is a personal Android ride recorder for everyday cycling. Rides, saved places and distance totals stay on the phone. Android 8.0 (API 26) or later is required.

## Record a ride

1. Open Bike There and tap **Auto-start** on the first run. Allow precise location and notifications. On subsequent launches, the app enables Auto-start if that setting is enabled.
2. Add places such as Home and Daycare under **Settings → Places**. Tap a map location or use **My location**, then set the arrival circle with the radius slider. The list shows each circle's radius and centre in degrees and decimal minutes.
3. On opening the app, GPS warms up with precise location permission. It can lock while recording is off. Standby stops when you leave the app, while an armed or active tracker can continue. While acquiring GPS the map can centre on an approximate location, and supported devices show how many satellites are seen and used. **Auto-start** waits for cycling after GPS is ready. **Manual start** uses a fresh lock immediately or waits for the next usable fix. The live map shows elapsed time, average speed over moving time, and distance. A stop of about 12 seconds pauses moving time. A stop of about 30 seconds within a saved place ends and names the ride, trims off the wait and arms the next leg.
4. Use **Disarm** or **Finish & disarm** in the app or the status notification to turn off GPS. The Ready state also expires after an adjustable delay, initially 20 minutes.
5. Open the **Ride log**, then tap a finished ride to scrub its map, speed and elevation together. A triangle marks the start and a checkered flag marks the finish. You can also save a timestamped GPX. The Stats tab shows distance totals and a monthly calendar of rides or distance per day. You can go back to the month of your first ride. Settings shows the installed app version.

The activity estimate uses GPS speed, so very slow riding and brisk walking can be hard to tell apart. Use **Manual start** for a manual start. Test the timing with your usual routes and stops.

In **Settings → Appearance**, choose System, Light or Dark. OpenStreetMap tiles are optional. The route and profiles work without a network connection. New rides record GPS elevation when available. Older rides can show estimated speed from the saved route and timestamps. Optional weather data uses Open-Meteo and sends the approximate starting area and date. The app records routes without a network connection.

Under **Settings → Units**, choose System, Metric or Imperial. System uses Android's measurement preference when provided and otherwise uses metric. The choice changes displayed distances, speeds, heights, arrival radii and weather. GPX export uses standard GPX units.

GPX files can be uploaded manually to Strava. Strava API uploads and Solid Pod sync are planned.

## Build

Install JDK 17 or later and Android SDK Platform 36 with Build Tools 35.0.0, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug preview uses a separate application ID and a generated debug certificate. Previews built in different GitHub jobs can have different certificates and may require a fresh install. A signed release installs alongside the preview. Use the signed release for rides you want to keep across updates.

## Publish a signed APK

On a computer with OpenSSL and GitHub CLI, sign into the repository owner account from a checkout of this repository:

```sh
gh auth login
bash scripts/setup-signing.sh
```

The script creates the signing key once, stores it and its password under `~/.bike-there-signing`, and writes the repository's Actions secrets using GitHub CLI. Keep a private backup of both files. To restore the same key, pass its backup directory to the script. The app ID and signing certificate must stay the same for Android to update an installed release.

Create and push a version tag from the intended release commit:

```sh
git tag v0.6.1
git push origin v0.6.1
```

The Android workflow tests the app, builds the APK, checks its signing certificate and attaches it to a GitHub Release. Each higher version tag produces a higher Android version code. If the secrets are set after a tag was pushed, rerun the failed release job in GitHub Actions.

### Set up signing on Android

In Termux, run the following and complete the GitHub device sign-in in your phone browser:

```sh
pkg update
pkg install git gh openssl
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git
git clone https://github.com/ZeroOne3010/bike-there.git
cd bike-there
bash scripts/setup-signing.sh
git tag v0.6.1
git push origin v0.6.1
```

The script uses OpenSSL to generate the key in Termux and sends it to Actions as an encrypted repository secret. GitHub builds and signs the APK. If a release key already exists, restore that key and pass its backup directory to the script.

Termux keeps `~/.bike-there-signing` in app-private storage. Back up both files before uninstalling Termux or resetting the phone. To make an encrypted backup in Downloads, grant Termux storage access and choose a strong backup passphrase:

```sh
pkg install gnupg
termux-setup-storage
tar -C "$HOME" -czf - .bike-there-signing |
  gpg --symmetric --cipher-algo AES256 --output "$HOME/storage/downloads/bike-there-signing.tar.gz.gpg"
```
