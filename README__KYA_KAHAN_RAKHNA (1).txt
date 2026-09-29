BIPIN CLICKER - v2 (OCR: screen ke pixels se Accept dhoondh kar slide/tap)

ADD (nayi file):
 1. ADD__ScreenOcrScanner.kt
    -> app/src/main/java/com/bipin/clicker/ScreenOcrScanner.kt

REPLACE (purani file ki jagah, REPLACE__ / ADD__ naam se hata kar asli naam rakho):
 2. REPLACE__OrderWatcherService.kt        -> app/src/main/java/com/bipin/clicker/OrderWatcherService.kt
 3. REPLACE__DebugOverlay.kt               -> app/src/main/java/com/bipin/clicker/DebugOverlay.kt
 4. REPLACE__FloatingBubbleService.kt      -> app/src/main/java/com/bipin/clicker/FloatingBubbleService.kt
 5. REPLACE__accessibility_service_config.xml -> app/src/main/res/xml/accessibility_service_config.xml
 6. REPLACE__app_build.gradle              -> app/build.gradle

Install ke baad ZAROORI: Accessibility service OFF karke phir ON karo
(canTakeScreenshot naya permission hai, purani ON service me nahi aata).

Kya karta hai:
- Har ~0.6 sec screenshot leta hai, OCR se text padhta hai (offline, internet nahi).
- "Accept" wali line mile + Drop/Pickup city set se match ho -> slider pill pixel se dhoondh kar
  hold + drag, slider na mile to us jagah tap.
- Photo / screenshot / camera me dikhi doosre phone ki screen par bhi chalega jab tak text saaf padha jaye.
- Android 11+ chahiye. Isse neeche wale phone par OCR band rehta hai, purana tareeka chalta hai.
Dhyan: OCR battery zyada khata hai. Jab chalana na ho, START/STOP se pause kar do.
