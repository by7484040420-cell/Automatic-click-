# SwiftGate (React Web Application)

Location-based Auto-Accept controller rewritten from the Android Kotlin codebase into React, TypeScript, Vite, and Tailwind CSS.

## Core Features
- **Control Panel & Order Watcher Live Test**: Toggle Auto-Accept ON/OFF, inspect active Pickup/Drop rules, test order screen text splitting (`(x.x km)` headers and `Accept in Ns` cutoff), Trip Details screen guard, and 2-tone Web Audio chime (`SoundPlayer`).
- **Pickup / Drop Area Preferences (`AreaPreferenceView`)**: Select cities, Delhi zones (with pincode matching), and individual localities from `locations.json`; add custom localities; create custom folders from ticked areas or comma/newline-separated lists; configure Pickup special keywords; or reset all rules.
- **Swipe Zone Editor (`SwipeZoneView`)**: Calibrate the start (`»`) and end (`END`) handles over a simulated slider bar, auto-snap, animate test swipes, save, or reset.
- **Permission Setup & OEM Autostart Guides (`PermissionSetupView`)**: Manage Accessibility, Overlay, Notification, Battery, and OEM Autostart checklist items plus step-by-step Auto Setup Wizard.
- **License & Update Manager (`LicenseUpdateView`)**: Device ID generation, clipboard copy, WhatsApp sharing, live GitHub `licenses.txt` approval check, and GitHub `update.txt` version check with 6-hour snooze.

## Development
```bash
npm install
npm run dev
```
