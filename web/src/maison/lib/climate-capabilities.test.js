import { test } from 'node:test';
import assert from 'node:assert/strict';
import { climateCapabilities, temporarySetpoint } from './climate-capabilities.js';
test('thermostat controls follow capabilities without advertising heat-pump modes or power', () => {
  const caps = climateCapabilities({ points: [
    { key: 'target_temperature', kind: { type: 'numeric', min: 7, max: 30, step: 0.5 }, access: { write: false } },
    { key: 'setpoint', access: { write: true } },
    { key: 'home_mode', kind: { type: 'enum', values: ['schedule', 'away', 'hg'] }, access: { write: true } },
  ] });
  assert.equal(caps.min, 7);assert.equal(caps.timed, true);assert.equal(caps.power, false);
  assert.deepEqual(caps.modes, ['schedule', 'away', 'hg']);
  assert.equal(temporarySetpoint(7, 90, caps.min, caps.max), '{"temperature":7,"duration_minutes":90}');
  assert.equal(temporarySetpoint(21, undefined, caps.min, caps.max), null);
  assert.equal(temporarySetpoint(31, 30, caps.min, caps.max), null);
});
test('existing heat-pump controls retain their ranges and protocol modes', () => {
  const caps = climateCapabilities({ points: [{key:'on', access:{write:true}}] });
  assert.equal(caps.min,16);assert.equal(caps.power,true);assert.equal(caps.timed,false);
  assert.deepEqual(caps.modes,['froid','chaud','auto','déshumidification','ventilation']);
});
