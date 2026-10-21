# cleanyourtv: personal notes for Claude

Personal fork of the Clean Your TV launcher (`com.gathin.tvhome`), running on the owner's projector.
Work on branch `personal-dashboard`.

## The device

- FORMOVIE (Xiaomi/Fengmi) projector, Android 14, ADB over TCP on port 5555.
- IP: `192.168.68.102` (DHCP, so it can change). Check it is up: `nc -z -G 3 192.168.68.102 5555`.
- If it times out: the projector is off or asleep, the IP changed (Settings > Network), or ADB is off.
- The `adb` binary on this Mac does not see it; use `tools/tv.py` (adb_shell over TCP).

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

## Git

- Push to remote `fork` (TuanTITAN85/cleanyourtv). Never push to `origin` (Gathin23, the upstream).

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
