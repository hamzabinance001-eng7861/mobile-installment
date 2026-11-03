# Releasing a phone from management

Use when the contract is fully paid (status *Completed*) or the phone is legitimately released.

1. Admin panel -> Devices -> open the device -> **Release device**. Enter the reason (e.g. "Contract completed").
   - Admin role: allowed when the contract is Completed or there is no active contract.
   - Releasing early (balance still open) needs a **SuperAdmin**.
2. The command is queued, pushed with FCM, and fetched by the phone. If the phone is offline the command waits
   (it expires after the configured number of hours; create it again if it expires).
3. The phone reports success to the server first (the server revokes the device token), then:
   removes all restrictions and the restricted screen, re-allows uninstall, and gives up Device Owner.
   The managed notification disappears; the customer can uninstall the app normally.
4. If the phone was offline when the token was revoked, its next server contact gets `401` and it completes the release itself.
5. Audit trail: `CommandRelease` (who/why), `CommandReleaseExecuted` (device confirmation) and the enrollment
   history row (released at / by / reason). Payment records are never deleted.

There is no customer-side or hidden way to leave management; the supported ways are this release, or a factory reset
done through the means the manufacturer provides (blocked by policy while managed, see COMPATIBILITY.md for limits).
