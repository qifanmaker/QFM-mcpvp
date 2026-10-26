'use strict';

// Reason: reproduce stale SkyWars drops through real MatchManager lifecycle,
// including player departure, deferred cleanup and reusing the same region.
// Pre-review: run only against an isolated server; hold just the probe chunks
// while querying, release them in finally, and quit both clients on every exit.
// Resource packs are downloaded/hash-checked but HEADLESS reports are not a
// visual, audio or first-person control acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const modules = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-skywars-matches'));
const port = Number(process.env.MYGO_TEST_PORT || 25627);
const projectBuild = path.resolve(__dirname, '../build') + path.sep;
if (port === 25565 || !Number.isInteger(port) || port < 1024 || port > 65535
    || !runDir.startsWith(projectBuild) || !path.basename(runDir).includes('mygo')) {
  throw new Error('Use a mygo-named directory inside build/ and an isolated port, never 25565.');
}
fs.mkdirSync(runDir, { recursive: true });
const evidence = path.join(runDir, 'skywars-match-results.jsonl');
const resultFile = path.join(runDir, 'skywars-match-verification.json');
const serverLog = path.join(runDir, 'server-console.log');
const bots = {}, chats = [], checks = [], packs = {}, rounds = [], forced = new Set();
let sequence = 0;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function emit(event, details = {}) {
  const line = JSON.stringify({ time: new Date().toISOString(), event, ...details });
  fs.appendFileSync(evidence, line + '\n');
  process.stdout.write(line + '\n');
}
function check(name, ok, actual, expected) {
  const row = { name, pass: !!ok, actual, expected };
  checks.push(row); emit('assertion', row);
  if (!ok) throw new Error(`Assertion failed: ${name}`);
}
async function waitFor(predicate, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timed out: ${label}`);
    await sleep(100);
  }
}
function logText() { return fs.existsSync(serverLog) ? fs.readFileSync(serverLog, 'utf8') : ''; }
async function command(id, text, delay = 180) {
  emit('command', { id, text });
  bots[id].chat(text);
  await sleep(delay);
}
async function resourcePack(bot, packet) {
  const id = bot.probeId;
  try {
    const url = new URL(packet.url);
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
      throw new Error('This regression fixture requires a local resource-pack URL.');
    }
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 3 });
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Resource download: HTTP ${response.status}`);
    const body = Buffer.from(await response.arrayBuffer());
    const sha1 = crypto.createHash('sha1').update(body).digest('hex');
    check(`${id}: local resource-pack SHA-1`, sha1 === packet.hash, sha1, packet.hash);
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 4 });
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 0 });
    packs[id] = { sha1, bytes: body.length, headless: true, rendered: false };
    emit('packReady', { id, ...packs[id] });
  } catch (error) {
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 2 });
    emit('packFailure', { id, error: error.stack });
  }
}
function connect(id) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: `MyGoCheck${id}`,
    version: '1.21.1', auth: 'offline', hideErrors: false });
  bot.probeId = id;
  bots[id] = bot;
  let authoritative;
  const originalWrite = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    // The fixture stands still. Do not let a delayed Mineflayer respawn reply
    // overwrite the latest authoritative teleport into or out of a match.
    if (authoritative && (name === 'position' || name === 'position_look')) {
      if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
      return name === 'position_look'
        ? originalWrite('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true })
        : originalWrite('flying', { onGround: true });
    }
    return originalWrite(name, packet);
  };
  bot._client.prependListener('position', packet => {
    const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object'
      ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
    bot.probePosition = authoritative;
  });
  const world = packet => {
    const info = packet.worldState || packet;
    bot.probeWorld = info.name ?? info.worldName ?? packet.worldName;
    emit('world', { id, world: bot.probeWorld });
  };
  bot._client.prependListener('login', world);
  bot._client.prependListener('respawn', world);
  bot.on('spawn', () => { bot.physicsEnabled = false; bot.probeSpawned = true; });
  bot.on('messagestr', (message, position) => {
    chats.push({ id, message, at: Date.now() });
    if (position !== 'game_info') emit('chat', { id, message });
  });
  bot._client.on('add_resource_pack', packet => void resourcePack(bot, packet));
  bot._client.on('remove_resource_pack', () => { delete packs[id]; });
  bot.on('error', error => emit('clientError', { id, error: error.stack }));
  bot.on('kicked', reason => emit('kicked', { id, reason }));
  bot.on('end', reason => emit('clientEnd', { id, reason }));
}
async function query(selector, expectedPresent, name) {
  const token = `SW_PROBE_${++sequence}`;
  const start = chats.length;
  await command('A', `/execute in pvp:arena if entity ${selector} run tellraw MyGoCheckA {"text":"${token}_present"}`);
  await command('A', `/execute in pvp:arena unless entity ${selector} run tellraw MyGoCheckA {"text":"${token}_absent"}`);
  await waitFor(() => chats.slice(start).some(row => row.id === 'A' && row.message.startsWith(token)), name);
  const found = chats.slice(start).find(row => row.id === 'A' && row.message.startsWith(token)).message;
  check(name, found === `${token}_${expectedPresent ? 'present' : 'absent'}`, found,
    `${token}_${expectedPresent ? 'present' : 'absent'}`);
}
async function queryRetiredRound(round) {
  const key = `${round.center.x} ${round.center.z}`;
  await command('A', `/execute in pvp:arena run forceload add ${key}`);
  forced.add(key);
  await sleep(1800);
  await query(`@e[type=minecraft:item,tag=${round.tag}]`, false, `${round.mode}: old dropped item absent after chunk reload`);
  await query('@e[type=minecraft:item,nbt={Item:{id:"minecraft:nether_star"}}]', false,
    `${round.mode}: cleared chest did not spill its sentinel inventory`);
  await command('A', `/execute in pvp:arena run forceload remove ${key}`);
  forced.delete(key);
}
async function runRound(mode, previous) {
  const start = chats.length;
  const beforeLog = logText().length;
  for (const id of ['A', 'B']) await command(id, `/pvp join ${mode}`);
  if (mode === 'skywarsguns') {
    await waitFor(() => packs.A && packs.B, 'both local resource packs', 30000);
    await sleep(500);
    for (const id of ['A', 'B']) {
      if (!chats.slice(start).some(row => row.id === id && row.message.includes('已加入枪械空岛'))) {
        await command(id, `/pvp join ${mode}`);
      }
    }
  }
  const display = mode === 'skywars' ? '空岛战争' : '枪械空岛';
  await waitFor(() => ['A', 'B'].every(id => chats.slice(start).some(row =>
    row.id === id && row.message.includes(`已加入${display}`))), `${mode}: both queued`);
  // The existing command uses StringArgumentType.word(), which accepts the
  // enum spelling but rejects non-ASCII display names such as 主世界.
  await command('A', '/pvp start OVERWORLD');
  await waitFor(() => ['A', 'B'].every(id => chats.slice(start).some(row =>
    row.id === id && row.message.includes('战斗开始'))), `${mode}: ACTIVE`, 60000);
  for (const id of ['A', 'B']) if (bots[id].currentWindow) bots[id].closeWindow(bots[id].currentWindow);
  check(`${mode}: both participants entered arena`, ['A', 'B'].every(id => bots[id].probeWorld === 'pvp:arena'),
    ['A', 'B'].map(id => bots[id].probeWorld), ['pvp:arena', 'pvp:arena']);
  const a = bots.A.probePosition, b = bots.B.probePosition;
  // Two spawn islands are opposite each other; their midpoint is the region center.
  const center = { x: Math.floor((a.x + b.x) / 2), z: Math.floor((a.z + b.z) / 2) };
  const round = { mode, tag: `skywars_regression_${mode}`, center, spawns: [a, b] };
  rounds.push(round);
  emit('roundStarted', round);
  if (previous) {
    check('next match reuses cleaned region', center.x === previous.center.x && center.z === previous.center.z,
      center, previous.center);
    await queryRetiredRound(previous);
  }
  await command('A', `/execute in pvp:arena run summon minecraft:item ${center.x} 180 ${center.z} {NoGravity:1b,Age:-32768s,PickupDelay:32767s,Tags:["${round.tag}"],Item:{id:"minecraft:gold_nugget",count:1}}`);
  await query(`@e[type=minecraft:item,tag=${round.tag}]`, true, `${mode}: sentinel drop created during active match`);
  await command('A', `/execute in pvp:arena run setblock ${center.x} 181 ${center.z} minecraft:chest`);
  await command('A', `/execute in pvp:arena run item replace block ${center.x} 181 ${center.z} container.0 with minecraft:nether_star 7`);
  await command('B', '/hub');
  await waitFor(() => chats.slice(start).some(row => row.id === 'A' && row.message.includes('你赢了')), `${mode}: match result`, 15000);
  await waitFor(() => ['A', 'B'].every(id => bots[id].probeWorld !== 'pvp:arena'), `${mode}: restored to lobby`, 15000);
  await waitFor(() => /比赛 #\d+ 已结束并清理/.test(logText().slice(beforeLog)), `${mode}: deferred cleanup`, 60000);
  const cleanup = logText().slice(beforeLog).match(/比赛 #(\d+) 已结束并清理/);
  round.matchId = Number(cleanup[1]);
  check(`${mode}: MatchManager completed deferred cleanup`, true, round.matchId, 'completed match id');
  await queryRetiredRound(round);
  return round;
}
async function main() {
  let error;
  try {
    connect('A'); connect('B');
    await waitFor(() => bots.A.probeSpawned && bots.B.probeSpawned, 'client login', 30000);
    await sleep(700);
    const first = await runRound('skywars');
    await runRound('skywarsguns', first);
  } catch (caught) {
    error = caught;
    emit('failure', { error: caught.stack });
  } finally {
    for (const key of forced) {
      try { await command('A', `/execute in pvp:arena run forceload remove ${key}`); } catch {}
    }
    for (const id of ['A', 'B']) {
      if (!bots[id]) continue;
      try { await command(id, '/hub'); bots[id].quit('SkyWars lifecycle regression complete'); } catch {}
    }
    const result = { passed: !error, assertions: checks.length,
      failed: checks.filter(row => !row.pass).length, checks, rounds,
      headless: true, visualAcceptance: false, error: error?.stack };
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2) + '\n');
    emit('complete', { passed: result.passed, assertions: result.assertions, resultFile });
    await sleep(400);
    process.exit(error ? 1 : 0);
  }
}
void main();
