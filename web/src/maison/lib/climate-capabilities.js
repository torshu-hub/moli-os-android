// Point contracts, shared by thermostat and heat-pump cards. No provider detection.
export function climateCapabilities(device) {
  const points = device?.points ?? [];
  const point = (key) => points.find((p) => p.key === key);
  const target = point('target_temperature');
  const mode = point('home_mode')?.access?.write ? point('home_mode') : point('mode');
  return {
    modes: mode?.kind?.values ?? ['froid', 'chaud', 'auto', 'déshumidification', 'ventilation'],
    min: target?.kind?.min ?? 16,
    max: target?.kind?.max ?? 30,
    step: target?.kind?.step ?? 0.5,
    timed: !!point('setpoint')?.access?.write,
    resume: !!point('resume')?.access?.write,
    program: point('program')?.access?.write ? point('program') : null,
    mode: mode?.access?.write ? mode : null,
    power: !!point('on')?.access?.write,
  };
}
export function temporarySetpoint(temperature, duration, min, max) {
  const t = Number(temperature), minutes = Number(duration);
  if (!Number.isFinite(t) || t < min || t > max || !Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return null;
  return JSON.stringify({ temperature: t, duration_minutes: minutes });
}
