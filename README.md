# Foodtale signage (new stack)

Raspberry Pi CMS + Kotlin/Media3 players. `foodtale_signage_mvp` / `release1.0` is frozen.

```
cms/       Go: admin, pairing, files, Cristian UDP 8123, beacon UDP 48720
player/    Android APK (phone, tablet, TV)
protocol/  JSON + ports
deploy/    systemd + install.sh
```

## Pi CMS

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
# optional: sudo ../deploy/install.sh --static-ip 192.168.1.50 --gateway 192.168.1.1 --interface eth0
```

Copy `player/app/build/outputs/apk/debug/app-debug.apk` to `/var/lib/foodtale/public/foodtale-player.apk` after you build it.

## Android player

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd player
./gradlew :app:assembleDebug
```

Sideload the APK (or download it from the Pi admin). Pair once. After that the box listens for the Pi beacon if DHCP moves the IP.

HUD shows leader/slave, clock offset, RTT, playhead delta. Long-press the HUD to toggle speed catch-up (default off). Do that only after a two-box recording test.
