# SecurityCam — Setup Guide

Turns an old Android phone or tablet (WiFi only, no SIM needed; Android 8.0+) into a
security camera that detects humans, vehicles and animals, records a short clip to the
SD card, and alerts you by email, ntfy push, and/or a local web page.

The built APK is at:

```
app/build/outputs/apk/release/app-release.apk
```

## 1. Install the app on the phone

Pick whichever is easiest:

**A. USB + adb (fastest)**
1. On the old phone: Settings → About phone → tap "Build number" 7 times to enable
   Developer options, then Settings → Developer options → enable "USB debugging".
2. Plug the phone into this computer via USB, accept the "Allow USB debugging?" prompt
   on the phone.
3. Run:
   ```
   ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
   ```

**B. File copy (no cable needed)**
1. Copy `app-release.apk` to the phone any way you like (email to yourself, Google Drive,
   a USB stick with an OTG adapter, etc).
2. On the phone, open the file with a file manager and tap install. You'll need to allow
   "install unknown apps" for whichever app you used to open the file (Settings will
   prompt you the first time).

## 2. First launch

1. Open **SecurityCam**. Grant the Camera permission (and notification permission if asked).
2. Point the phone's camera where you want it to watch, mount/prop it securely, and
   plug it into permanent power (this is meant to run 24/7).
3. Tap the gear icon to open **Settings** before starting monitoring — ideally set up
   at least one alert method (see below) so you hear about detections, then go back and
   tap **Start monitoring**. All alert methods (email/ntfy/web page) are optional and
   can be enabled in any combination; detection and recording work without them.
4. Tap **Settings → Ignore battery optimization** and allow it — otherwise Android may
   kill the app in the background after a while.
5. **Check the live preview/web page orientation.** Depending on how the phone is
   physically mounted, the image may appear rotated. If so, go to **Settings → Detection →
   Camera mounting rotation** and try each option (0°/90°/180°/270°) until the preview
   looks upright — this only needs to be set once per mounting position.

## 3. Email alerts (optional, Gmail SMTP + App Password)

Gmail won't accept your normal password from an app. Create a 16-character "App Password":

1. Go to https://myaccount.google.com/security and turn on **2-Step Verification** if it
   isn't already on.
2. Go to https://myaccount.google.com/apppasswords, enter a name like "SecurityCam", and
   generate a password.
3. In the app's Settings → Email alerts:
   - Enable email alerts
   - SMTP host: `smtp.gmail.com`, port `587` (already the default)
   - SMTP username: your full Gmail address
   - SMTP app password: the 16-character password from step 2 (not your normal password)
   - Send alerts to: the email address you want alerts sent to (can be the same address)
4. Tap **Send test email** to confirm it works.

Using a non-Gmail SMTP provider works too — just fill in that provider's host/port/user/password.

## 4. ntfy push notifications (optional, to your own phone)

ntfy is a free, no-account push notification service.

1. On your (main) phone, install the **ntfy** app from the Play Store or F-Droid.
2. Pick a private topic name that's hard to guess, e.g. `securitycam-a1b2c3`. Anyone who
   knows the topic name can see your notifications, so don't use something obvious.
3. In the ntfy app, subscribe to that topic name (default server ntfy.sh is fine, or your
   own self-hosted server if you have one).
4. In SecurityCam Settings → ntfy push alerts: enable it, leave server as
   `https://ntfy.sh` (or your own server URL), and enter the same topic name.
5. Tap **Send test notification**.

## 5. Local web page (LAN only)

Enabled by default on port 8080. While the phone is on your WiFi, browse to:

```
http://<camera-phone's-LAN-IP>:8080
```

Find the phone's IP under Settings → WiFi → (tap the connected network) → IP address.
The page shows monitoring status, a live-ish snapshot, and a scrollable list of recent
events with thumbnails and clip downloads. There's no login — only use this on a trusted
home network.

