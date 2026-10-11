# Browser version (no APK, no special permissions)

1. Install **Firefox for Android** from the Play Store.
2. In Firefox: menu → Add-ons → install **Violentmonkey** (or Tampermonkey).
3. In Violentmonkey: + → New script → paste `no-for-you.user.js` → save.
4. Open https://x.com in Firefox, log in, then menu → *Add to Home screen* to get an app-like icon.

Untested: X's markup changes, so the tab match (`role="tab"` with text "For you") may need adjusting.
