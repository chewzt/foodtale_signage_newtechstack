# Foodtale signage (new stack)

One store, one Raspberry Pi CMS, Android players on the same playlist with different crops. `foodtale_signage_mvp` / `release1.0` is the parked Flutter + Laravel snapshot. Do not port it.

Player on this branch: `0.2.24` (versionCode 25). The sync behavior is the 0.2.23 keeper. 0.2.24 only adds a HOME intent filter.

```
cms/       Go: admin, pairing, files, clock, beacon
player/    Kotlin / Media3 APK
protocol/  JSON + ports
deploy/    systemd + install.sh
```

## What runs in a store

The Pi is the store CMS. It keeps videos, pairing, playlists, walls, the store clock, and the beacon. It does not decode video and it does not decide whether the pictures match. There is no cloud console in this version.

Each player caches the manifest and the files, then computes the same timeline from the CMS clock. The lowest paired device id is the only one with sound. The others stay muted. That id is not a playhead supervisor.

Heartbeats update the admin page only. They do not change playback.

```
Pi CMS  --beacon UDP 48720-->  players find http://<pi>:8080
        <--clock UDP 8123---   Cristian sample, saved offset
        <--HTTP :8080--------  pair, manifest, media, heartbeat
players --UDP 48721-------->  PRESENCE (liveness) and START (cut announcement)
```

## Clock and cuts

On the Pi the master clock is `CLOCK_BOOTTIME`. A CMS process restart does not zero it. A Pi power loss does.

Every CMS process start runs Restart sync for every playlist, the same action as the admin button: the timeline starts about 8 seconds ahead, on a whole second, and `sync_generation` increments. Without that, a power loss leaves `start_master_ms` in the future and every player sits on the last frame.

Each player’s clock is `elapsedRealtime + offset`. Killing the app does not reset `elapsedRealtime`. Rebooting the device does. After a full power cycle, frame sync needs the CMS clock again. Cached files still play with no CMS; kill-and-reopen returns to the playlist. This version does not promise frame sync with no clock after reboot.

Playback, per device:

- Two decoders. The spare one prerolls the next item. The cut swaps. A late `play()` still plays.
- Opening lead is this device’s recent startup lag, memory only, cap 400ms, and only if the spare decoder is ready. Process death resets it to 0, so the first cuts after a cold start are late.
- Drift against the timeline, not against another screen. Inside ±50ms, speed 1.0. From 50ms to 800ms, 1.03 if behind and 0.97 if ahead. Worse than −800ms, one seek on that cut. The first 400ms of a slot is display-only. The last 200ms forces speed 1.0 and does not seek.

UDP 48721 carries `PRESENCE` (who is alive) and `START` (the audio device announcing a cut). Slaves do not chase another device’s playhead during a clip. If the audio device has been silent for more than 2 seconds, the next-lowest id can open a settle barrier. `seen=0` on that bus is not the picture gap.

## Pairing

Pair once with the code from the admin page. The player stores the token and the CMS `cmsId`. A beacon with a different `cmsId` is ignored, so a second CMS cannot take a paired screen. There is no unpair button: clear the app data and pair again.

The player polls `/api/app/version` and only shows a hint when `version_code` is higher. It does not download or install the APK. Put the APK in the CMS public directory yourself.

Declaring `HOME` does not make a certified Google TV open this app at power-on. The stock launcher stays higher priority. A cheap Android box may have its own autostart checkbox. That is the ROM, not this APK.

## Build

```bash
cd cms
go build -o foodtale-cms .
./foodtale-cms
```

Admin: `http://<lan-ip>:8080`

On a Pi:

```bash
cd cms && GOOS=linux GOARCH=arm64 go build -o foodtale-cms .
sudo ../deploy/install.sh
```

Copy `player/app/build/outputs/apk/debug/app-debug.apk` to `/var/lib/foodtale/public/foodtale-player.apk` after you build it. Replacing the binary at `/usr/local/bin/foodtale-cms` and restarting the service is what puts Restart sync on the Pi. Building the file in the repo does not.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd player
./gradlew :app:assembleDebug
```

## Not in this version

- CMS running on a TV box
- Google TV opening the player by itself when power returns
- A cloud merchant console
