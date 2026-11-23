# Tasks

## Done

- [x] Project structure: `core` (plain Kotlin) and `app` (Android, Jetpack Compose)
- [x] Shipment rules: excursions, alarms, data gaps, verdict, mean kinetic temperature, with tests
- [x] Decoder for the standard Bluetooth temperature characteristic (0x2A6E), with tests
- [x] Fridge simulator and a live simulated sensor (door and link switches), with tests
- [x] Live screen: temperature, connection state, chart, report, demo switches
- [x] A stale reading is greyed out and labelled while the sensor is disconnected
- [x] Checked on an emulator (Pixel 9 Pro XL): pass, door left open (fail), sensor dropout (incomplete)
- [x] Dashboard: FastAPI + SQLite server, live web page, upload queue on the phone that survives a dead network
- [x] Checked with two emulators reporting at once: one passing, one failing after the door was left open
- [x] GitHub Actions: tests and app build on every push

## Next: show it working (first weekend)

- [ ] Install Android Studio, open this folder, run the app on your phone (USB debugging) or an emulator
- [ ] Play with the demo: open the door, watch the excursion become an alarm; cut the link, watch the gap
- [ ] Read through `core/` and its tests until you can explain every rule out loud
- [ ] Keep a short `LEARNING.md`: what was new in Kotlin, and how you figured it out
- [ ] Write the "How I used AI" section of the README in your own words
- [ ] Create a GitHub repo, push, check the CI run is green, pin the repo on your profile

## Then: the real sensor (second weekend)

- [ ] Choose a sensor. Either it offers the standard Environmental Sensing Service, or its data format is
      documented. Capture real bytes with the nRF Connect app and turn them into decoder tests first.
- [ ] `BleSensorSource` implementing `SensorSource`:
  - permissions: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+), asked at runtime
  - scan by service UUID, connect, subscribe to notifications
  - map disconnects to `LOST` / `RECONNECTING`, retry with a growing wait
- [ ] A switch in the app between simulator and real sensor

## Later

- [ ] Save shipments on the phone (Room), so waiting uploads also survive an app restart
- [ ] Deploy the dashboard server (for example a small cloud VM or Render) with HTTPS and an API key per phone
- [ ] Optional: sync to Firebase (Firestore) instead of or next to the own server
- [ ] Share a shipment report (text or PDF)
- [ ] Instant alarm for a hard limit (for example above 12 °C), not just the 15-minute rule