Tap **"▶ Watch live"** (or go to `/live`) for a continuously updating live view — no app
needed, it works in any browser. Frame rate matches the detection analysis rate (a few
frames per second, not full video), which is enough to see what's currently happening
after getting an alert. This only shows anything while the camera is actively bound (i.e.
monitoring is armed, or the app is open) — if nothing is running, the page just won't
update.

## 6. Remote access away from home WiFi (Tailscale, optional)

> **Note: this feature has not actually been tested yet** — the app-side plumbing (the
> "Remote base URL" setting and the links it builds into alerts) has been verified to
> work correctly with the LAN IP, but installing/configuring Tailscale itself and
> confirming the camera device is reachable through it hasn't been tried. Treat the
> steps below as a starting point, not a guarantee.

The local web page above only works while your other phone is on the **same home WiFi**
as the camera. [Tailscale](https://tailscale.com) is a free VPN app that creates a
private, encrypted network between just your own devices — no port forwarding, no public
exposure — so you can reach the camera's web page (and the "View event" links in alerts)
from anywhere with internet, not just at home.

1. Install the **Tailscale** app on the camera device *and* on the phone you want to view
   it from (Play Store, or sideload the APK the same way you installed SecurityCam).
2. Sign in with the same account on both (any Google/Microsoft/GitHub account works —
   Tailscale is free for personal use, up to 100 devices).
3. On the camera device, open the Tailscale app and note its Tailscale IP address (looks
   like `100.x.x.x`).
4. In SecurityCam, go to **Settings → Local web page → Remote base URL** and enter
   `http://<that-tailscale-ip>:8080` (using the same port as "Local web page" above).
5. From your other phone (with Tailscale running), try browsing to that same address —
   it should load the camera's status page even when you're both off the home WiFi.

Once set up, alert emails/ntfy notifications will include a "View event" link using this
address instead of the LAN-only IP, so it works from wherever you are. Leave the field
blank to keep using the LAN IP only (fine if you're always going to be on the same WiFi
when checking alerts).

## 7. Optional: AI description of detections (Gemini free tier)

If enabled, each alert email/notification also includes a one-sentence AI description of
the snapshot (e.g. "A person walking a dog on the driveway").

1. Go to https://aistudio.google.com/apikey and create a free API key (no credit card
   needed). Note the free-tier quota is small — as of mid-2026 it allows only about
   **20 requests/day** for the model the app uses (confirmed live via the API's own
   quota error), so on a busy camera many alerts will simply go out without the AI
   sentence once the daily quota is used up.
2. In SecurityCam Settings → AI description: enable it and paste the key.
3. Tap **Test Gemini description** to confirm it works.

This is best-effort — if the phone has no internet or the free quota is exhausted, alerts
still go out with the snapshot and detected object labels, just without the AI sentence.

## 8. Tuning detection

In Settings → Detection you can, per category (Human / Vehicle / Animal):
- toggle it on/off
- set the confidence threshold (higher = fewer false positives, but may miss real events)
- set how many consecutive analyzed frames must show it before it counts as a real event
  (helps ignore a single flickery misdetection)
- set the **cooldown between recordings**: how often a fresh clip/snapshot can start for
  the same category. Kept fairly short by design, so a lingering object still gets
  periodic fresh footage.
- set the **minimum time between repeat alerts**: this is what actually stops something
  that sticks around a long time (e.g. a car parked in view for hours) from spamming your
  email/ntfy — recordings keep happening as normal, but the alert itself won't repeat
  until this much time has passed. It's smarter than a plain timer, though: the app
  compares the position of the new detection against the one that triggered the last
  alert, so if a *different* car pulls in a few minutes later in a different spot, you
  still get notified immediately — the throttling only kicks in when it looks like the
  same physical object is still sitting in the same place.

Under Recording, you can set clip length, how far a continuing event can extend the clip,
where to save (SD card recommended vs internal storage — if no SD card is inserted, the
"SD card" setting silently falls back to internal storage), and the total storage cap —
the app deletes the oldest events automatically once the cap is hit.

