# BipinClicker — Pickup + Drop Auto Tap/Swipe

## ON once
1. Open the app and enter Pickup + Drop.
2. Turn **Pickup/Drop Automation ON** once.
3. Enable the Android Accessibility Service once in Android Settings.
4. You can close BipinClicker and use the phone normally. The saved setting remains ON.

The service only acts when a Pickup + Drop pair is found in the same visible ride/card. It does **not** click random text while Pickup/Drop mode is enabled.

## Matching
- Exact/partial location text matching.
- `all delhi`, `all noida`, `all ghaziabad`, `all gurugram` work as city-wide matches.

## Accept action
- Normal accessible Accept/Book action: accessibility click.
- Slider labels such as `Accept in 6s` / `Slide to...`: left-to-right accessibility gesture.

## Important Android note
Accessibility service must be enabled by the user in Android Settings. Android/OEM battery-saving settings can stop or restrict background services; this app cannot silently re-enable Accessibility after Android disables it.
