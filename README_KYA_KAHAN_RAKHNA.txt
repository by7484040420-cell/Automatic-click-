V8 - SWIPE ZONE: hari line ko khud slider par rakho, swipe sirf wahin hoga.

1 NAYI file ADD karo:
  SwipeZone.kt            -> app/src/main/java/com/bipin/clicker/SwipeZone.kt
3 files REPLACE karo:
  OrderWatcherService.kt  -> app/src/main/java/com/bipin/clicker/OrderWatcherService.kt
  MainActivity.kt         -> app/src/main/java/com/bipin/clicker/MainActivity.kt
  activity_main.xml       -> app/src/main/res/layout/activity_main.xml
(SwipeIndicator.kt V7 wali chahiye - pehle se repo me honi chahiye.)

Naam wahi rakho, space nahi. Install ke baad Accessibility OFF -> ON.

Kaise use kare:
1. App kholo -> "Swipe Zone Set Karein" dabao (app peeche chala jayega).
2. Hari line + do gol handle dikhenge. Porter ka order card (ya uska screenshot) screen par rakho.
3. Hara » handle pakad kar poori line ko slider ke thumb par le jao (upar/neeche/left/right).
4. Narangi END handle ko slider ke right end par le jao.
5. TEST dabao (ek baar swipe karke dekhta hai), phir SAVE.
Uske baad order aane par swipe sirf isi line par hoga. RESET = zone hatao.