## 9. Start on boot

Settings → General → "Start monitoring on boot" will auto-start monitoring after the
phone reboots (e.g. after a power blip), so you don't have to walk over and tap Start again.

## 10. Status and battery alerts

Under Settings → "Status & battery alerts":

- **Alert when monitoring starts/stops**: sends an email/ntfy alert with a live camera
  snapshot when you tap Start, and a text-only alert when you tap Stop. Useful to confirm
  the camera actually armed, and to know if someone (or the app itself) stopped it.
- **Alert on low battery**: since the camera phone should normally stay plugged in 24/7,
  this warns you if it's running on battery and drops below the configured threshold
  (default 20%) — e.g. after a power outage or a cable coming loose. Alerts escalate as
  the battery keeps dropping (won't repeat at the same level) and reset once it's
  charging again.

## 11. Camera name (multiple cameras)

If you run more than one camera phone, set a unique **Camera name** at the top of
Settings — it's included in every email/ntfy subject line and shown on the web page and
foreground notification, so you can tell which camera an alert came from.

## 12. Monitoring schedule

Settings → **Monitoring schedule** (opens its own screen) lets you automatically start
and stop monitoring at specific times, independently for each day of the week:

1. Toggle **Enable schedule** on.
2. For each day you want scheduled, toggle "Active on \<Day>" and set its **Start time**
   and **End time**. Leave a day's toggle off to not schedule anything that day.
3. An end time earlier than the start time (e.g. start 22:00, end 06:00) is treated as an
   overnight window spanning into the next day.

The schedule only forces monitoring **on** at the moment a window begins and **off** at
the moment it ends — the manual Start/Stop button on the main screen still works as an
override in between (e.g. if you manually stop partway through a scheduled window, it
stays off for the rest of that window and resumes at the next scheduled start).

The schedule uses Android's alarm system so it keeps working even if the app isn't open,
surviving reboots too. On Android 12 and newer, for the most precise timing you can grant
"Alarms & reminders" special access to the app in the phone's system Settings, though it
will still work (just possibly a few minutes less precise) without it.

## Notes / limitations

- Video clips are saved without audio (no microphone permission requested).
- Detection runs on-device using a TensorFlow Lite EfficientDet-Lite0 model (COCO classes),
  so it works even with no internet — only the email/ntfy/Gemini alerts need connectivity.
- **Still-frame "burst" mode on older camera hardware**: some phones (confirmed on a
  Xiaomi Mi A1) report their camera as "LEGACY" level, which can't reliably run continuous
  video recording at the same time as live detection — the video encoder starves and the
  clip aborts after ~1 second. The app detects this automatically and falls back to saving
  one JPEG frame per second for the event duration instead of `clip.mp4` (still saved to
  the same per-event folder on the SD card, as `frame_000.jpg`, `frame_001.jpg`, ...). The
  web page and event list show "no video (camera limitation)" for these events. This is a
  hardware limitation, not something adjustable in settings.
- If you see this fallback and want a real video file instead, the SD-card frames can be
  combined into an mp4 afterward on a computer with ffmpeg
  (`ffmpeg -framerate 1 -i frame_%03d.jpg -pix_fmt yuv420p clip.mp4`), though this isn't
  done automatically by the app.
- **Confirmed device compatibility**: real video recording works on a Samsung Galaxy
  Tab A (2016, Android 8.1, `SM-T580`) — full 1080p30 clips with no issues. The
  still-frame burst fallback (above) was needed on a Xiaomi Mi A1 (Android 9). Whether
  you get one or the other depends entirely on the specific device's camera hardware,
  not the Android version — there's no way to know in advance without testing.
- Minimum supported Android version is 8.0 (API 26), since that's when notification
  channels (which the foreground "monitoring" notification requires) were introduced.
