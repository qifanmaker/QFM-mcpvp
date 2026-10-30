'use strict';

// Reason: a participant leaving during voting invalidates the four-player
// Platforms entry. Verify both the already-open screen and actual selection.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const path = require('node:path');
const probe = new PartyProbe({ port: 25608, runDir: path.join(__dirname, '../build/mygo-party2-eligibility') });
async function run() {
  await probe.launch(); await probe.connectAll();
  const start = Date.now();
  for (const id of ['A', 'B', 'C', 'D']) await probe.chat(id, '/pvp join minecraftparty');
  await probe.wait(() => ['A', 'B', 'C', 'D'].every(id => probe.menuChoices(probe.bots[id].currentWindow).some(item => item.source === 414)),
    'four-player voting menus include Platforms', 90000);
  const windows = Object.fromEntries(['A', 'B', 'C'].map(id => [id, probe.bots[id].currentWindow.id]));
  probe.check('Exactly four players may select original Platforms', true);
  await probe.chat('D', '/hub');
  await probe.wait(() => probe.bots.D.probeWorld === 'minecraft:overworld', 'D forfeits during voting');
  await probe.wait(() => ['A', 'B', 'C'].every(id => probe.bots[id].currentWindow?.id === windows[id]
    && probe.menuChoices(probe.bots[id].currentWindow).length === 10
    && !probe.menuChoices(probe.bots[id].currentWindow).some(item => item.source === 414)),
  'existing windows remove Platforms without reopening', 3000);
  probe.check('Three-player menus update in place and remove the four-player game', true,
    ['A', 'B', 'C'].map(id => ({ id, window: windows[id], choices: probe.menuChoices(probe.bots[id].currentWindow).map(item => item.source) })));
  for (const id of ['A', 'B', 'C']) await probe.vote(id, 'dropper');
  await probe.wait(() => probe.chats.some(row => row.id === 'A' && row.at >= start
    && /第\s+1\/5\s+轮选择：.*原版编号\s+401/.test(row.text)), 'three-player votes launch an eligible game', 30000);
  probe.check('Selection does not launch an ineligible Platforms round', !probe.chats.some(row => row.at >= start
    && /轮选择：.*原版编号\s+414/.test(row.text)));
  await probe.leaveAll();
}
(async () => {
  let error;
  try { await run(); } catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error); if (!report.ok) process.exitCode = 1;
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
