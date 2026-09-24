<<<<<<< HEAD
# BIPIN Clicker

Ek standalone app jisme rider apni **Pickup City/Zone** aur **Drop City/Zone** set karta hai
(jaise "Gurugram", "Noida" - poori city, sector-by-sector nahi).
Jab bhi order aata hai, wo tabhi khud accept hota hai jab order ka pickup city AND drop city dono match karein -
us city ke andar chahe koi bhi sector ho, sab match hoga (Gurugram select kiya to Gurugram ka koi bhi
sector ka order uthega).
App me ads (AdMob banner) lage hain - yahi is app ki earning ka zariya hai.

## Areas ki list
`app/src/main/assets/locations.json` me sab areas hain: Delhi (Rohini, Dwarka, Mayur Vihar, Vasant Kunj, aur
areas), Noida, Gurugram, Ghaziabad. Is file ko edit karke naye areas add/remove kar sakte hain.

## Ads (AdMob)
Abhi Google ke **TEST Ad IDs** lage hain (`AndroidManifest.xml` me App ID, `activity_main.xml` me Ad Unit ID) -
inse sirf test/dummy ads dikhte hain, paisa nahi aata. Publish karne se pehle:
1. Apna AdMob account bana kar app register karein, apna real **App ID** milega - manifest me
   `com.google.android.gms.ads.APPLICATION_ID` wali line me daal dein.
2. Ek banner **Ad Unit ID** banayein - `activity_main.xml` me `app:adUnitId` me daal dein.

## GitHub par upload karne ke steps

1. Poora `BipinClicker` folder ek naye GitHub repo me upload karein.
2. Actions tab me `Build APK` workflow chalega.
3. Run complete hone ke baad Artifacts me `bipin-clicker-debug-apk` milega - download karke install karein.

## App kaise kaam karta hai

1. App kholte hi "Pickup / Drop Area Set Karein" button dikhega.
2. Do list hain - Pickup areas aur Drop areas - jitne chahiye utne select karein.
3. Save karte hi home screen par sirf wahi orders dikhenge jinka pickup AUR drop dono match karein.
4. Neeche ek ad banner dikhega.

## Backend jodna

Filhal orders demo/random generate hote hain testing ke liye (`MainActivity.kt` me `generateSampleOrders()`).
Jab aapka real delivery-app backend/API ready ho, isi function ko apne API call se replace kar dena -
baaki filtering logic (`AreaPrefs.isOrderInMyArea`) waisa hi kaam karega.
=======
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
>>>>>>> FETCH_HEAD
