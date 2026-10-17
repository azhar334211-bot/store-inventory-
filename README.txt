HOTEL STORE INVENTORY — ANDROID PROJECT

This is a native Android project (Kotlin + SQLite) intended to build an installable APK.
It is offline-first and includes item records, stock in/out, automatic balance, history,
monthly quantity report, CSV export for Excel, and PDF report export.

Important limitations:
- This ZIP is source code, not a compiled APK.
- A build environment is required to create the APK.
- The GitHub Actions workflow in .github/workflows/build-apk.yml can build it in the cloud:
  1. Create a GitHub repository and upload all files from this ZIP.
  2. Open the repository's Actions tab.
  3. Run "Build Android APK" (Run workflow), or push to main.
  4. Open the completed workflow run and download the HotelStoreInventory-APK artifact.
  5. Extract app-debug.apk and install it on your Android phone (allow installing unknown apps if prompted).

No internet permission is declared in the Android app; runtime stock data is stored in a local SQLite database.
The initial Android version does not yet include full JSON database backup/restore or XLSX workbook export; it provides CSV (opens in Excel) and PDF report export.
