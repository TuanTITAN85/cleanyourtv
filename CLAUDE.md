# cleanyourtv: personal notes for Claude

Personal fork of the Clean Your TV launcher (`com.gathin.tvhome`), running on the owner's projector.
Work on branch `personal-dashboard`.

## The device

- FORMOVIE (Xiaomi/Fengmi) projector, Android 14, ADB over TCP on port 5555.
- IP: `192.168.68.102` (DHCP, so it can change). Check it is up: `nc -z -G 3 192.168.68.102 5555`.
- If it times out: the projector is off or asleep, the IP changed (Settings > Network), or ADB is off.
- Plain `adb` also works: `~/Library/Android/sdk/platform-tools/adb connect 192.168.68.102:5555` (it sometimes
  shows the device `offline` right after connecting; reconnect). `tools/tv.py` (adb_shell over TCP) is the fallback.
- macOS has no `timeout` command; do not chain blocking `sleep`s.

## Running commands on the TV

One-time setup (the system `python3` has no `adb_shell`):

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
```

Then:

```bash
export TV_HOST=192.168.68.102
tools/venv/bin/python -I tools/tv.py 'getprop ro.build.version.sdk'
tools/venv/bin/python -I tools/tv.py --pull /data/local/tmp/s.png /tmp/s.png
```

## Build and install the launcher

```bash
cd launcher && TV_HOST=192.168.68.102 ./build-and-install.sh
```

- Unit tests and compile check: `cd launcher && ./gradlew --offline -q :app:compileDebugJavaWithJavac :app:testDebugUnitTest`.
- After every install, re-grant usage access (the "Most used" card is empty without it):
  `tools/venv/bin/python -I tools/tv.py 'appops set com.gathin.tvhome GET_USAGE_STATS allow'`
- Look at the result: `screencap -p /data/local/tmp/s.png` on the TV, `--pull` it, open the PNG.
  The Dashboard cards only show while a dock tile has focus (press Home, then DPAD_UP to the dock row).

## Device state (as set up)

- Home is `com.gathin.tvhome/.MainActivity` (`cmd package set-home-activity`). Stock launchers
  `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith` are disabled with
  `pm disable-user`; undo with `pm enable --user 0 <pkg>`.
- Reinstalling the APK makes Android switch accessibility services off. Afterwards re-enable the Home/Settings
  key handler and re-set Home:
  `settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService`,
  `settings put secure accessibility_enabled 1`, then `cmd package set-home-activity com.gathin.tvhome/.MainActivity`.
- The remote's gear button sends scancode 384 (keycode 83). `HomeKeyService` handles it through the
  `settings_key_scancodes` array in `config.xml` (empty upstream, `<item>384</item>` on this branch).
- The dock order is stored by upstream in its own way; the old custom order was lost. Rearrange with
  hold OK on Settings > Reorder.
- Verify an install with `dumpsys package com.gathin.tvhome | grep lastUpdateTime`, not with adb's message.
- No RTC battery: after a power cycle the clock can be wrong until the network sets it. Warn before advising one.

## Rules when touching the device (family uses the projector)

- Do not inject keys or install while someone may be watching. Before any key event check that
  `dumpsys activity activities | grep topResumedActivity` is `com.gathin.tvhome`.
- No screenshots while another app is in front. Crop screenshots before publishing: remove the Wi-Fi name,
  IP and accounts.
- Never press OK on top-bar items during tests (it once opened the keystone screen).
- Commit or push only when asked; confirm before outward-facing actions (PR comments, deleting branches).

## Git

- Push to remote `fork` (TuanTITAN85/cleanyourtv). Never push to `origin` (Gathin23, the upstream).
- `personal-dashboard` = upstream main + 384 scancode + Dashboard + fixes. Rebase it on `origin/main` to update.
  Other local branches (`main` stale, `dock-arrange*`, `remote-settings-key`, `personal-dashboard-backup`) are
  leftovers; `fork/pr-assets-dock-arrange` holds screenshots referenced by an old PR comment, keep it for now.
- Upstream PRs: #193 (settings key array) merged; #189 (dock rearrange) and #194 (Vietnamese) closed, upstream
  has its own Reorder and `values-vi`. Check a PR is still open before commenting.
- Upstream rules (CONTRIBUTING): no network, background services, polling or new permissions in the reference
  launcher, one topic per PR, screenshot plus "tested on". The Dashboard is personal only, never for upstream.

## Things learned

- The projector's clock once jumped to Jan 2027, which corrupted Android's usage-stats buckets: weekly and
  yearly buckets sat in the future, so the "Most used" card came back empty even with access granted and a
  reboot did not clear the yearly bucket. `Dashboard.readUsage()` therefore sums the daily buckets itself
  instead of calling `queryAndAggregateUsageStats`. If the card is empty again, check
  `tools/tv.py 'date'` and `dumpsys usagestats | grep timeRange` first.
- An Outlook/Office 365 calendar card was tried and removed. It needed an Azure app registration (device
  code flow + Graph `Calendars.Read`). A multi-tenant app without a verified publisher cannot be consented
  by users in another tenant, and the owner's company tenant (FPT) required admin approval. Do not redo it
  unless the app is registered in the target tenant or has a verified publisher (MPN). The test app
  "TV Home Calendar" (client id `6c98b07b-013d-4415-a91e-406ef2fbb304`) still exists in tenant
  `tuantitan.info`.
- The launcher deliberately has no INTERNET permission; keep it that way unless a feature needs it.
- Dashboard load: network queries run every 15 s and usage reads at most every 20 s, only while the cards are
  shown. A 5-minute A/B test (new, polling off, old dashboard) showed 0% ping loss and no adb drops in all three,
  so the Dashboard was not shown to cause the Wi-Fi/adb drops (those appeared after about 22 h uptime, weaker
  RSSI, high load average). Still unproven; monitor passively if it comes back.
- `/private/tmp/.../scratchpad/ab/net_ab.py LABEL SECONDS` style probe (ping 1/s plus adb check every 10 s) is
  a quick way to test connectivity; it is not kept in the repo.
