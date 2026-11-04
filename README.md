# Installment Customer App (Android, Kotlin)

Customer-side app of the mobile installment system. There is **no customer account or login**: the app shows shop
information, amount paid, remaining balance, next installment and overdue notices, receives Firebase reminders, shows
the restricted screen when authorised, and syncs with the backend using a per-device token.

Device protection (uninstall blocking, restrictions, restricted mode, release) uses Android's official
`DevicePolicyManager` and requires **Device Owner** enrollment. See `docs/`:
[ENROLLMENT](docs/ENROLLMENT.md) - [RELEASE](docs/RELEASE.md) - [COMPATIBILITY](docs/COMPATIBILITY.md).

## Build
GitHub Actions (`.github/workflows/android.yml`) builds the APK on every push (JDK 17, Gradle 8.9, AGP 8.7.3, SDK 35).
Download **installment-customer-app-apk** from the run's artifacts. Locally: `gradle assembleRelease` (Gradle 8.9+, JDK 17,
Android SDK 35) or open the folder in Android Studio.

### Repository secrets (all optional, never committed)
| Secret | Purpose |
|--------|---------|
| `RELEASE_KEYSTORE_B64` (base64 of a PKCS12 `.p12` file), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (same as the store password for PKCS12) | Release signing. Without them the APK is debug-signed (testing only). Keep the same key forever: provisioning QR codes pin its checksum. |
| `FCM_PROJECT_ID`, `FCM_APP_ID`, `FCM_API_KEY`, `FCM_SENDER_ID` | Firebase Cloud Messaging (free). Without them the app skips push and syncs every 15 minutes. |

## Layout
`admin/` DevicePolicyManager + provisioning - `work/` sync, offline enforcement, boot - `data/` API client, state, trusted clock -
`push/` FCM - `ui/` customer screen, restricted screen, notifications.
