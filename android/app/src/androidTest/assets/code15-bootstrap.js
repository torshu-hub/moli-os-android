// Offline transport for the actual production dashboard in Android WebView.
// No alternative card, driver, real API or production testing hook.
window.code15Commands = [];
const id = 'climate-test:room';
const numeric = { type: 'numeric', min: 7, max: 30, step: 0.5 };
const point = (key, kind, write = false) => ({ key, kind, access: { read: true, write } });
const device = {
  id, native_name: 'Thermostat test', online: true, label: {},
  points: [point('temperature', numeric), point('target_temperature', numeric), point('setpoint', { type: 'text' }, true)],
  state: { temperature: { value: 19.5 }, target_temperature: { value: 20.5 } },
};
const snapshot = { devices: [device], drivers: [], approvals: [] };
window.EventSource = class extends EventTarget {
  constructor() {
    super();
    window.code15Source = this;
    setTimeout(() => this.dispatchEvent(new MessageEvent('snapshot', { data: JSON.stringify(snapshot) })), 0);
  }
  close() {}
};
window.code15Report = (value) => window.code15Source.dispatchEvent(new MessageEvent('event', {
  data: JSON.stringify({ type: 'state', point: `${id}/target_temperature`, value, ts: Date.now() }),
}));
window.fetch = async (path, options = {}) => {
  const url = new URL(path, location.href);
  if (options.method === 'POST') {
    if (url.pathname !== '/api/command') throw Error('Unexpected write');
    window.code15Commands.push(JSON.parse(options.body));
    return new Response('{}', { status: 200 });
  }
  const replies = {
    '/api/home': { favorites: { climate: [{ id, name: 'Thermostat test' }] } },
    '/api/session': { human: false, setup: false, language: 'fr' },
    '/api/health': { stats: {}, guard: { protected_rooms: [] } },
    '/api/bench': {}, '/api/journal': [], '/api/ambiances': [],
  };
  return new Response(JSON.stringify(replies[url.pathname] ?? {}), { status: url.pathname in replies ? 200 : 404 });
};
