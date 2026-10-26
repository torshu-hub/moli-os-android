# Meross MSS310 — local, read-only by default

The canonical profile remains read-only. An explicit, separately selected built-in
variant `meross-mss310-manual` appends `manual-write.toml` to the same profile;
it supports one channel-0 ToggleX command through the original hub, with strict
identity/ability/ACK checks and readback. Android uses independent local authorization for each plug. Critical-use
equipment also requires explicit acknowledgement of its physical effect. Existing
installations remain read-only unless already authorized.
See [manual beta and trial procedure](../../docs/components/meross-manual-beta.md).
The [simulation](../../docs/components/meross-set-simulation.md) stays loopback-only
for external profiles. No native driver, new package format or cloud login.

The narrow Rust response adapter reuses `moli-profile`'s signed Meross transport,
typed points, supervisor, encrypted vault and history. No automatic heating,
Timer/Countdown/routine operations, or cloud key retrieval. Distinct from
EM06/ElectricityX. Existing Meross settings remain untouched; offline countdown
behavior is not qualified.

Protocol references (knowledge only; no upstream code copied):
- [meross_lan at bafedb4](https://github.com/krahabb/meross_lan/tree/bafedb4f8563153f9e3494292073551bb28f5f9a),
  `custom_components/meross_lan/devices/mss.py` (`ElectricitySensor`, `ConsumptionXSensor`),
  `sensor.py` (`MLSignalStrengthSensor`), and anonymized MSS310 protocol traces.
- `Appliance.System.All`: hardware UUID/type/version and digest ToggleX channel 0.
- `Appliance.Control.Electricity`: power / 1000 W, current / 1000 A, voltage / 10 V.
- `Appliance.Control.ConsumptionX`: unsorted daily entries `{date,time,value}`, Wh.
- `Appliance.System.Runtime`: signal percentage, not dBm.

All/state and electricity poll every 30 s; signal 60 s; consumption 5 min. Missing
measurements clear to unknown. Per-field timestamps expose failures or stale values.
Identity uses the device UUID plus the configured stable instance ID, not its IP/name.
Only an MSS310-reported model is accepted; no claim of hardware 6.0.0 validation yet.

Consumption is the valid entry with the latest device timestamp, shown with its date;
it is neither summed across days nor called a lifetime index or guaranteed "today".
Known firmware reset/offset/timezone quirks handled by meross_lan are **not** corrected
here. No power integration, synthetic energy estimate or parallel history store is added.

The four fixtures are entirely synthetic, with invented UUID, dates and measurements;
no keys, accounts, MAC addresses or actual household captures. `moli_latest` is the
adapter-derived view included in the consumption fixture for catalogue replay, not a
field returned by a physical plug. Runtime always overwrites it from raw consumptionx.
Tests cover conversion, missing values, independent plugs, rejected key, connection
loss, restart and parameter/key persistence. These do not prove physical compatibility.
