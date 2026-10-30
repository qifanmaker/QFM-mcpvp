'use strict';

// Reason: Monster Shoot switches from four cardinal ledges to the source
// radius12 circle at five players. Verify that branch on an actual Fabric
// server, joining all five atomically before the queue's next matching tick.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const path = require('node:path');
const IDS = ['A', 'B', 'C', 'D', 'E'];
const probe = new PartyProbe({ port: 25616, runDir: path.join(__dirname, '../build/mygo-party2-monster-crowd') });
const position = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
const wrap = value => ((value + 180) % 360 + 360) % 360 - 180;
async function condition(value) {
  const token = `P2_MS_CROWD_${++probe.sequence}`;
  await probe.console(`execute ${value} run say ${token}`);
  return probe.serverLines.some(row => row.text.includes(`[Server] ${token}`));
}
async function run() {
  await probe.launch();
  for (const id of IDS) {
    const bot = probe.connect(id);
    bot._client.on('position', packet => { bot.crowdYaw = packet.yaw; });
  }
  await probe.wait(() => IDS.every(id => probe.bots[id].probeSpawned), 'five clients spawned', 30000);
  probe.game = 'monster';
  const start = Date.now();
  for (const id of IDS) await probe.console(`tag MyGoCheck${id} add p2_ms_crowd_join`);
  probe.log('fixtureNote', { description: 'One execute-as command invokes each real join handler before the server next tries to match the queue.' });
  await probe.console('execute as @a[tag=p2_ms_crowd_join] run pvp join minecraftparty');
  await probe.wait(() => {
    if (probe.voteError) throw probe.voteError;
    return probe.chats.some(row => row.id === 'A' && row.at >= start && /轮选择：.*原版编号\s+403/.test(row.text));
  }, 'five players vote Monster Shoot', 90000);
  const selectedAt = Date.now();
  IDS.forEach(id => { probe.bots[id].probeStartedAt = 0; });
  await probe.wait(() => IDS.every(id => probe.bots[id].probeStartedAt >= selectedAt), 'five-player Monster Shoot enters PLAY', 45000);
  probe.game = null;
  const points = IDS.map(id => position(probe.bots[id]));
  const meanX = points.reduce((sum, point) => sum + point.x, 0) / 5;
  // ArenaTemplate's Party region is176wide and384apart. This independently
  // fixes the source anchor, exposing the source circle's one-block Z offset.
  const center = new Vec3(Math.round((meanX - 88.5) / 384) * 384 + 88.5, 101, 87.5);
  const expected = IDS.map((_, seat) => {
    const angle = seat * 72 * Math.PI / 180;
    return center.offset(-12 * Math.sin(angle), 0, 12 * Math.cos(angle));
  });
  probe.check('Five-player branch places five distinct players on original radius12 circle', new Set(points.map(point => point.toString())).size === 5
    && expected.every(target => points.some(point => point.distanceTo(target) < .003)), { points, center, expected });
  for (const id of IDS) {
    const bot = probe.bots[id], point = position(bot);
    const yaw = Math.atan2(-(center.x - point.x), center.z - point.z) * 180 / Math.PI;
    probe.check(`${id} faces the original circle center`, Math.abs(wrap(bot.crowdYaw - yaw)) < .03,
      { yaw: bot.crowdYaw, expected: yaw, point });
    probe.check(`${id} receives playable source weapons at five players`, bot.probeWorld === 'pvp:arena' && bot.game.gameMode === 'adventure'
      && bot.inventory.items().some(item => item.name === 'bow') && bot.inventory.items().some(item => item.name === 'arrow'), probe.snapshot(id));
  }
  const since = probe.serverLines.length;
  probe.rawConsole('tick sprint 100');
  await probe.wait(() => probe.serverLines.slice(since).some(row => /Sprint completed/i.test(row.text)), 'advance five-player spawns', 30000);
  probe.check('Five-player source branch spawns real5HP targets', await condition('in pvp:arena if entity @e[tag=pvp_party_ms_monster,nbt={Health:5.0f}]'));
  await probe.leaveAll();
  probe.check('Cancelling five-player match removes owned targets', !await condition('in pvp:arena if entity @e[tag=pvp_party_ms_monster]'));
}
(async () => {
  let error;
  try { await run(); } catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error); if (!report.ok) process.exitCode = 1;
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
