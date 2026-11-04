# Compatibility, limits and acceptance tests

## Honest limits
- Everything depends on **Device Owner**. A normal APK install: no uninstall protection, no restrictions.
- No root, exploits, FRP bypass or firmware changes are used. Flashing firmware, bootloader unlock, recovery-mode
  reset or OEM service tools can still defeat any app-level policy; this project does not claim otherwise.
- Android versions differ: `setUserControlDisabledPackages` needs 11+; `DISALLOW_CONFIG_DATE_TIME` needs 9+; some
  OEMs (Xiaomi/MIUI/HyperOS, Oppo/Realme ColorOS, Vivo, Huawei) restrict QR provisioning, battery background limits
  or the lock-task home replacement. Test every model you sell and record it below.
- Offline restriction is evaluated every 15 minutes, at the deadline alarm and at boot; battery savers on some OEMs may
  delay WorkManager. On those models, exclude the app from battery optimisation by hand during enrollment and test it.
- Restricted phones keep their existing Wi-Fi/mobile data so they can reach the server; there is no in-app Wi-Fi picker.

## Brand notes (Xiaomi, Redmi, Poco, Oppo, Realme, Vivo, Infinix/Tecno)
These are commonly reported behaviours, **not verified by this project** - confirm on a unit of each model.
- The app runs a small foreground service (shows the "managed" notice) and re-opens the restricted screen if the system
  closes it. Open **Phone setup check** in the app after enrolling: it shows what is still off and opens the right page.
- **Xiaomi / Redmi / Poco:** `dpm set-device-owner` may fail with a permission error until Developer options ->
  *USB debugging (Security settings)* is on (needs a Mi account + SIM/data). Also set Battery saver = *No restrictions*,
  turn on *Autostart*, and lock the app in Recents.
- **Oppo / Realme:** Developer options may have a *Disable permission monitoring* toggle needed for Device Owner.
  Allow *Auto-launch* and *Background activity* for the app.
- **Vivo:** similar to Oppo; allow *Autostart* and *High background power consumption*.
- **Infinix / Tecno (Transsion):** allow Auto-start and battery *No restrictions* (Phone Master).
- On all of them prefer the **adb** enrollment method; QR provisioning is more often blocked by the maker's setup wizard.
- After each setup change, run the acceptance tests below (especially the offline-lock and reboot tests).

## Test matrix (fill in per model)
| Model | Android | Enrol (QR/adb) | Uninstall blocked | Reset blocked | Reboot OK | Offline lock | Admin unlock | Release |
|-------|---------|----------------|-------------------|---------------|-----------|--------------|--------------|---------|
|       |         |                |                   |               |           |              |              |         |

## Acceptance tests
1. Settings -> Apps -> Installment: *Uninstall* is disabled; Force stop / Clear data disabled.
2. Settings: no way to remove device management; factory reset option blocked; USB debugging cannot be enabled.
3. Reboot: managed notification returns; if restricted, the restricted screen returns; WorkManager resumes.
4. Airplane mode -> reconnect: app syncs on its own; last sync time updates.
5. Offline auto-restrict: set a contract whose grace period has ended, enable airplane mode, wait <= 15 min:
   phone restricts. Turn data back on and pay: **still restricted**. Admin presses *Restore*: phone unlocks, auto-restrict
   paused for the chosen days.
6. Change the date to the past/future: blocked by policy; after reboot the app never uses a time earlier than it already saw.
7. Admin -> Release: phone leaves management; the audit log shows `CommandRelease` and `CommandReleaseExecuted`.
8. Emergency call and "Call the shop" work from the restricted screen.
