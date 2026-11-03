# Enrolling a new phone (before handing it to the customer)

Uninstall protection, factory-reset blocking and restricted mode work **only** when this app is the
**Device Owner** of the phone. A normally installed APK gets none of these protections: the app shows
"This phone is NOT enrolled as a managed device" in that case and the admin panel will refuse to restrict it.

Device Owner can only be set on a phone that has just been factory reset (setup wizard, no Google account yet).
Tell the customer in writing, before the sale, that the phone is managed, what the rules are (see
"Automatic restriction") and how it is released. Keep their signed acknowledgement.

## One-time server setup
1. Push this repo; GitHub Actions builds `installment-customer-app-apk`. Download the APK and host it on **HTTPS**
   (any static file host you control).
2. The workflow summary prints the signing-certificate checksum. In the admin panel's `appsettings`
   (section `Provisioning`) set `ApkUrl`, `ApkSignatureChecksum`, `ApiBaseUrl` (public HTTPS address of the API).
3. Use the same release keystore (GitHub secret `RELEASE_KEYSTORE_B64`) for every build; a different key changes
   the checksum and old QR codes stop working.

## Method A - QR code (no computer needed, Android 8+; the app's minimum is Android 8.0)
1. Admin panel -> Customers: add the customer. Devices: register the phone (IMEI, serial) and assign the customer.
2. Device page -> **Generate enrollment code / QR** (the code is shown once and expires in 24 h).
3. Factory-reset the phone. On the first welcome screen tap the same spot **6 times** until the QR scanner opens.
4. Connect to Wi-Fi when asked, scan the QR. Android downloads the app, verifies the checksum and makes it Device Owner.
5. The app registers itself with the one-time code. The device page changes to *Enrolled / Device Owner*.
6. Create the installment contract (tick *Automatic restriction* if agreed). Hand over the phone.

## Method B - adb (technician bench, Android 8+)
Use when a manufacturer blocks the QR flow. The phone must be freshly reset, with **no accounts** added.
```
adb install installment-customer-app.apk
adb shell dpm set-device-owner com.installment.customerapp/.admin.InstallmentDeviceAdminReceiver
```
Open the app, type the server address and the enrollment code from the admin panel, press **Enroll this phone**.
Then turn USB debugging off (the app also blocks it as a device-owner policy).

## What the app enforces once enrolled
- Cannot be uninstalled from Settings (`setUninstallBlocked`), cannot be force-stopped / data-cleared
  (`setUserControlDisabledPackages`, Android 11+; `DISALLOW_APPS_CONTROL` before).
- Factory reset from Settings, USB debugging, safe boot, adding users, network reset and date/time changes are blocked
  (user restrictions). Device Owner status itself cannot be removed from the phone's Settings.
- A persistent notification states that the phone is managed under an installment agreement.
- After reboot the app restores its schedules and, if restricted, the restricted screen.

## Automatic restriction (works offline)
The server sends each phone a deadline = end of the grace period of the oldest unpaid installment. The phone checks it
every 15 minutes, at the deadline (alarm) and at boot **without internet**. When the deadline passes it restricts itself
and **stays restricted until an admin restores it in the admin panel** (paying is not enough on its own). The clock is
protected: server-anchored monotonic time, never going backwards, and date/time settings are blocked.
Restore -> choose how many days automatic restriction pauses, otherwise an unpaid phone would lock again immediately.
Emergency calls and calling the shop always work from the restricted screen.
