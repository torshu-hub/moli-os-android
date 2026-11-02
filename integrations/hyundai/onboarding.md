# Bluelink / myHyundai Europe

Android Management → Integrations → Bluelink / myHyundai. The owner enters their
European myHyundai account and optional four-digit vehicle PIN in private fields
on their own tablet. Rust performs CCI/OneApp authentication. Credentials and
rotating tokens remain in Moli's encrypted vault; the password is not saved.
CAPTCHA/consent changes require the owner to use myHyundai and reconnect here.
No legacy HA token import or Python/Home Assistant service is required.

Select the vehicles, the passive cache interval (minimum 15 minutes), optional
commands and optional location display. Both optional permissions start disabled.
Connecting/enabling/polling sends no remote order and never wakes the vehicle.
Remote orders require an explicit confirmed human action in Moli and the vehicle
PIN where Hyundai requires it. Their acknowledgement is a cloud receipt, not a
physical result. Force refresh has a durable 15-minute cooldown and refuses an
unknown/low 12V battery. Automations cannot execute vehicle orders.

The vehicle card in Moli provides readings, supported controls and the Ioniq 5
journal. Unsupported/missing capabilities retain their reason. Location and
stored SVM are optional human-only volatile views, excluded from history/export.
Daily driving statistics are archived in the existing history.db before normal
30-day retention removes raw samples. Missing days remain unknown. Vehicle Wh,
grid kWh, regeneration, documented charging costs and estimates stay distinct.
Bind only an existing Celsius weather point and an explicitly dedicated EV meter;
whole-home Linky and ordinary Meross sockets are never assigned automatically.
No previous Raspberry Pi database has been imported. Account/physical operation
requires voluntary qualification on the owner's S5e; CI uses synthetic data only.
