'use strict';

// Reason: only real AI swings into a real Fabric player can demonstrate actual
// critical damage, equipment attributes and armor mitigation. The observer is a
// test-only snapshot entrypoint, and the client remains a stationary target.
const fs = require('node:fs');
const path = require('node:path');
const modules = process.env.PRACTICE_TEST_NODE_MODULES || '/tmp/pvp-practice-protocol/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const port = Number(process.env.PRACTICE_TEST_PORT || 25647);
const runDir = path.resolve(process.env.PRACTICE_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-practice-combat'));
const baseline = process.env.PRACTICE_COMBAT_BASELINE === '1';
if (port === 25565 || !runDir.startsWith(path.resolve(__dirname, '../build') + path.sep)) throw new Error('Use an isolated test runtime.');
const eventFile = path.join(runDir, 'practice-combat-events.jsonl');
const rows = () => fs.existsSync(eventFile) ? fs.readFileSync(eventFile, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const packets = [], actions = [], checks = [];
const arrows = [];
let client;
const log = (event, data = {}) => process.stdout.write(JSON.stringify({ event, ...data }) + '\n');
function check(name, pass, actual) {
  checks.push({ name, pass: !!pass, actual }); log('check', checks.at(-1));
  if (!pass) throw new Error(name);
}
async function waitFor(predicate, label, timeout = 20000) {
  const end = Date.now() + timeout;
  while (!predicate()) { if (Date.now() > end) throw new Error(`Timeout: ${label}`); await sleep(100); }
}
function connect() {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'MyGoCheckA', version: '1.21.1', auth: 'offline' });
  let authoritative;
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (authoritative && (name === 'position' || name === 'position_look')) {
      if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
      return name === 'position_look' ? write('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true })
        : write('flying', { onGround: true });
    }
    return write(name, packet);
  };
  bot._client.prependListener('position', packet => {
    const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object' ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  bot.on('spawn', () => { bot.physicsEnabled = false; bot.probeReady = true; });
  bot.on('messagestr', (text, position) => { if (position === 'game_info') actions.push(text); else log('chat', { text }); });
  bot.on('entitySpawn', entity => { if (entity.name === 'arrow') arrows.push(entity.id); });
  bot._client.on('animation', packet => packets.push({ kind: 'animation', ...packet }));
  bot._client.on('sound_effect', packet => {
    const id = packet.sound?.soundId;
    const name = packet.sound?.data?.soundName || bot.registry.sounds?.[id]?.name || '';
    if (name.includes('attack')) packets.push({ kind: 'sound', soundId: id, clientMappedName: name });
  });
  bot.on('error', error => log('clientError', { error: error.message }));
  return bot;
}
async function main() {
  client = connect();
  await waitFor(() => client.probeReady, 'login');
  client.chat('/pvp practice pvp expert iron_pvp');
  await waitFor(() => Object.values(client.entities).some(e => e.username === 'PvP_Bot_L5'), 'humanoid');
  const npc = Object.values(client.entities).find(e => e.username === 'PvP_Bot_L5');
  // Remove ranged/tool choices to isolate sword combat while retaining the
  // ordinary iron PvP armor and the actual production melee decision path.
  for (const slot of [0, 2, 5]) { client.chat(`/item replace entity ${npc.uuid} hotbar.${slot} with minecraft:air`); await sleep(120); }
  const start = Date.now();
  await waitFor(() => rows().filter(row => row.event === 'hit' && row.healthLoss > 0).length >= 24,
    '24 actual AI sword hits', 60000);
  const events = rows(), hits = events.filter(row => row.event === 'hit' && row.healthLoss > 0);
  const criticals = hits.filter(row => row.base > 8 && !row.ground && row.fallDistance > 0 && !row.sprinting);
  const normals = hits.filter(row => row.base <= 6.01 && row.attackDamageAttribute === 6);
  check('natural AI combat collected sufficient damage samples', hits.length >= 24, hits.length);
  check('iron armor remains equipped', hits.every(row => row.armor === 15 && row.toughness === 0));
  if (baseline) {
    check('baseline reproduces missing vanilla critical damage', criticals.length === 0, { criticals: criticals.length, advertisedCritical: actions.filter(row => row.includes('跳跃暴击')).length });
    check('baseline sword hits reproduce cooldown-reset weak damage', hits.every(row => Math.abs(row.base - 1.20768) < 0.0001 && Math.abs(row.healthLoss - 0.51224136) < 0.001), hits.map(row => ({ base: row.base, healthLoss: row.healthLoss })));
  } else {
    check('every sword hit uses synchronized sword attack attributes', hits.every(row => row.weapon === 'minecraft:iron_sword' && row.attackDamageAttribute === 6), hits.map(row => ({ weapon: row.weapon, damage: row.attackDamageAttribute })));
    check('AI produces real airborne falling vanilla criticals', criticals.length > 0, criticals);
    check('critical iron sword uses vanilla 1.5x raw damage', criticals.some(row => Math.abs(row.base - 9) < 0.001), criticals.map(row => row.base));
    check('critical sword damage applies normal iron armor mitigation', criticals.some(row => Math.abs(row.healthLoss - 5.22) < 0.001), criticals.map(row => row.healthLoss));
    check('ordinary sword damage applies normal iron armor mitigation', normals.length > 0 && normals.every(row => Math.abs(row.base - 6) < 0.001 && Math.abs(row.healthLoss - 3.12) < 0.001), normals.map(row => ({ base: row.base, healthLoss: row.healthLoss })));
    check('all sword hits are fully charged normal or critical damage', hits.every(row => Math.abs(row.base - 6) < 0.001 || Math.abs(row.base - 9) < 0.001), hits.map(row => row.base));
    check('real critical hit animation reaches the vanilla client', packets.some(packet => packet.kind === 'animation' && packet.animation === 4 && packet.entityId === client.entity.id));

    // Restore the real iron-kit bow, make the AI use it at range, then approach.
    // The first subsequent sword swing must wait for sword attributes/cooldown,
    // rather than inheriting the bow's bare-hand attack attributes in that tick.
    const arrowStart = arrows.length;
    client.chat(`/item replace entity ${npc.uuid} hotbar.2 with minecraft:bow`);
    await sleep(200);
    const region = Math.floor(npc.position.x / 384) * 384;
    const farX = npc.position.x < region + 25 ? region + 37.5 : region + 13.5;
    client.chat(`/tp @s ${farX} 101 25.5`);
    await waitFor(() => arrows.length > arrowStart, 'AI releases real bow before switching', 15000);
    const closeX = npc.position.x < region + 25 ? npc.position.x + 2.7 : npc.position.x - 2.7;
    const swapStart = rows().length;
    client.chat(`/tp @s ${closeX} 101 ${npc.position.z}`);
    await waitFor(() => rows().slice(swapStart).some(row => row.event === 'hit' && row.directMelee && row.healthLoss > 0), 'melee after bow swap', 15000);
    const swapped = rows().slice(swapStart).find(row => row.event === 'hit' && row.directMelee && row.healthLoss > 0);
    check('bow to sword first melee uses sword attributes and full charge', swapped.weapon === 'minecraft:iron_sword'
      && swapped.attackDamageAttribute === 6 && (Math.abs(swapped.base - 6) < 0.001 || Math.abs(swapped.base - 9) < 0.001), swapped);
  }
  log('complete', { baseline, elapsedMillis: Date.now() - start, hitCount: hits.length, criticalCount: criticals.length,
    rawDamage: [...new Set(hits.map(row => row.base))], healthLoss: [...new Set(hits.map(row => row.healthLoss))] });
}
main().catch(error => { process.exitCode = 1; log('failure', { error: error.stack }); }).finally(async () => {
  fs.writeFileSync(path.join(runDir, 'practice-combat-results.json'), JSON.stringify({ baseline, checks, events: rows(), actions, packets, arrows }, null, 2));
  if (client) { client.chat('/pvp practice leave'); await sleep(250); client.chat('/stop'); await sleep(300); client.quit(); }
  process.exit(process.exitCode || 0);
});
