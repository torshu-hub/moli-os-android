# Offline external-demand simulation

Run from the repository root:

```sh
python3 -m unittest discover -s android/tools/demand-simulation -v
```

This standalone design fixture mirrors an already computed boolean demand into an
in-memory virtual target. It does not calculate a thermostat setpoint, connect to
any service, import account data, or form device commands. It is not packaged in
the APK. Python is a development test tool, not an Android runtime dependency.

Inputs must distinguish device observation age from HTTP response receipt age.
A recent HTTP response does not prove fresh device data. An eventual connector
must reject partial errors, missing/unreachable devices and mismatched identities;
this fixture accepts normalized synthetic observations, not raw provider JSON.
Percentages, absent demand and integer booleans are rejected. Contradictions are
explicit synthetic inputs, not inferred from mains power (power is not proof of
heat production). Intervals in tests are arbitrary, not commissioning advice.

Invalid input produces a journal reason with no virtual transition. Retaining the
last *virtual* target is not a prescribed physical fail-safe. Neither permanent ON
nor OFF is universally safe. Restart requires explicit virtual reconciliation and
a new dwell interval; lost history cannot authorize an immediate transition.
A physical implementation would additionally need independently validated fault
policy, feedback and safety interlocks. This fixture provides none of those.
