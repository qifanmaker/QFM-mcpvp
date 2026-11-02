'use strict';

// Reason: server state alone cannot prove clients receive team colors, hidden
// names and both HUD updates. Observe actual Fabric 1.21.1 packets while real
// bot gunfights change alive counts; do not mutate HP, deaths or round state.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const modules = process.env.MYGO_PRACTICE_NODE_MODULES || '/tmp/pvp-practice-protocol/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const nbt = require(path.join(modules, 'prismarine-nbt'));
const port = Number(process.env.MYGO_PRACTICE_PORT || 25657);
const runDir = path.resolve(process.env.MYGO_PRACTICE_RUN_DIR || path.join(__dirname, '../build/mygo-hud-test'));
const buildRoot = path.resolve(__dirname, '../build') + path.sep;
if (port === 25565 || !Number.isInteger(port) || port < 1024 || port > 65535
    || !runDir.startsWith(buildRoot) || !path.basename(runDir).includes('mygo')) {
  throw new Error('Use an isolated mygo-named build/ directory and a test port other than25565.');
}
fs.mkdirSync(runDir, { recursive: true });
const checks = [], chats = [], observations = [], shots = [], clients = [], packLoads = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const emit = (event, data = {}) => process.stdout.write(JSON.stringify({ event, ...data }) + '\n');
function check(name, pass, actual) {
  const result = { name, pass: !!pass, actual };
  checks.push(result); emit('check', result);
  if (!pass) throw new Error(`Assertion failed: ${name}`);
}
async function waitFor(predicate, label, timeout = 30000) {
  const end = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > end) throw new Error(`Timeout: ${label}`);
    await sleep(100);
  }
}
async function pack(bot, packet) {
  try {
    const url = new URL(packet.url);
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
      throw new Error('This test only accepts the isolated server local HTTP resource pack.');
    }
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 3 });
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Resource pack HTTP${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const sha1 = crypto.createHash('sha1').update(bytes).digest('hex');
    check(`${bot.username} resource pack hash matches`, sha1 === packet.hash, { sha1, expected: packet.hash });
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 4 });
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 0 });
    bot.packReady = true;
    packLoads.push({ name: bot.username, sha1, bytes: bytes.length, rendered: false });
  } catch (error) {
    bot.packFailure = error.message;
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 2 });
    emit('packFailure', { name: bot.username, error: error.stack });
  }
}
function connect(letter) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, version: '1.21.1', auth: 'offline', username: `MyGoCheck${letter}` });
  clients.push(bot);
  captureHud(bot);
  let authoritative;
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (authoritative && (name === 'position' || name === 'position_look')) {
      if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
      return name === 'position_look'
        ? write('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true })
        : write('flying', { onGround: true });
    }
    return write(name, packet);
  };
  bot._client.prependListener('position', packet => {
    // Recoil packets contain only relative camera deltas. Preserve the latest
    // real teleport so the stationary probe cannot replay an old world position.
    const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object'
      ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  const world = packet => { const state = packet.worldState || packet; bot.testWorld = state.name ?? state.worldName ?? packet.worldName; };
  bot._client.on('login', world);
  bot._client.on('respawn', world);
  bot._client.on('add_resource_pack', packet => { void pack(bot, packet); });
  bot._client.on('remove_resource_pack', () => { bot.packReady = false; });
  bot.on('spawn', () => { bot.physicsEnabled = false; bot.testSpawned = true; });
  bot.on('messagestr', (message, position) => {
    chats.push({ name: bot.username, message, position, at: Date.now() });
    if (position !== 'game_info') emit('chat', { name: bot.username, message });
  });
  bot._client.on('sound_effect', packet => {
    const name = packet.sound?.data?.soundName || bot.registry.sounds?.[packet.sound?.soundId]?.name || '';
    if (name.startsWith('pvp:mygo.')) shots.push({ name, at: Date.now(), player: bot.username });
  });
  bot.on('error', error => emit('clientError', { name: bot.username, error: error.message }));
  bot.on('kicked', reason => emit('kicked', { name: bot.username, reason }));
  return bot;
}
async function command(bot, text, delay = 180) { bot.chat(text); await sleep(delay); }
async function message(bot, text, regex, timeout = 30000) {
  const index = chats.length;
  await command(bot, text);
  await waitFor(() => chats.slice(index).some(row => row.name === bot.username && regex.test(row.message)), text, timeout);
  return chats.slice(index).find(row => row.name === bot.username && regex.test(row.message)).message;
}
async function info(bot, actor) {
  const line = await message(bot, (actor ? `/execute as ${actor} run ` : '/') + 'pvp mygo practiceinfo', /MyGOPractice\s+\{/);
  const value = JSON.parse(line.slice(line.indexOf('{')));
  observations.push({ ...value, at: Date.now() });
  return value;
}
function verifyRoom(state, humans, label) {
  const roster = state.roster;
  check(`${label}: fixed ten actor seats`, state.total === 10 && roster.length === 10, state.total);
  check(`${label}: exactly five per current side`, roster.filter(row => row.team === 'T').length === 5
    && roster.filter(row => row.team === 'CT').length === 5, roster.map(row => row.team));
  check(`${label}: human replaces one bot`, state.humans === humans && state.bots === 10 - humans
    && roster.filter(row => row.bot).length === 10 - humans, { humans: state.humans, bots: state.bots });
  check(`${label}: all actor UUIDs unique`, new Set(roster.map(row => row.uuid)).size === 10);
}
async function ready(bot) {
  await waitFor(() => bot.testSpawned, `${bot.username}login`);
  if (!bot.packReady) {
    await command(bot, '/pvp mygo pack');
    await waitFor(() => bot.packReady || bot.packFailure, 'local resource pack download');
    if (bot.packFailure) throw new Error(bot.packFailure);
  }
}
async function join(bot, team, difficulty = 'normal', map = 'dust2') {
  await ready(bot);
  const before = chats.length;
  await command(bot, `/pvp practice mygo ${difficulty} ${team} ${map}`);
  await waitFor(() => bot.testWorld === 'pvp:arena', 'practice world teleport', 120000);
  // A correctly inherited dead seat has no inventory until the next round.
  await waitFor(() => chats.slice(before).some(row => row.name === bot.username && /已进入 MyGO 5v5 人机练习/.test(row.message)), 'practice seat admission', 30000);
}
async function leave(bot, text = '/pvp practice leave') {
  if (!bot || bot._client.state === 'disconnected') return;
  await command(bot, text);
  await waitFor(() => bot.testWorld === 'minecraft:overworld', `${text}returns lobby`);
}

function component(value) {
  if (!value) return '';
  if (value.type && value.value !== undefined) value = nbt.simplify(value);
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(component).join('');
  return (value.text || value.translate || '') + component(value.extra);
}
function unstyled(text) { return text.replace(/§[0-9a-fk-or]/gi, ''); }
function captureHud(bot) {
  bot.hud = { teams: new Map(), profiles: new Map(), bosses: new Map(), scores: new Map(), sidebar: '', packets: [] };
  for (const event of ['teams', 'player_info', 'player_remove', 'boss_bar', 'scoreboard_score', 'reset_score', 'scoreboard_display_objective']) {
    bot._client.on(event, packet => {
      const hud = bot.hud;
      hud.packets.push({ event, packet, at: Date.now() });
      if (event === 'teams') {
        if (packet.mode === 1) hud.teams.delete(packet.team);
        else {
          let team = hud.teams.get(packet.team);
          if (packet.mode === 0) team = { players: new Set() };
          if (!team) team = { players: new Set() };
          if (packet.mode === 0 || packet.mode === 2) Object.assign(team, {
            formatting: packet.formatting, nameTagVisibility: packet.nameTagVisibility,
            collisionRule: packet.collisionRule, prefix: component(packet.prefix), suffix: component(packet.suffix)
          });
          for (const player of packet.players || []) {
            if (packet.mode === 4) team.players.delete(player);
            else {
              for (const other of hud.teams.values()) other.players.delete(player);
              team.players.add(player);
            }
          }
          hud.teams.set(packet.team, team);
        }
      } else if (event === 'player_info') {
        // Void fields in other action variants do not erase existing values.
        for (const entry of packet.data) hud.profiles.set(entry.uuid, { ...hud.profiles.get(entry.uuid),
          ...Object.fromEntries(Object.entries(entry).filter(([, value]) => value !== undefined)) });
      } else if (event === 'player_remove') {
        for (const uuid of packet.players) hud.profiles.delete(uuid);
      } else if (event === 'boss_bar') {
        if (packet.action === 1) hud.bosses.delete(packet.entityUUID);
        else if (packet.action === 0 || packet.action === 3) hud.bosses.set(packet.entityUUID, component(packet.title));
      } else if (event === 'scoreboard_display_objective' && packet.position === 1) hud.sidebar = packet.name;
      else if (event === 'scoreboard_score') {
        if (!hud.scores.has(packet.scoreName)) hud.scores.set(packet.scoreName, new Map());
        hud.scores.get(packet.scoreName).set(packet.itemName, component(packet.display_name) || packet.itemName);
      } else if (event === 'reset_score') {
        for (const [objective, scores] of hud.scores) if (!packet.objective_name || objective === packet.objective_name) scores.delete(packet.entity_name);
      }
    });
  }
}
function teamFor(bot, name) {
  for (const [id, team] of bot.hud.teams) if (team.players.has(name)) return { id, ...team };
}
function alive(state) {
  return { t: state.roster.filter(row => row.team === 'T' && row.alive).length,
    ct: state.roster.filter(row => row.team === 'CT' && row.alive).length };
}
function hudCounts(text) {
  text = unstyled(text);
  const t = text.match(/(?:^|\W)T\s*(?:存活\s*)?(\d+)/), ct = text.match(/(?:^|\W)CT\s*(?:存活\s*)?(\d+)/);
  return t && ct ? { t: Number(t[1]), ct: Number(ct[1]) } : null;
}
function sidebar(bot) { return [...(bot.hud.scores.get(bot.hud.sidebar)?.values() || [])].join('\n'); }
function top(bot) { return [...bot.hud.bosses.values()].find(text => unstyled(text).includes('MyGO')) || ''; }
function matches(actual, expected) { return actual?.t === expected.t && actual?.ct === expected.ct; }
async function verifyCounts(bot, state, label) {
  const expected = alive(state);
  await waitFor(() => matches(hudCounts(sidebar(bot)), expected) && matches(hudCounts(top(bot)), expected), label + ' HUD packets', 2000);
  check(label + ': sidebar matches actual alive seats', matches(hudCounts(sidebar(bot)), expected), { expected, text: sidebar(bot), objective: bot.hud.sidebar });
  check(label + ': top bar matches actual alive seats', matches(hudCounts(top(bot)), expected), { expected, text: top(bot) });
}
function verifyTeamPackets(bot, state, label) {
  const actors = state.roster.map(row => ({ row, team: teamFor(bot, row.name) }));
  check(label + ': every actor belongs to its current side color', actors.every(({ row, team }) => team?.formatting === (row.team === 'T' ? 12 : 9)),
    actors.map(({ row, team }) => ({ name: row.name, side: row.team, color: team?.formatting, id: team?.id })));
  check(label + ': bot overhead names are hidden by vanilla team rule', actors.filter(({ row }) => row.bot).every(({ team }) => team?.nameTagVisibility === 'never'),
    actors.filter(({ row }) => row.bot).map(({ row, team }) => ({ name: row.name, rule: team?.nameTagVisibility })));
  check(label + ': all bot profile display names have side color', state.roster.filter(row => row.bot).every(row => {
    const display = bot.hud.profiles.get(row.uuid)?.displayName;
    const text = component(display), serialized = JSON.stringify(display);
    return text.includes(row.name) && (text.includes(row.team === 'T' ? '§c' : '§9') || serialized.includes(row.team === 'T' ? 'red' : 'blue'));
  }), state.roster.filter(row => row.bot).map(row => ({ name: row.name, side: row.team, display: component(bot.hud.profiles.get(row.uuid)?.displayName) })));
  check(label + ': own room bots are actually listed in Tab', state.roster.filter(row => row.bot)
    .every(row => !!bot.hud.profiles.get(row.uuid)?.listed), state.roster.filter(row => row.bot)
    .map(row => ({ name: row.name, listed: bot.hud.profiles.get(row.uuid)?.listed })));
}
function hasListedBots(bot) { return [...bot.hud.profiles.values()].some(profile => profile.listed && /^MG\d+_/.test(profile.player?.name || '')); }
const configPath = path.join(runDir, 'config/pvp/config.json');
let initialHealthTagSetting;
function setHealthTags(enabled) {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (initialHealthTagSetting === undefined) initialHealthTagSetting = config.healthTagEnabled;
  config.healthTagEnabled = enabled;
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + '\n');
}
async function run() {
  const a = connect('A'), b = connect('B'), c = connect('C');
  for (const actor of [a, b, c]) await ready(actor);
  await command(a, '/team add mygo_hud_restore');
  await command(a, '/team join mygo_hud_restore MyGoCheckA');
  check('fixture establishes pre-existing team before admission', teamFor(a, a.username)?.id === 'mygo_hud_restore');
  await join(a, 'ct', 'expert', 'dust2');
  let state = await info(a);
  check('initial room has five alive per side', matches(alive(state), { t: 5, ct: 5 }), alive(state));
  verifyTeamPackets(a, state, 'initial join');
  await verifyCounts(a, state, 'initial join');
  check('lobby client receives no MyGO top bar', !top(b), top(b));
  check('lobby client sidebar remains outside MyGO', !unstyled(sidebar(b)).includes('MyGO'), sidebar(b));
  check('lobby client has no listed MyGO bots', !hasListedBots(b));

  await join(b, 't', 'expert', 'dust2');
  state = await info(a);
  verifyTeamPackets(a, state, 'existing client after replacement');
  verifyTeamPackets(b, state, 'joining client receives full teams');
  await verifyCounts(a, state, 'replacement preserves living seat');
  await verifyCounts(b, state, 'replacement receives own HUD');
  await leave(b);
  state = await info(a);
  verifyTeamPackets(a, state, 'departure backfills bot');
  await verifyCounts(a, state, 'departure keeps seat life state');
  await waitFor(() => !top(b) && !unstyled(sidebar(b)).includes('MyGO'), 'departed client HUD cleanup', 3000);
  check('departed client has no MyGO top bar', !top(b), top(b));
  check('departed client sidebar returns to lobby', !unstyled(sidebar(b)).includes('MyGO'), sidebar(b));
  check('departed client no longer lists room bots', !hasListedBots(b));
  check('departure removes room bot profiles from the client', state.roster.filter(row => row.bot)
    .every(row => !b.hud.profiles.has(row.uuid)));

  await join(c, 't', 'beginner', 'mirage');
  const other = await info(c);
  verifyTeamPackets(c, other, 'second room');
  await verifyCounts(c, other, 'second room');
  check('both room clients receive the dedicated practice sidebar', a.hud.sidebar === 'pvp_mygo_prac' && c.hud.sidebar === 'pvp_mygo_prac',
    { a: a.hud.sidebar, c: c.hud.sidebar });
  check('each room top bar preserves its own phase and title', top(a) && top(c) && [...a.hud.bosses.keys()].every(id => !c.hud.bosses.has(id)), { a: top(a), c: top(c) });
  check('other room bots do not leak into the first room Tab', other.roster.filter(row => row.bot)
    .every(row => !a.hud.profiles.get(row.uuid)?.listed));
  check('first room bots do not leak into the other room Tab', state.roster.filter(row => row.bot)
    .every(row => !c.hud.profiles.get(row.uuid)?.listed));
  for (const enabled of [false, true]) {
    setHealthTags(enabled);
    await message(a, '/pvp reload', /配置已重载/);
    await sleep(350);
    verifyTeamPackets(a, await info(a), `health tags ${enabled} first room`);
    verifyTeamPackets(c, await info(c), `health tags ${enabled} other room`);
  }
  // A distance constraint makes @e local to the execute dimension; an
  // unconstrained selector can also enumerate the ordinary lobby's labels.
  const tagCheck = await message(a, '/execute in pvp:arena unless entity @e[type=minecraft:text_display,tag=pvp_health_tag,distance=0..] run tellraw @s "MYGO_NO_ARENA_HEALTH_TAGS"', /MYGO_NO_ARENA_HEALTH_TAGS/);
  check('MyGO arena has no extra overhead health label entities', tagCheck.includes('MYGO_NO_ARENA_HEALTH_TAGS'));

  const firstRound = state.round;
  let sawDeath = false, sawReset = false;
  const end = Date.now() + Number(process.env.MYGO_HUD_OBSERVE_MS || 210000);
  while (Date.now() < end && !sawReset) {
    await sleep(350);
    state = await info(a);
    const counts = alive(state);
    if (!sawDeath && state.round === firstRound && counts.t + counts.ct < 10) {
      await verifyCounts(a, state, 'real gunfight death');
      check('real death lowers both HUD counts', counts.t < 5 || counts.ct < 5, counts);
      await verifyCounts(c, await info(c), 'other room remains accurate during first room death');
      sawDeath = true;
    }
    if (sawDeath && state.round > firstRound && state.phase === 'BUY') {
      check('new round revives exactly five per side', matches(counts, { t: 5, ct: 5 }), counts);
      await verifyCounts(a, state, 'new round reset');
      verifyTeamPackets(a, state, 'new round');
      sawReset = true;
    }
  }
  check('natural bot combat produced a visible death', sawDeath);
  check('natural bot combat advanced and reset HUD for next round', sawReset);
  check('spectating lobby client stayed free of other room updates', !top(b) && !unstyled(sidebar(b)).includes('MyGO'), { top: top(b), side: sidebar(b) });
  await leave(c);
  await leave(a);
  await waitFor(() => !top(a) && !unstyled(sidebar(a)).includes('MyGO'), 'final HUD cleanup', 3000);
  check('exit removes MyGO top bar', !top(a));
  check('exit removes room bots from the Tab list', !hasListedBots(a));
  check('exit removes room bot profiles from the client', state.roster.filter(row => row.bot)
    .every(row => !a.hud.profiles.has(row.uuid)));
  check('exit restores original scoreboard team', teamFor(a, a.username)?.id === 'mygo_hud_restore', teamFor(a, a.username)?.id);
  await command(a, '/team remove mygo_hud_restore');
}

run().then(() => emit('complete', { checks: checks.length })).catch(error => {
  emit('failure', { error: error.stack }); process.exitCode = 1;
}).finally(async () => {
  if (initialHealthTagSetting !== undefined) {
    setHealthTags(initialHealthTagSetting);
    const operator = clients.find(bot => bot._client.state !== 'disconnected');
    if (operator) await command(operator, '/pvp reload');
  }
  const wire = clients.map(bot => ({ name: bot.username, packets: bot.hud.packets }));
  fs.writeFileSync(path.join(runDir, 'mygo-hud-results.json'), JSON.stringify({ checks, chats, observations, packLoads, wire }, null, 2));
  for (const bot of clients) if (bot._client.state !== 'disconnected') bot.quit();
  await sleep(300);
  process.exit(process.exitCode || 0);
});
