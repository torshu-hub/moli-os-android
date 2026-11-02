# Bluelink / myHyundai Europe

Android Management → Integrations → Bluelink / myHyundai. Select the same Europe
mode as in Home Assistant: Europe / legacy CCAPI (region 1, offered first for
qualification on a 2022 Ioniq 5), or Europe CCI/GSPA (region 9). This changes the
vehicle transport, not the account region. Existing code21 accounts keep GSPA.

“Continue with Hyundai” opens only the Hyundai IDP/OneApp HTTPS pages in a
screenshot-protected view without a JavaScript bridge, file access or third-party
cookies. The owner enters credentials, accepts provider terms or completes a
verification only if Hyundai actually requests it. The one-use redirect must
match the expected HTTPS callback and random state, with a ten-minute limit.
The optional vehicle PIN is entered separately and stays in the encrypted vault.
No code, cookie, password or redirect URL is included in diagnostic output.

Direct sign-in remains available. It encrypts the password with Hyundai's public
RSA key and does not save it. A local checkbox is no longer required for login:
the explicit connection button requests authorization. Identity requests have a
35-second bound (120-second total for direct login), without automatic retries.
A terms page is distinguished from an incomplete or untrusted redirect, and
network/TLS/HTTP, CCI/CCS and fleet failures have separate safe phases. If
credentials were authorized but fleet discovery failed, resume discovery using
the saved authorization rather than entering the password again. Do not repeat
logout/login in the official app merely because Moli reports a generic failure.

After authorization, vehicle/control/synchronization options open directly.
Commands and location start disabled and are independently chosen by the owner.
Compatible controls require a voluntary action in Moli; sensitive controls have
an explicit confirmation, while ordinary parameter submission is already an
explicit action. There are no automatic vehicle commands or automation orders.
Force refresh is separate from cached reads, with a durable cooldown and 12V guard.
A cloud acknowledgement does not confirm a physical outcome.

Both APIs use ordinary Moli Hub points and the existing journal. The default
interval is 15 minutes, reading stored cloud data. Legacy driving history uses a
passive POST query, never a control/wakeup endpoint. Supported controls depend
on the chosen API, protocol and reported equipment. Legacy CCS2 charge/climate
scheduling uses separate controls so no second order runs after Moli's deadline.
Navigation is supported by legacy EU via a private short-lived destination and
an opaque one-use Hub token; coordinates/name never enter journal/history/export.
Stored SVM is available only on GSPA. Missing functions retain their reasons.

Daily summaries in history.db survive ordinary 30-day raw retention. Vehicle Wh,
grid kWh, regeneration, documented costs and estimates remain distinct. Bind
only a known existing weather point and an explicitly dedicated EV meter;
whole-home Linky and ordinary Meross sockets are never assigned automatically.
No Raspberry Pi database is imported. Real account/vehicle behavior and renewal
still need voluntary qualification on the owner's S5e. Tests are synthetic/offline.
