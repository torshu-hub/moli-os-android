import assert from 'node:assert/strict';
import { createServer } from 'vite';
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
try {
  const { default: Inventory } = await server.ssrLoadModule('/src/maison/ui/IntegrationInventory.svelte');
  const { render } = await server.ssrLoadModule('svelte/server');
  const { setLanguage } = await server.ssrLoadModule('/src/lib/i18n.svelte.js');
  const rows = [
    { id: 'hue', kind: 'hue', compiled: true, instances: [] },
    { id: 'meross-mss310', kind: 'profile', compiled: true, instances: [{ instance: 'synthetic', enabled: false, status: null }] },
    { id: 'host', kind: 'host', compiled: true, instances: [{ instance: 'worker', enabled: true, status: { state: 'running' } }] },
    { id: 'unknown', kind: 'unknown', compiled: false, instances: [] },
  ];
  for (const [language, words] of [
    ['fr', ['Non configurée', 'désactivée', 'Inconnu / non observé', 'En marche', 'Non documenté dans le moteur', 'limitées sur Android']],
    ['en', ['Not configured', 'disabled', 'Unknown / not observed', 'Running', 'Not documented in the engine', 'limited on Android']],
  ]) {
    await setLanguage(language);
    const html = render(Inventory, { props: { rows, platform: 'android' } }).body;
    for (const word of words) assert.ok(html.includes(word), `${language}: ${word}`);
    assert.ok(!/<(button|form|input)\b/.test(html));
    assert.ok(!html.includes('systeme.inventory.'));
  }
  const html = render(Inventory, { props: { rows: undefined, platform: 'other' } }).body;
  assert.ok(html.includes('Unknown / not observed'));
  console.log('Inventory rendering verified: FR/EN, missing/disabled/unconfigured, Android limits, no write controls.');
} finally {
  await server.close();
}
