'use strict';

// Reason: exercise MyGO through genuine 1.21.1 client packets on the isolated
// server, retaining reproducible evidence while keeping npm out of this repo.
// A protocol bot can validate downloaded assets and server state; it cannot
// validate rendered gun models, the scope texture, animation, or audio playback.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { execFileSync } = require('node:child_process');
const moduleRoot = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(moduleRoot, 'mineflayer'));
const { Vec3 } = require(path.join(moduleRoot, 'vec3'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-server-test'));
const port = Number(process.env.MYGO_TEST_PORT || 25575);
if (port === 25565 || !path.basename(runDir).includes('mygo')) throw new Error('Use an isolated MyGO test directory and port.');
fs.mkdirSync(runDir, { recursive: true });
const logfile = path.join(runDir, 'protocol-results.jsonl');
const bots = {}, packMode = {}, packs = {}, packRequests = {}, assertions = [], chats = [], healthEvents = [], recoils = {}, abilities = {}, hud = [], soundEvents = [], effectEvents = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const stringify = value => JSON.stringify(value, (_, entry) => typeof entry === 'bigint' ? entry.toString() : entry);
function emit(event, detail = {}) {
  const record = stringify({ time: new Date().toISOString(), event, ...detail });
  fs.appendFileSync(logfile, `${record}\n`);
  process.stdout.write(`${record}\n`);
}
function check(name, ok, actual, expected) {
  const result = { name, pass: !!ok, actual, expected };
  assertions.push(result); emit('assertion', result);
  if (!ok) throw new Error(`Assertion failed: ${name}`);
}
function itemInfo(item) {
  return item && { name: item.name, count: item.count, slot: item.slot, components: item.components, nbt: item.nbt };
}
function snapshot(id) {
  const bot = bots[id];
  return { id, name: bot.username, position: bot.entity?.position, health: bot.health,
    food: bot.food, dimension: bot.game?.dimension, worldName: bot.testWorldName, mode: bot.game?.gameMode,
    selected: bot.quickBarSlot, held: itemInfo(bot.heldItem),
    items: bot.inventory?.slots.map(itemInfo).filter(Boolean),
    window: bot.currentWindow && { title: bot.currentWindow.title, id: bot.currentWindow.id,
      items: bot.currentWindow.slots.map(itemInfo).filter(Boolean) }, packs: packs[id] };
}
function packStatus(bot, packet, result) {
  bot._client.write('resource_pack_receive', { uuid: packet.uuid, result });
  emit('packStatus', { id: bot.testId, uuid: packet.uuid, result, headless: true });
}
async function handlePack(bot, packet) {
  const id = bot.testId;
  packRequests[id] = packet;
  emit('packRequested', { id, ...packet });
  if (packMode[id] === 'hold') return;
  if (packMode[id] === 'decline') { packStatus(bot, packet, 1); return; }
  packStatus(bot, packet, 3);
  try {
    const url = new URL(packet.url);
    const local = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
    if (!local && url.protocol !== 'https:') throw new Error('Non-local resource packs must use HTTPS.');
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Download returned HTTP ${response.status}`);
    const body = Buffer.from(await response.arrayBuffer());
    if (body.length > 30 * 1024 * 1024) throw new Error('Unexpected resource pack size over 30 MiB.');
    const hash = crypto.createHash('sha1').update(body).digest('hex');
    check(`${id} resource ZIP SHA-1`, hash === packet.hash, hash, packet.hash);
    const destination = path.join(runDir, `download-${id}-${hash}.zip`);
    fs.writeFileSync(destination, body);
    const validation = JSON.parse(execFileSync('python3', ['-c',
      'import json,sys,zipfile\nwith zipfile.ZipFile(sys.argv[1]) as z:\n assert z.testzip() is None\n names=z.namelist()\n parsed={n:json.loads(z.read(n)) for n in names if n.endswith((".json", ".mcmeta"))}\n assert parsed["pack.mcmeta"]["pack"]["pack_format"] == 34\n print(json.dumps({"entries":len(names),"jsonFiles":len(parsed),"models":sum(n.endswith(".json") and "/models/" in n for n in names),"scope":any(n.endswith("pumpkinblur.png") for n in names),"sounds":sum(n.endswith(".ogg") for n in names),"format":34}))', destination], { encoding: 'utf8' }));
    check(`${id} resource ZIP contains scope`, validation.scope, validation, 'pumpkinblur.png');
    packs[id] = { uuid: packet.uuid, sha1: hash, size: body.length, destination, validation,
      rendered: false, note: 'Downloaded and checked; headless bot reports loaded to exercise protocol gating.' };
    packStatus(bot, packet, 4);
    packStatus(bot, packet, 0);
    emit('packValidated', { id, ...packs[id] });
  } catch (error) {
    packStatus(bot, packet, 2);
    emit('packError', { id, message: error.stack });
  }
}
function connect(id) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: `MyGoCheck${id}`,
    version: '1.21.1', auth: 'offline', hideErrors: false });
  bot.testId = id;
  bots[id] = bot;
  packMode[id] ??= 'accept';
  // These probes stand still and use server /tp for setup. Mineflayer's delayed
  // respawn reply can replay a stale location after a second teleport; retain
  // the latest authoritative server coordinates in all stationary replies.
  let authoritativePosition;
  const originalWrite = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    // Footstep probes opt into ordinary client physics/position replies. Keep
    // the last position current before returning to stationary protocol cases.
    if (bot.testAllowMovement && (name === 'position' || name === 'position_look')) {
      authoritativePosition = new Vec3(packet.x, packet.y, packet.z);
      return originalWrite(name, packet);
    }
    if (authoritativePosition && (name === 'position' || name === 'position_look')) {
      if (bot.entity) { bot.entity.position.set(authoritativePosition.x, authoritativePosition.y, authoritativePosition.z); bot.entity.onGround = true; }
      // No walking is part of these scenarios. Acknowledge teleports through
      // teleport_confirm, and send standing/look packets instead of replaying
      // the physics plugin's delayed coordinates across dimensions or rounds.
      if (name === 'position_look') return originalWrite('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true });
      return originalWrite('flying', { onGround: true });
    }
    return originalWrite(name, packet);
  };
  bot._client.prependListener('position', packet => {
    if (packet.x === 0 && packet.y === 0 && packet.z === 0 && (packet.pitch || packet.yaw)) { recoils[id] = (recoils[id] || 0) + 1; emit('cameraRecoil', {id, packet}); }
    const prior = authoritativePosition || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object' ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritativePosition = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  // Mineflayer's game.dimension is the dimension TYPE; custom arena worlds use
  // the overworld type too. Keep the actual world key from vanilla spawn data.
  const trackWorld = packet => {
    const info = packet.worldState || packet;
    bot.testWorldName = info.name ?? info.worldName ?? packet.worldName;
    emit('worldChange', {id, worldName: bot.testWorldName, dimensionType: info.dimension});
  };
  bot._client.prependListener('login', trackWorld);
  bot._client.prependListener('respawn', trackWorld);
  bot.on('spawn', () => { bot.physicsEnabled = false; emit('spawn', snapshot(id)); });
  bot.on('messagestr', (message, position) => {
    chats.push({ id, message, position, at: Date.now() });
    if (position !== 'game_info') emit('chat', { id, message });
  });
  bot.on('windowOpen', window => emit('windowOpen', { id, title: window.title,
    items: window.slots.map(itemInfo).filter(Boolean) }));
  bot.on('health', () => { healthEvents.push({ id, health: bot.health, at: Date.now() }); emit('health', { id, health: bot.health, food: bot.food }); });
  bot.on('title', (title, type) => emit('title', { id, title, type }));
  bot.on('error', error => emit('error', { id, message: error.stack }));
  bot.on('kicked', reason => emit('kicked', { id, reason }));
  bot.on('end', reason => emit('end', { id, reason }));
  bot._client.on('add_resource_pack', packet => { void handlePack(bot, packet); });
  bot._client.on('remove_resource_pack', packet => { delete packs[id]; emit('packRemoved', { id, ...packet }); });
  bot._client.on('system_chat', packet => { if (packet.isActionBar) { const row = {id,raw:stringify(packet.content),at:Date.now()}; hud.push(row); emit('actionBar',row); } });
  bot._client.on('abilities', packet => { abilities[id] = packet; emit('abilities', {id, packet}); });
  bot._client.on('sound_effect', packet => {
    const name = packet.sound?.data?.soundName || bot.registry.sounds?.[packet.sound?.soundId]?.name || '';
    const row = {id, name, packet, at: Date.now()};
    soundEvents.push(row);
    if (name.startsWith('pvp:mygo.')) emit('mygoSound', row);
  });
  bot._client.on('entity_effect', packet => {
    const row = {id, packet, at: Date.now()};
    effectEvents.push(row);
    if (packet.entityId === bot.entity?.id) emit('selfEffect', row);
  });
  bot._client.on('entity_equipment', packet => {
    if (packet.entityId === bot.entity?.id) emit('selfEquipment', { id, ...packet });
  });
}
async function chat(id, text, delay = 200) {
  bots[id].chat(text);
  await sleep(delay);
}
async function waitUntil(predicate, timeout = 15000, label = 'condition') {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timeout waiting for ${label}`);
    await sleep(100);
  }
}
async function status(id) {
  const start = chats.length;
  await chat(id, '/pvp mygo status', 180);
  const message = chats.slice(start).find(entry => entry.id === id && entry.message.includes('MyGO round='))?.message;
  if (!message) throw new Error(`No MyGO status for bot ${id}`);
  return parseStatusMessage(message);
}
function parseStatusMessage(message) {
  const state = Object.fromEntries([...message.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(match => [match[1], match[2]]));
  // Java's EnumMap includes spaces after commas; retain the whole loadout.
  state.grenades = message.match(/\bgrenades=(\{[^}]*\})/)?.[1] || state.grenades;
  for (const key of ['round', 'remainingTicks', 'team', 'hp', 'cash', 'ammo', 'reserve', 'kills', 'activeGrenades', 'smokes', 'moveSpeed', 'walkSpeed', 'scopeLevel', 'scopeFov', 'buyTicks', 'lossLevel', 'armor', 'throwTick', 'stepSounds']) if (key in state) state[key] = Number(state[key]);
  for (const key of ['alive', 'vest', 'helmet', 'bombPlanted', 'scoped', 'awZoom', 'usingItem']) if (key in state) state[key] = state[key] === 'true';
  for (const key of ['range', 'spawn', 'waiting', 'throwVelocity', 'throwOrigin']) {
    if (!state[key] || !state[key].includes(',')) continue;
    const [x,y,z] = state[key].split(',').map(Number);
    state[key] = {x,y,z};
  }
  state.sites = [...message.substring(message.indexOf('sites=')).matchAll(/BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}/g)].map(match => ({ x: +match[1], y: +match[2], z: +match[3] }));
  state.raw = message;
  return state;
}
async function waitPhase(id, phase, timeout = 25000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const state = await status(id);
    if (state.phase === phase) return state;
    await sleep(800);
  }
  throw new Error(`Timeout waiting for MyGO phase ${phase}`);
}
async function teleport(id, position) {
  await chat('A', `/execute in pvp:arena run tp MyGoCheck${id} ${position.x + .5} ${position.y} ${position.z + .5}`, 200);
}
async function select(id, slot) {
  bots[id].setQuickBarSlot(slot);
  // Explicit packet also covers an earlier server slot change before the next
  // server-to-client held-slot synchronization reaches the protocol bot.
  bots[id]._client.write('held_item_slot', { slotId: slot });
  await sleep(120);
}
async function trigger(id, count = 1, interval = 250) {
  for (let i = 0; i < count; i++) { bots[id].swingArm('right'); await sleep(interval); }
}
async function scope(id, enabled) {
  if ((bots[id].inventory.slots[5]?.name === 'carved_pumpkin') === enabled) return;
  await sleep(320); bots[id].activateItem(); await sleep(160);
}
async function use(id, delay = 1800) { bots[id].activateItem(); await sleep(delay); }
async function gate() {
  await waitUntil(() => Object.values(bots).every(bot => bot.entity), 30000, 'all bots spawned');
  const probe = 'D';
  packMode[probe] = 'decline';
  const declinedAt = chats.length;
  await chat(probe, '/pvp join mygo', 400);
  await waitUntil(() => chats.slice(declinedAt).some(entry => entry.id === probe && entry.message.includes('DECLINED')), 5000, 'declined pack feedback');
  await chat(probe, '/pvp mygo status', 200);
  check('declining optional pack keeps player in lobby and unready', chats.slice(declinedAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=false')) && bots[probe].game.dimension !== 'pvp:arena', chats.slice(declinedAt).filter(entry => entry.id === probe), 'unready; in lobby');
  packMode[probe] = 'hold';
  delete packRequests[probe];
  await chat(probe, '/pvp join mygo', 200);
  await waitUntil(() => packRequests[probe], 5000, 'held pack request');
  packStatus(bots[probe], packRequests[probe], 3);
  packStatus(bots[probe], packRequests[probe], 4);
  const heldAt = chats.length;
  await chat(probe, '/pvp join mygo', 180);
  await chat(probe, '/pvp mygo status', 180);
  check('ACCEPTED and DOWNLOADED do not unlock matchmaking', chats.slice(heldAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=false')), chats.slice(heldAt).filter(entry => entry.id === probe), 'resourcePackReady=false');
  const timeoutAt = chats.length;
  await waitUntil(() => chats.slice(timeoutAt).some(entry => entry.id === probe && entry.message.includes('加载超时')), 14000, '10 second pack loading timeout');
  check('pack loading timeout removes pending pack', !packs[probe] && chats.slice(timeoutAt).some(entry => entry.id === probe && entry.message.includes('加载超时')), chats.slice(timeoutAt).filter(entry => entry.id === probe), 'timeout; unready');
  packMode[probe] = 'accept';
  const reloadAt = chats.length;
  await chat(probe, '/pvp join mygo', 200);
  await waitUntil(() => packs[probe], 10000, 'valid pack download after timeout');
  // Download completion and sending LOADED precede the server thread consuming
  // that packet. Wait for its acknowledgement before probing ready state.
  await waitUntil(() => chats.slice(reloadAt).some(entry => entry.id === probe
    && entry.message.includes('MyGO 资源包加载成功')),5000,'server resource-pack load acknowledgement');
  const readyAt = chats.length;
  await chat(probe, '/pvp mygo status', 200);
  check('real ZIP download plus reported LOADED unlocks ready state', chats.slice(readyAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=true')), chats.slice(readyAt).filter(entry => entry.id === probe), 'resourcePackReady=true');
  await chat(probe, '/pvp join mygo', 200);
  await chat(probe, '/pvp leave', 250);
  check('queue exit removes only MyGO resource pack', !packs[probe], packs[probe], 'removed');
  return { passed: assertions.filter(result => result.pass).length, failed: assertions.filter(result => !result.pass).length };
}
function hudMoney(id, cash, detail = '') {
  return hud.filter(row => row.id === id && row.raw.includes('§6§l$ ' + cash.toLocaleString('en-US')) && row.raw.includes(detail));
}
async function smoke() {
  const ids = Object.keys(bots);
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'all bots spawned');
  check('four clients connect to Fabric 1.21.1', ids.length === 4 && ids.every(id => bots[id].version === '1.21.1'), ids, 'ABCD');
  // Save a recognizable lobby item and helmet before joining to verify restoration.
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 20000, 'every resource pack downloaded and checked');
  for (const id of ids) await chat(id, '/pvp join mygo');
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'round loadouts');
  const initial = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  check('balanced 2v2 sides', Object.values(initial).filter(state => state.side === 'T').length === 2 && Object.values(initial).filter(state => state.side === 'CT').length === 2, initial, 'two T and two CT');
  check('CS:GO quick match begins with $800 and 100 HP', Object.values(initial).every(state => state.cash === 800 && state.hp === 100), initial, '800 cash, 100 virtual HP');
  await waitUntil(() => hudMoney('A',800).length > 0,5000,'first money HUD after match activation');
  check('money HUD is a bold gold action bar with thousands separators', hudMoney('A',800).length > 0, hudMoney('A',800).slice(-1), '$ 800 above hotbar');
  const attackers = ids.filter(id => initial[id].side === 'T'), defenders = ids.filter(id => initial[id].side === 'CT');
  const carrier = attackers.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  check('exactly one attacker receives C4', !!carrier && attackers.filter(id => bots[id].inventory.items().some(item => item.name === 'redstone')).length === 1, carrier, 'one carrier');
  const gunner = attackers.find(id => id !== carrier), defender = defenders[0], otherDefender = defenders[1];
  await select(gunner, 8); bots[gunner].activateItem(); await sleep(150);
  check('purchase device opens real shop GUI', !!bots[gunner].currentWindow, bots[gunner].currentWindow?.title, 'MyGO shop');
  // With CS:GO's $800 start, buy the $300 P250 (GUI slot 12) instead of the
  // old pre-CSGO test's unaffordable MP5 purchase.
  await bots[gunner].clickWindow(12, 0, 0); await sleep(100); bots[gunner].closeWindow(bots[gunner].currentWindow);
  await select(gunner, 1);
  const bought = await status(gunner);
  check('GUI purchase charges P250 price', bought.cash === 500 && bought.gun === 'P250' && bought.ammo === 13 && bought.reserve === 26, bought, 'P250 13/26; cash=500');
  check('successful purchase immediately shows new action-bar balance', hudMoney(gunner,500,'购买成功').length > 0, hudMoney(gunner,500,'购买成功').slice(-1), '$ 500');
  await chat(defender, '/pvp mygo buy kit');
  const armor = await status(defender);
  check('defuse kit purchase fits the $800 start economy', !armor.vest && armor.cash === 400, armor, '800 - 400 = 400');
  await waitPhase(gunner, 'LIVE');
  // Use the unobstructed corridor, away from cover and the bomb site.
  const site = initial[carrier].sites.at(-1);
  if (!site) throw new Error('Status did not include a bomb site');
  const range = initial[carrier].range;
  await teleport(gunner, range);
  await teleport(defender, { ...range, x: range.x + 6 });
  await teleport(carrier, { ...range, x: range.x + 3 });
  await select(gunner, 1); await scope(gunner, true); await sleep(450);
  check('right click with a pistol does not create a scope', !bots[gunner].inventory.slots[5], itemInfo(bots[gunner].inventory.slots[5]), 'empty head');
  const friendBefore = await status(carrier), enemyBefore = await status(defender);
  await bots[gunner].lookAt(bots[defender].entity.position.offset(0, .9, 0), true);
  await trigger(gunner, 1, 300);
  const shot = await status(gunner), friendAfter = await status(carrier), enemyAfter = await status(defender);
  check('P250 emits one bullet per trigger', shot.ammo === 12, shot, '13 -> 12');
  check('friendly target does not take damage or block bullets', friendAfter.hp === friendBefore.hp && enemyAfter.hp < enemyBefore.hp, { friendBefore, friendAfter, enemyBefore, enemyAfter }, 'friend unchanged, enemy damaged');
  check('hit or kill feedback keeps the bold money label visible', enemyAfter.alive === false || hudMoney(gunner,500,'命中').length > 0,
    { enemyAfter, hud: hudMoney(gunner,500,'命中').slice(-1) }, '$ 500 with hit/kill feedback');
  bots[gunner]._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
  await sleep(2300);
  const reloaded = await status(gunner);
  check('F swap packet reloads magazine and consumes reserve', reloaded.ammo === 13 && reloaded.reserve === 25, reloaded, '13/25');
  check('reload prompt keeps the money label visible', hudMoney(gunner,500,'正在换弹').length > 0, hudMoney(gunner,500,'正在换弹').slice(-1), '$ 500 with reload');
  await scope(gunner, false);
  check('second right click clears scope helmet', !bots[gunner].inventory.slots[5], itemInfo(bots[gunner].inventory.slots[5]), 'empty head');
  await teleport(carrier, site); await select(carrier, 5);
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(1000);
  bots[carrier].setControlState('sneak', false); await sleep(2400);
  check('releasing sneak cancels installation', !(await status(carrier)).bombPlanted, await status(carrier), 'unplanted');
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(3300);
  check('plant progress keeps current balance visible', hudMoney(carrier,800,'安装剩余').length > 0, hudMoney(carrier,800,'安装剩余').slice(-1), '$ 800 with progress');
  const planted = await status(carrier);
  check('three second plant starts 40 second fuse and rewards $300', planted.bombPlanted && planted.remainingTicks <= 800 && planted.remainingTicks > 700 && planted.cash === 1100, planted, 'planted; approximately 40s; 1100 cash');
  await waitUntil(() => hudMoney(carrier,1100).length > 0,1500,'plant money HUD');
  check('plant reward updates the highlighted balance', hudMoney(carrier,1100).length > 0, hudMoney(carrier,1100).slice(-1), '$ 1,100');
  bots[carrier].setControlState('sneak', false);
  await teleport(defender, { ...site, z: site.z + 1 }); await select(defender, 5);
  bots[defender].setControlState('sneak', true); await sleep(150); bots[defender].activateItem(); await sleep(1800);
  check('defuse progress keeps current balance visible', hudMoney(defender,400,'拆除剩余').length > 0, hudMoney(defender,400,'拆除剩余').slice(-1), '$ 400 with progress');
  await select(defender, 1); await sleep(2500);
  check('switching slots cancels defuse', (await status(defender)).phase === 'LIVE' && (await status(defender)).bombPlanted, await status(defender), 'LIVE and planted');
  await select(defender, 5); bots[defender].activateItem(); await sleep(5500);
  const defused = await status(defender);
  check('five second kit defuse wins round and grants economy rewards', defused.phase === 'POST' && defused.score === '0:1' && defused.cash === 3900, defused, 'POST, defenders lead, 400+3500=3900');
  await waitUntil(() => hudMoney(defender,3900).length > 0,1500,'round money HUD');
  check('defuse and win rewards update bold thousands-formatted balance', hudMoney(defender,3900).length > 0, hudMoney(defender,3900).slice(-1), '$ 3,900');
  bots[defender].setControlState('sneak', false);
  const next = await waitPhase(defender, 'BUY', 10000);
  check('new round resets HP and unplants C4', next.round === 2 && next.hp === 100 && !next.bombPlanted, next, 'round=2, hp=100, no planted bomb');
  const gunnerNext = await status(gunner);
  check('survivor retains purchased gun and refills ammo', gunnerNext.gun === 'P250' && gunnerNext.ammo === 13 && gunnerNext.reserve === 26, gunnerNext, 'P250 13/26');
  for (const id of [carrier, gunner, defender, otherDefender]) await chat(id, '/hub', 250);
  await sleep(500);
  check('leaving restores lobby helmet', bots.A.inventory.slots[5]?.name === 'golden_helmet', itemInfo(bots.A.inventory.slots[5]), 'golden_helmet');
  check('leaving restores lobby inventory', bots.A.inventory.items().some(item => item.name === 'apple' && item.count === 3), snapshot('A'), 'three apples');
  emit('smokeComplete', { passed: assertions.filter(result => result.pass).length,
    failed: assertions.filter(result => !result.pass).length, renderingVerified: false });
  return { initial, gunner, carrier, defender, otherDefender, range, site };
}
async function shutdownSetup() {
  await sleep(6500);
  const ids = Object.keys(bots);
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  await chat('A', '/pvp mygo status', 200);
  await chat('A', '/data get entity MyGoCheckA Inventory', 200);
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 15000, 'shutdown test packs loaded');
  for (const id of ids) await chat(id, '/pvp join mygo', 200);
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'shutdown test loadouts');
  await select('A', 1);
  await waitPhase('A', 'LIVE');
  await scope('A', true);
  check('shutdown test begins while scope is equipped', bots.A.inventory.slots[5]?.name === 'carved_pumpkin', itemInfo(bots.A.inventory.slots[5]), 'temporary carved pumpkin');
  emit('shutdownReady', { instruction: 'Send stop on the isolated server console, then run tools/mygo_verify_saved_player.cjs.', snapshot: snapshot('A') });
  return status('A');
}
async function fullMatch() {
  await sleep(6500);
  const ids = Object.keys(bots);
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  const statsStart = chats.length;
  for (const id of ids) await chat(id, '/pvp mygo stats', 150);
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 15000, 'full match resource packs');
  for (const id of ids) await chat(id, '/pvp join mygo', 180);
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'full match loadouts');
  const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const winners = ids.filter(id => opening[id].team === 0), losers = ids.filter(id => opening[id].team === 1), gunner = winners[0];
  const site = opening[gunner].sites.at(-1), range = opening[gunner].range;
  for (let round = 1; round <= 7; round++) {
    const buy = await waitPhase(gunner, 'BUY', 15000);
    check(`round ${round} starts alive with restored HP`, buy.round === round && buy.hp === 100 && buy.alive, buy, `round=${round}, hp=100, alive=true`);
    if (round === 2) await chat(gunner, '/pvp mygo buy ak', 150);
    if (round === 7) {
      check('halftime swaps attacker side and resets cash', buy.side === 'CT' && buy.cash === 1600 && buy.score === '6:0', buy, 'CT; cash=1600; score=6:0');
      check('halftime clears previously purchased AK', bots[gunner].inventory.items().filter(item => item.name === 'iron_pickaxe').length === 1,
        snapshot(gunner), 'P9 only');
    }
    await waitPhase(gunner, 'LIVE', 20000);
    await teleport(gunner, range); await select(gunner, 1);
    await scope(gunner, true); await sleep(400);
    for (const [index, victim] of losers.entries()) {
      await teleport(victim, { ...range, x: range.x + 6, z: range.z + index * 3 });
      await bots[gunner].lookAt(bots[victim].entity.position.offset(0, 1.6, 0), true);
      await trigger(gunner, 1, 300);
      const dead = await status(victim);
      check(`round ${round} actual P9 headshot eliminates ${victim}`, !dead.alive && dead.hp === 0, dead, 'alive=false; hp=0');
      check(`round ${round} ${victim} stays in adventure death room`, bots[victim].game.gameMode === 'adventure' && bots[victim].entity.position.distanceTo(new Vec3(opening[gunner].waiting.x+.5,opening[gunner].waiting.y,opening[gunner].waiting.z+.5)) < 4,
        { mode: bots[victim].game.gameMode, position: bots[victim].entity.position }, 'adventure; sealed death room');
    }
    await scope(gunner, false);
    if (round < 7) {
      const post = await status(gunner);
      check(`round ${round} elimination awards a single team point`, post.phase === 'POST' && post.score === `${round}:0`, post, `POST; ${round}:0`);
      await waitPhase(gunner, 'BUY', 10000);
    }
  }
  await sleep(6000);
  check('seven victories restore original lobby helmet', bots.A.inventory.slots[5]?.name === 'golden_helmet', itemInfo(bots.A.inventory.slots[5]), 'golden_helmet');
  check('seven victories restore original lobby inventory', bots.A.inventory.items().some(item => item.name === 'apple' && item.count === 3), snapshot('A'), 'three apples');
  const statsFinish = chats.length;
  for (const id of ids) await chat(id, '/pvp mygo stats', 150);
  emit('fullMatchComplete', { winners, losers,
    before: chats.slice(statsStart).filter(entry => entry.message.includes('MyGO 战绩')).slice(0, 4),
    after: chats.slice(statsFinish), finalScore: '7:0' });
  return { winners, losers, finalScore: '7:0' };
}
async function explosion() {
  const ids = Object.keys(bots);
  const states = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const attackers = ids.filter(id => states[id].side === 'T'), defenders = ids.filter(id => states[id].side === 'CT');
  const carrier = attackers.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  const site = states[carrier].sites.at(-1), gunner = defenders[0];
  for (const id of ids) bots[id].setControlState('sneak', false);
  await teleport(carrier, site); await select(carrier, 5);
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(3300);
  check('explosion scenario installs live C4', (await status(carrier)).bombPlanted, await status(carrier), 'planted');
  bots[carrier].setControlState('sneak', false);
  const position = states[gunner].range;
  await teleport(gunner, position); await select(gunner, 1);
  await scope(gunner, true); await sleep(400);
  for (const [index, victim] of attackers.entries()) {
    await teleport(victim, { ...position, x: position.x + 5, z: position.z + index * 2 });
    await bots[gunner].lookAt(bots[victim].entity.position.offset(0, 1.6, 0), true);
    await trigger(gunner, 1, 300);
    check(`planted round eliminates attacker ${victim}`, !(await status(victim)).alive, await status(victim), 'dead');
  }
  const continued = await status(gunner);
  check('eliminating every attacker does not end planted round', continued.phase === 'LIVE' && continued.bombPlanted,
    continued, 'LIVE; bombPlanted=true');
  await scope(gunner, false);
  await sleep(Math.max(0, continued.remainingTicks * 50 + 250));
  const result = await status(gunner);
  check('real 40 second C4 explosion wins for eliminated attackers', result.phase === 'POST' && result.score === '1:0', result, 'POST; attackers lead 1:0');
  return result;
}
function glows(item) { return !!item?.components?.some(component => component.type === 'enchantments' || component.type === 'enchantment_glint_override' && component.data); }
function menuItem(id, slot) { return bots[id].currentWindow?.slots[slot]; }
async function menu() {
  await waitUntil(() => ['A', 'B', 'C'].every(id => bots[id]?.entity), 30000, 'three GUI clients');
  await chat('A', '/pvp');
  check('main menu orders SkyWars MyGO Bridge at slots 10 11 12',
    stringify(menuItem('A', 10)).includes('空岛战争') && stringify(menuItem('A', 11)).includes('MyGO') && stringify(menuItem('A', 12)).includes('战桥'),
    [10, 11, 12].map(slot => itemInfo(menuItem('A', slot))), 'SkyWars / MyGO / Bridge');
  check('main minigame description no longer lists MyGO', !stringify(menuItem('A', 13)).includes('MyGO'), itemInfo(menuItem('A', 13)), 'no MyGO');
  await bots.A.clickWindow(13, 0, 0); await sleep(200);
  check('minigame page contains no MyGO button', !stringify(bots.A.currentWindow.slots.slice(0, 36)).includes('MyGO') && menuItem('A', 19)?.name === 'gray_stained_glass_pane',
    { title: bots.A.currentWindow.title, slot19: itemInfo(menuItem('A', 19)) }, 'empty slot 19 and no MyGO');
  await bots.A.clickWindow(19, 0, 0); await sleep(200);
  check('former minigame MyGO slot no longer routes to queue', !!bots.A.currentWindow && !packRequests.A, { title: bots.A.currentWindow?.title, packRequested: !!packRequests.A }, 'same minigame page, no pack request');
  await bots.A.clickWindow(26, 0, 0); await sleep(200);
  await bots.A.clickWindow(11, 0, 0); await sleep(200);
  await waitUntil(() => packs.A, 15000, 'A resource download from main menu');
  check('main menu MyGO click requests configured external pack', packRequests.A?.url === 'https://qfmaker.com/upload/files/5df4ab7h.zip', packRequests.A, 'exact configured HTTPS URL');
  await chat('B', '/pvp'); await bots.B.clickWindow(11, 0, 0); await sleep(200);
  await waitUntil(() => packs.B, 15000, 'B resource download from main menu');
  await chat('B', '/pvp'); await bots.B.clickWindow(11, 0, 0); await sleep(250);
  await chat('A', '/pvp'); await sleep(1100);
  check('main MyGO button refreshes after B joins', menuItem('A', 11)?.count === 1 && glows(menuItem('A', 11)), itemInfo(menuItem('A', 11)), 'one queued player, enchanted');
  check('MyGO queue does not light minigame category', menuItem('A', 13)?.count === 1 && !glows(menuItem('A', 13)), itemInfo(menuItem('A', 13)), 'default one item, no enchantment');
  await bots.A.clickWindow(11, 0, 0); await sleep(250);
  await chat('A', '/pvp'); await sleep(1100);
  check('loaded client rejoins through main MyGO button and shows queue 2', menuItem('A', 11)?.count === 2 && glows(menuItem('A', 11)), itemInfo(menuItem('A', 11)), 'two queued players');
  check('two MyGO players remain excluded from minigame category', menuItem('A', 13)?.count === 1 && !glows(menuItem('A', 13)), itemInfo(menuItem('A', 13)), 'no minigame queued');
  await chat('C', '/pvp join luckypillar'); await sleep(1100);
  check('minigame category counts only the actual minigame player', menuItem('A', 13)?.count === 1 && glows(menuItem('A', 13)) && menuItem('A', 11)?.count === 2,
    { games: itemInfo(menuItem('A', 13)), mygo: itemInfo(menuItem('A', 11)) }, 'games=1, MyGO=2');
  await chat('C', '/pvp leave'); await sleep(1100);
  check('minigame queue exit clears its indicator while MyGO stays at 2', !glows(menuItem('A', 13)) && menuItem('A', 11)?.count === 2,
    { games: itemInfo(menuItem('A', 13)), mygo: itemInfo(menuItem('A', 11)) }, 'games empty, MyGO=2');
  await bots.A.clickWindow(22, 0, 0); await sleep(250);
  await chat('B', '/pvp leave');
  await chat('A', '/pvp'); await sleep(1100);
  check('leaving via main menu resets MyGO queue indicator', menuItem('A', 11)?.count === 1 && !glows(menuItem('A', 11)), itemInfo(menuItem('A', 11)), 'default one item, no enchantment');
  check('queue exit removes loaded MyGO packs', !packs.A && !packs.B, { A: packs.A, B: packs.B }, 'both removed');
  emit('menuComplete', { passed: assertions.filter(result => result.pass).length, failed: assertions.filter(result => !result.pass).length, renderingVerified: false });
  return { slot10: itemInfo(menuItem('A', 10)), slot11: itemInfo(menuItem('A', 11)), slot12: itemInfo(menuItem('A', 12)) };
}
async function arsenal() {
  const ids = Object.keys(bots);
  for (const id of ids) await chat(id, '/hub', 100);
  for (const id of ids) await chat(id, '/pvp join mygo', 150);
  await waitUntil(() => ids.every(id => packs[id]), 20000, 'new arsenal pack');
  for (const id of ids) await chat(id, '/pvp join mygo', 100);
  await chat('A', '/pvp start', 100);
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'arsenal round');
  const initial = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const attackers = ids.filter(id => initial[id].side === 'T'), defenders = ids.filter(id => initial[id].side === 'CT');
  const grenadier = attackers[0], friend = attackers[1], target = defenders[0], shotgunner = defenders[1];
  await chat(grenadier, '/pvp mygo');
  check('arsenal shop exposes fifteen weapons and three utilities in real 45 slot GUI',
    bots[grenadier].currentWindow?.inventoryStart === 45 && bots[grenadier].currentWindow.slots.slice(9, 24).every(item => item?.name === 'iron_pickaxe')
    && bots[grenadier].currentWindow.slots.slice(30, 33).every(item => item?.name === 'slime_ball'),
    {slots: bots[grenadier].currentWindow?.inventoryStart}, '45 slots; 15 guns; HE/flash/smoke');
  bots[grenadier].closeWindow(bots[grenadier].currentWindow);
  await chat(grenadier, '/pvp mygo buy m4', 100); await chat(target, '/pvp mygo buy ak', 100);
  check('opposite side rifles reject purchases without taking money', (await status(grenadier)).cash === 1600 && (await status(target)).cash === 1600, await status(target), 'both keep 1600');
  for (const item of ['he', 'flash', 'flash', 'smoke', 'flash']) await chat(grenadier, '/pvp mygo buy ' + item, 80);
  check('four utility loadout obeys flash carrying cap', (await status(grenadier)).cash === 600, await status(grenadier), '1000 spent; third flash rejected');
  for (const item of ['vest', 'helmet', 'smoke']) await chat(target, '/pvp mygo buy ' + item, 80);
  await chat(friend, '/pvp mygo buy uzi', 80); await chat(shotgunner, '/pvp mygo buy s686', 80);
  const live = await waitPhase(grenadier, 'LIVE');
  const site = live.sites[0], origin = live.range;
  for (const [index, id] of ids.entries()) await teleport(id, {...origin, x: origin.x + index * 2});
  await select(friend, 0); await bots[friend].look(0, 1.1, true);
  const before = await status(friend), recoilBefore = recoils[friend] || 0;
  await trigger(friend, 1, 300);
  const after = await status(friend);
  check('new Uzi burst consumes two rounds and camera recoil preserves live position', after.gun === 'UZI' && after.ammo === 30 && after.alive && (recoils[friend] || 0) > recoilBefore,
    {after, recoil:recoils[friend],position:bots[friend].entity.position}, 'UZI 32->30, recoil packet, alive');
  bots[friend]._client.write('block_dig', {status:6,location:{x:0,y:0,z:0},face:0,sequence:0}); await sleep(100);
  check('reload switches the actual held resource model to reload pose', stringify(itemInfo(bots[friend].heldItem)).includes('71216'), itemInfo(bots[friend].heldItem), 'UZI reload CMD 71216');
  await select(shotgunner, 0); await bots[shotgunner].look(0, 1.1, true); await trigger(shotgunner, 1, 300);
  check('S686 pellet group consumes one shell', (await status(shotgunner)).ammo === 1, await status(shotgunner), '2->1 shell');
  await teleport(grenadier, origin); await teleport(friend, {...origin,x:origin.x+2}); await teleport(target,{...origin,x:origin.x+4});
  const hpBefore = await status(target), friendlyBefore = await status(friend);
  await select(grenadier, 3); await bots[grenadier].lookAt(new Vec3(origin.x+1.5,origin.y+.1,origin.z+.5),true);
  await use(grenadier);
  const hpAfter = await status(target);
  check('HE grenade damages armored enemy and ignores teammate', hpAfter.hp < hpBefore.hp && (await status(friend)).hp === friendlyBefore.hp,
    {enemyBefore:hpBefore.hp,enemyAfter:hpAfter.hp,friend:await status(friend)}, 'enemy damaged; friend unchanged');
  await bots[target].lookAt(bots[grenadier].entity.position.offset(0,1.6,0),true);
  await select(grenadier,4); await use(grenadier);
  check('facing a real flash applies client status effects', Object.keys(bots[target].entity.effects || {}).length > 0, bots[target].entity.effects, 'blindness/darkness');
  await select(grenadier,6); await use(grenadier);
  const smoky = await status(grenadier);
  check('smoke fuse creates one active cloud and visible item displays', smoky.smokes === 1 && Object.values(bots[grenadier].entities).filter(e => e.name === 'item_display').length >= 5,
    {smoky, displays:Object.values(bots[grenadier].entities).filter(e=>e.name==='item_display').length}, 'one smoke; at least five display screens');
  await sleep(12500);
  check('smoke expires after twelve seconds', (await status(grenadier)).smokes === 0, await status(grenadier), 'zero smoke clouds');
  await select(target,6); await bots[target].lookAt(new Vec3(origin.x+4.5,origin.y+.1,origin.z+.5),true); await use(target);
  check('second smoke is active before exiting', (await status(target)).smokes === 1, await status(target), 'one smoke');
  for (const id of ids) await chat(id, '/hub', 150);
  await sleep(500);
  check('exiting the match clears every utility display', Object.values(bots.A.entities).every(e => e.name !== 'item_display'), Object.values(bots.A.entities).filter(e=>e.name==='item_display').length, 'no leaked utility displays');
  emit('arsenalComplete',{passed:assertions.filter(a=>a.pass).length,failed:assertions.filter(a=>!a.pass).length});
}

async function awZoom() {
  const ids = Object.keys(bots);
  for (const id of ids) await chat(id, '/hub', 100);
  await sleep(6500);
  for (const id of ids) await chat(id, '/pvp join mygo', 100);
  await waitUntil(() => ids.every(id => packs[id]), 15000, 'AWM test packs');
  for (const id of ids) await chat(id, '/pvp join mygo', 100);
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(i => i.name === 'iron_pickaxe')), 25000, 'AWM loadouts');
  const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const winners = ids.filter(id => opening[id].team === 0), losers = ids.filter(id => opening[id].team === 1), gunner = winners[0], partner = winners[1];
  for (let round = 1; round <= 2; round++) {
    const live = await waitPhase(gunner, 'LIVE', 20000), site = live.sites.at(-1);
    for (const id of losers) await teleport(id, {...site,x:site.x+500});
    const won = await status(gunner);
    check('AWM funding round ' + round + ' settles once', won.phase === 'POST' && won.score === round + ':0', won, 'single round win');
    await waitPhase(gunner, 'BUY', 10000);
  }
  for (const id of winners) await chat(id, '/pvp mygo buy aw');
  await waitPhase(gunner, 'LIVE', 20000);
  await select(gunner, 0); await select(partner, 0);
  const before = await status(gunner), normal = {...abilities[gunner]};
  check('round economy buys AWM normally', before.gun === 'AW' && before.cash === 3350 && before.ammo === 5, before, '8100-4750');
  await scope(gunner, true);
  const aimed = await status(gunner), zoom = {...abilities[gunner]};
  check('AWM scopes without spending ammunition', aimed.scoped && aimed.awZoom && aimed.ammo === 5, aimed, 'scoped AWM, five rounds');
  check('AWM sends stronger client zoom without changing actual speed or abilities', Math.abs(aimed.walkSpeed-.1)<1e-6
    && Math.abs(aimed.moveSpeed-.065)<1e-6 && Math.abs(zoom.walkingSpeed-.2)<1e-6
    && zoom.flags === normal.flags && zoom.flyingSpeed === normal.flyingSpeed, {aimed,normal,zoom}, 'server .065/.1, client .2; identical permissions');
  const fov = (aimed.moveSpeed/zoom.walkingSpeed+1)/2;
  check('default AWM scoped view narrows to about 46 degrees', fov < .67 && fov > .65, {multiplier:fov,baseFov:70,degrees:70*fov}, 'about 46 degrees at 100% FOV effects');
  await bots[gunner].look(0,1.1,true); await trigger(gunner,1,300);
  const fired = await status(gunner);
  check('zoomed AWM left click fires once without self death', fired.alive && fired.hp === 100 && fired.ammo === 4 && fired.awZoom, fired, 'alive, 100HP, four rounds');
  await scope(gunner,false);
  check('closing AWM restores the real walk-speed baseline', Math.abs(abilities[gunner].walkingSpeed-.1)<1e-6 && !(await status(gunner)).awZoom, abilities[gunner], 'client .1');
  await scope(gunner,true); await select(gunner,1); await scope(gunner,true);
  const pistol = await status(gunner);
  check('switching to pistol restores baseline and keeps ordinary scope', pistol.scoped && !pistol.awZoom && Math.abs(abilities[gunner].walkingSpeed-.1)<1e-6, pistol, 'ordinary scope, client .1');
  await select(gunner,0); await scope(gunner,true); await chat(gunner,'/hub');
  check('leaving while AWM scoped restores client abilities', Math.abs(abilities[gunner].walkingSpeed-.1)<1e-6 && bots[gunner].inventory.slots[5]?.name !== 'carved_pumpkin', abilities[gunner], 'client .1, no scope helmet');
  await scope(partner,true);
  check('second AWM player independently receives zoom', (await status(partner)).awZoom && Math.abs(abilities[partner].walkingSpeed-.2)<1e-6, abilities[partner], 'second client .2');
  const site = (await status(partner)).sites.at(-1);
  await teleport(partner,{...site,x:site.x+500});
  const dead = await status(partner);
  check('death while AWM scoped restores client abilities', !dead.alive && !dead.awZoom && Math.abs(abilities[partner].walkingSpeed-.1)<1e-6, {dead,abilities:abilities[partner]}, 'dead, client .1');
  for (const id of ids) await chat(id,'/hub',100);
  emit('awZoomComplete',{passed:assertions.filter(a=>a.pass).length,failed:assertions.filter(a=>!a.pass).length});
}

// Reason: a two-player minimum must work through the normal sixty-second queue,
// without an OP force-start masking regressions in automatic 1v1 matchmaking.
async function twoPlayerAuto() {
  const ids = Object.keys(bots);
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'both 1v1 clients spawned');
  check('automatic 1v1 uses exactly two vanilla protocol clients',
    ids.length === 2 && ids.includes('A') && ids.includes('B') && ids.every(id => bots[id].version === '1.21.1'), ids, 'MYGO_TEST_BOTS=AB');
  const chatStart = chats.length;
  const lobby = Object.fromEntries(ids.map(id => [id, bots[id].testWorldName]));
  check('both clients report actual lobby world keys', ids.every(id => typeof lobby[id] === 'string'), lobby, 'world keys from login spawn data');
  for (const id of ids) {
    await chat('A', `/item replace entity MyGoCheck${id} armor.head with minecraft:golden_helmet`, 80);
    await chat('A', `/item replace entity MyGoCheck${id} hotbar.7 with minecraft:apple 3`, 80);
  }
  await chat('A', '/pvp');
  const tooltip = stringify(menuItem('A', 11));
  check('MyGO main-menu tooltip advertises 1v1 and a two-player minimum',
    tooltip.includes('1v1~5v5') && tooltip.includes('至少两人') && tooltip.includes('60 秒'), itemInfo(menuItem('A', 11)), '1v1~5v5; at least two players; 60 seconds');
  bots.A.closeWindow(bots.A.currentWindow);
  for (const id of ids) await chat(id, '/pvp join mygo', 150);
  await waitUntil(() => ids.every(id => packs[id]) && ids.every(id => chats.slice(chatStart).some(entry =>
    entry.id === id && entry.message.includes('MyGO 资源包加载成功'))), 20000, 'both downloaded resource packs acknowledged by server');
  await chat('A', '/pvp join mygo', 150);
  await sleep(1500);
  await chat('A', '/pvp');
  check('one ready player remains queued without entering a match',
    bots.A.testWorldName === lobby.A && glows(menuItem('A', 11)) && menuItem('A', 11)?.count === 1
      && !bots.A.inventory.items().some(item => item.name === 'iron_pickaxe'),
    {worldName: bots.A.testWorldName, queueButton: itemInfo(menuItem('A', 11))}, 'one queued player in lobby');
  bots.A.closeWindow(bots.A.currentWindow);
  const joinedAt = Date.now();
  await chat('B', '/pvp join mygo', 150);
  emit('twoPlayerAutoWaiting', {joinedAt, timeoutMs: 90000, forcedStart: false});
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
    90000, 'normal sixty-second queue automatically starts 1v1');
  const elapsedMs = Date.now() - joinedAt;
  const initial = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  check('automatic 1v1 waits the normal fill interval without force-start',
    elapsedMs >= 58000 && elapsedMs <= 90000, {elapsedMs, forcedStart: false}, 'about 60 seconds, at most 90 seconds including map loading');
  check('two ready players automatically enter first-round BUY',
    ids.every(id => initial[id].round === 1 && initial[id].phase === 'BUY' && initial[id].alive
      && initial[id].hp === 100 && initial[id].cash === 1600 && bots[id].testWorldName === 'pvp:arena'),
    {initial, worlds: Object.fromEntries(ids.map(id => [id, bots[id].testWorldName]))}, 'round 1 BUY, alive, 100 HP, $1600, arena world');
  check('automatic 1v1 assigns opposite teams and sides',
    initial.A.team !== initial.B.team && new Set(ids.map(id => initial[id].side)).size === 2,
    ids.map(id => ({id, team: initial[id].team, side: initial[id].side})), 'one T and one CT');
  const messages = chats.slice(chatStart);
  for (const id of ids) {
    const joined = messages.filter(entry => entry.id === id && entry.message.includes('已加入 MyGO'));
    const opening = messages.filter(entry => entry.id === id && !entry.message.includes('已加入 MyGO')
      && entry.message.includes('左键点射') && entry.message.includes('右键瞄准'));
    check(`${id} queue and opening messages use current mouse controls`,
      joined.some(entry => entry.message.includes('至少两人') && entry.message.includes('左键点射') && entry.message.includes('右键瞄准'))
        && opening.length > 0, {joined, opening}, 'left-click burst, right-click aim');
  }
  check('two-player messages contain no obsolete firing or Shift aiming instructions',
    !messages.some(entry => entry.message.includes('右键点射') || /Shift\s*瞄准/.test(entry.message)),
    messages.filter(entry => entry.message.includes('右键点射') || /Shift\s*瞄准/.test(entry.message)), 'no obsolete controls');
  await waitPhase('A', 'LIVE');
  for (const id of ids) {
    await select(id, 1);
    await bots[id].lookAt(bots[id].entity.position.offset(0, -1, 0), true);
    await scope(id, true);
    const aimed = await status(id);
    check(`${id} right click aims without firing the free pistol`,
      aimed.scoped && aimed.alive && aimed.hp === 100 && aimed.ammo === 12
        && bots[id].inventory.slots[5]?.name === 'carved_pumpkin', aimed, 'scoped, alive, 100 HP, 12 rounds');
    await trigger(id);
    const fired = await status(id);
    check(`${id} left click fires one pistol round without self death`,
      fired.scoped && fired.alive && fired.hp === 100 && fired.ammo === 11, fired, 'scoped, alive, 100 HP, 11 rounds');
  }
  for (const id of ids) await chat(id, '/hub', 250);
  await sleep(500);
  for (const id of ids) {
    check(`${id} leaving 1v1 restores lobby inventory and removes scope`,
      bots[id].testWorldName === lobby[id] && bots[id].inventory.slots[5]?.name === 'golden_helmet'
        && bots[id].inventory.items().some(item => item.name === 'apple' && item.count === 3)
        && !bots[id].inventory.items().some(item => item.name === 'iron_pickaxe') && !packs[id],
      snapshot(id), 'original lobby dimension, gold helmet and three apples; no guns, scope or MyGO pack');
  }
  const summary = {elapsedMs, forcedStart: false, passed: assertions.filter(result => result.pass).length,
    failed: assertions.filter(result => !result.pass).length, renderingVerified: false};
  emit('twoPlayerAutoComplete', summary);
  return {initial, ...summary};
}

// Reason: the old smoke scenario encoded the PUBG arsenal and quick-round
// economy. Exercise the CS:GO replacement with real earnings and purchases,
// including timing boundaries, rather than manufacturing money in debug code.
function csgoSendLook(id) {
  // Mineflayer look(force=true) only changes its local orientation; its next
  // physics update sends the packet later. Emit look now so a following swing
  // cannot reach the server before the intended aim direction.
  const entity = bots[id].entity;
  bots[id]._client.write('look', {yaw: (Math.PI - entity.yaw) * 180 / Math.PI,
    pitch: -entity.pitch * 180 / Math.PI, onGround: true});
}
async function csgoLookAt(id, point) {
  await bots[id].lookAt(point, true); csgoSendLook(id); await sleep(80);
}
async function csgoLook(id, yaw, pitch) {
  await bots[id].look(yaw, pitch, true); csgoSendLook(id); await sleep(80);
}
async function csgoState(id, predicate, timeout = 10000, label = 'CS:GO state') {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const state = await status(id);
    if (predicate(state)) return state;
    await sleep(120);
  }
  throw new Error(`Timeout waiting for ${label} (${id})`);
}
async function csgoScopeClick(id) {
  // Always send each click; the previous Boolean helper cannot distinguish
  // first scope, second scope and the third click that closes the optic.
  await sleep(350); bots[id].activateItem(); await sleep(180);
  return status(id);
}
function csgoFov(id, state) {
  return 70 * ((state.moveSpeed / abilities[id]?.walkingSpeed + 1) / 2);
}
async function csgoPlant(id, site, cash) {
  await teleport(id, site); await select(id, 5);
  bots[id].setControlState('sneak', true); await sleep(150);
  const startedAt = Date.now(); bots[id].activateItem();
  await sleep(2600);
  const early = await status(id);
  check('C4 has not completed before the 3.2 second channel', !early.bombPlanted,
    {elapsedMs: Date.now() - startedAt, state: early}, 'still installing before 3.2 seconds');
  const planted = await csgoState(id, state => state.bombPlanted, 2500, '3.2 second C4 installation');
  const elapsedMs = Date.now() - startedAt;
  check('C4 installation uses 3.2 seconds and starts a 40 second fuse', elapsedMs >= 3100 && elapsedMs < 4500
    && planted.remainingTicks <= 800 && planted.remainingTicks > 760 && planted.cash === cash + 300,
    {elapsedMs, planted}, '3.2 seconds; 40 second fuse; planter +$300');
  bots[id].setControlState('sneak', false);
  return planted;
}
async function csgoDefuse(id, site, seconds, cash) {
  await teleport(id, {...site, z: site.z + 1}); await select(id, 5);
  bots[id].setControlState('sneak', true); await sleep(150);
  const startedAt = Date.now(); bots[id].activateItem();
  await sleep((seconds - 1.1) * 1000);
  const early = await status(id);
  check(`${seconds} second defuse has not completed one second early`, early.phase === 'LIVE' && early.bombPlanted,
    {elapsedMs: Date.now() - startedAt, state: early}, 'still defusing');
  const result = await csgoState(id, state => state.phase === 'POST', 2600, `${seconds} second defuse`);
  const elapsedMs = Date.now() - startedAt;
  check(`${seconds} second defuse pays personal and bomb-defuse victory rewards`, elapsedMs >= seconds * 1000 - 100
    && elapsedMs < seconds * 1000 + 1600 && result.cash === cash + 300 + 3500,
    {elapsedMs, result}, `${seconds}s; personal $300 plus team $3500`);
  bots[id].setControlState('sneak', false);
  return result;
}
async function csgo() {
  const ids = Object.keys(bots);
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'CS:GO four clients');
  check('CS:GO regression has four real 1.21.1 clients', ids.length === 4 && ids.every(id => bots[id].version === '1.21.1'), ids, 'ABCD');
  for (const id of ids) await chat(id, '/hub', 100);
  await sleep(6500);
  const lobby = Object.fromEntries(ids.map(id => [id, bots[id].testWorldName]));
  for (const id of ids) {
    await chat('A', `/item replace entity MyGoCheck${id} armor.head with minecraft:golden_helmet`, 100);
    await chat('A', `/item replace entity MyGoCheck${id} hotbar.7 with minecraft:apple 3`, 100);
  }
  const packAt = chats.length;
  for (const id of ids) await chat(id, '/pvp join mygo', 180);
  await waitUntil(() => ids.every(id => packs[id] && chats.slice(packAt).some(entry => entry.id === id
    && entry.message.includes('MyGO 资源包加载成功'))), 25000, 'all CS:GO resource packs acknowledged by server');
  for (const id of ids) await chat(id, '/pvp join mygo', 100);
  await chat('A', '/pvp start', 100);
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 30000, 'CS:GO opening loadouts');
  for (const id of ids) await select(id, 1);
  const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const terrorists = ids.filter(id => opening[id].side === 'T'), cts = ids.filter(id => opening[id].side === 'CT');
  check('opening is balanced 2v2 with $800 and 100 HP', terrorists.length === 2 && cts.length === 2
    && Object.values(opening).every(state => state.cash === 800 && state.hp === 100 && state.phase === 'BUY'), opening, 'two T / two CT; $800; 100 HP; freeze');
  for (const id of terrorists) check(`${id} T starts with Glock-18 and 20/120 ammunition`, opening[id].gun === 'GLOCK'
    && opening[id].pistol === 'GLOCK' && opening[id].ammo === 20 && opening[id].reserve === 120, opening[id], 'GLOCK 20/120');
  for (const id of cts) check(`${id} CT starts with USP-S and 12/24 ammunition`, opening[id].gun === 'USP_S'
    && opening[id].pistol === 'USP_S' && opening[id].ammo === 12 && opening[id].reserve === 24, opening[id], 'USP_S 12/24');
  const firstFreeze = opening[ids[0]], freezeStart = Date.now() - (300 - firstFreeze.remainingTicks) * 50;
  check('freeze and independent buy deadline are initialized to 15 plus 20 seconds', firstFreeze.remainingTicks <= 300
    && firstFreeze.remainingTicks > 240 && Math.abs(firstFreeze.buyTicks - firstFreeze.remainingTicks - 400) <= 1,
    firstFreeze, '15 second freeze; purchase deadline 20 seconds after unfreeze');
  let carrier = terrorists.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  const gunner = terrorists.find(id => id !== carrier), defuser = cts[0], rifleman = cts[1];
  check('one of the two T players receives C4', !!carrier && terrorists.filter(id => bots[id].inventory.items().some(item => item.name === 'redstone')).length === 1, carrier, 'one carrier');
  const site = opening[carrier].sites.at(-1), range = opening[carrier].range;
  await chat(gunner, '/pvp mygo', 180);
  check('54-slot shop exposes all 34 guns without overflowing equipment slots', bots[gunner].currentWindow?.slots.length === 90
    && bots[gunner].currentWindow.slots.slice(9, 43).every(item => item?.name === 'iron_pickaxe')
    && stringify(bots[gunner].currentWindow.slots.slice(9, 43)).includes('Negev'),
    {size: bots[gunner].currentWindow?.slots.length, guns: bots[gunner].currentWindow?.slots.slice(9, 43).map(itemInfo)}, '54 shop plus 36 player slots, 34 guns');
  // P250 is enum ordinal 3, immediately following the three starting pistols.
  await bots[gunner].clickWindow(12, 0, 0); await sleep(180);
  bots[gunner].closeWindow(bots[gunner].currentWindow); await select(gunner, 1);
  let state = await status(gunner);
  check('buying a P250 replaces only the sidearm and costs $300', state.pistol === 'P250' && state.gun === 'P250'
    && state.cash === 500 && state.ammo === 13 && state.reserve === 26, state, 'P250 13/26; cash $500');
  await chat(gunner, '/pvp mygo buy usp_s', 100);
  check('T cannot buy the CT USP-S', (await status(gunner)).cash === 500 && (await status(gunner)).pistol === 'P250', await status(gunner), 'unchanged P250 and $500');
  await chat(defuser, '/pvp mygo buy glock', 100);
  check('CT cannot buy the T Glock-18', (await status(defuser)).cash === 800 && (await status(defuser)).pistol === 'USP_S', await status(defuser), 'unchanged USP-S and $800');
  await chat(rifleman, '/pvp mygo buy vest', 100);
  check('pistol-round vest costs $650', (await status(rifleman)).cash === 150 && (await status(rifleman)).armor === 100, await status(rifleman), '$150 and 100 armor');
  const live = await waitPhase(defuser, 'LIVE', 20000), freezeElapsedMs = Date.now() - freezeStart;
  check('unfreezing starts a 115-second round and separate 20-second buying period', freezeElapsedMs >= 14500 && freezeElapsedMs < 17500
    && live.remainingTicks <= 2300 && live.remainingTicks > 2240 && live.buyTicks <= 400 && live.buyTicks > 340,
    {freezeElapsedMs, live}, '15 second freeze, 115 second round, 20 second live buying');
  await chat(defuser, '/pvp mygo buy p250', 100); await select(defuser, 1);
  state = await status(defuser);
  check('buying remains possible in spawn after unfreezing', state.pistol === 'P250' && state.cash === 500, state, 'P250; $500 during LIVE');
  await teleport(defuser, site); await chat(defuser, '/pvp mygo buy flash', 100);
  check('leaving the buy zone denies purchases before the timer expires', (await status(defuser)).cash === 500, await status(defuser), '$500 unchanged outside buy zone');
  await teleport(defuser, opening[defuser].spawn);
  const buying = await status(defuser);
  await sleep(Math.max(0, buying.buyTicks * 50 + 200));
  await chat(defuser, '/pvp mygo buy flash', 100);
  state = await status(defuser);
  check('the 20-second live purchase deadline denies purchases even back at spawn', state.buyTicks === 0 && state.cash === 500
    && state.phase === 'LIVE' && !bots[defuser].currentWindow, state, 'LIVE, no remaining buy ticks, $500 unchanged');
  await csgoPlant(carrier, site, 800);
  const firstPost = await csgoDefuse(defuser, site, 10, 500);
  const firstResult = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  check('first T loss starts at $1900 and adds $800 to every T after a defused plant', firstResult[carrier].cash === 3800
    && firstResult[gunner].cash === 3200 && terrorists.every(id => firstResult[id].lossLevel === 2), firstResult,
    'carrier 800+300+1900+800=3800, partner 500+1900+800=3200; next loss level 2');
  check('CT victory lowers loss level by one and pays $3500 to both CTs', firstPost.cash === 4300
    && firstResult[rifleman].cash === 3650 && cts.every(id => firstResult[id].lossLevel === 0), firstResult,
    'defuser 500+300+3500=4300; partner 150+3500=3650; next loss level 0');
  await waitPhase(defuser, 'BUY', 10000);
  await chat(gunner, '/pvp mygo buy ak47', 100); await select(gunner, 0);
  check('saved loss money buys the T AK-47 at $2700', (await status(gunner)).gun === 'AK47'
    && (await status(gunner)).cash === 500, await status(gunner), 'AK47; cash $500');
  await chat(rifleman, '/pvp mygo buy aug', 100); await select(rifleman, 0);
  check('CT can buy its $3300 AUG after winning', (await status(rifleman)).gun === 'AUG'
    && (await status(rifleman)).cash === 350, await status(rifleman), 'AUG; cash $350');
  await chat(defuser, '/pvp mygo buy kit', 100);
  check('defuse kit costs $400', (await status(defuser)).cash === 3900, await status(defuser), '$4300 - $400 = $3900');
  await waitPhase(gunner, 'LIVE', 20000);
  await teleport(gunner, range); await teleport(rifleman, {...range, x: range.x + 6});
  await sleep(400);
  state = await csgoScopeClick(gunner);
  check('right click does not attach an optic to AK-47', state.scopeLevel === 0 && !state.scoped
    && bots[gunner].inventory.slots[5]?.name !== 'carved_pumpkin', state, 'unscoped AK47');
  const armorBefore = await status(rifleman);
  await csgoLookAt(gunner, bots[rifleman].entity.position.offset(0, .9, 0));
  await trigger(gunner, 1, 300);
  const akShot = await status(gunner), armorAfter = await status(rifleman);
  check('left click AK-47 fires one bullet and preserves its shooter', akShot.ammo === 29 && akShot.alive && akShot.hp === 100,
    akShot, '30 to 29, alive at 100 HP');
  check('a real armored body hit consumes armor as well as HP', armorAfter.hp < armorBefore.hp && armorAfter.hp > 0
    && armorAfter.armor < armorBefore.armor && armorAfter.armor > 0, {armorBefore, armorAfter}, 'armor and health both decrease without death');
  const augNormal = {...abilities[rifleman]}, augAimed = await csgoScopeClick(rifleman);
  check('AUG has one 45-degree scope stage with unchanged server abilities', augAimed.scopeLevel === 1
    && augAimed.scopeFov === 45 && augAimed.scoped && Math.abs(csgoFov(rifleman, augAimed) - 45) < .02
    && Math.abs(augAimed.walkSpeed - .1) < 1e-6 && abilities[rifleman].flags === augNormal.flags
    && abilities[rifleman].flyingSpeed === augNormal.flyingSpeed && augAimed.usingItem === false,
    {state: augAimed, packet: abilities[rifleman], computedFov: csgoFov(rifleman, augAimed)}, 'one 45-degree stage; no item-use lock or permission change');
  await csgoLook(rifleman, 0, -1.2); await trigger(rifleman, 1, 300);
  check('scoped AUG left click remains usable', (await status(rifleman)).ammo === 29 && (await status(rifleman)).alive, await status(rifleman), '29 bullets, alive');
  state = await csgoScopeClick(rifleman);
  check('second AUG right click closes its only scope stage', state.scopeLevel === 0 && !state.scoped
    && Math.abs(abilities[rifleman].walkingSpeed - .1) < 1e-6, state, 'closed; client walk baseline .1');
  carrier = terrorists.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  const secondPlantCash = (await status(carrier)).cash;
  await csgoPlant(carrier, site, secondPlantCash);
  const secondPost = await csgoDefuse(defuser, site, 5, 3900);
  check('second T loss advances to $2400 instead of resetting after a CT win', (await status(carrier)).cash === secondPlantCash + 300 + 2400 + 800
    && (await status(carrier)).lossLevel === 3, await status(carrier), '$300 planter + $2400 loss + $800 planted loss; next level 3');
  await waitPhase(defuser, 'BUY', 10000);
  const carriedArmor = await status(rifleman);
  check('surviving armor damage persists across rounds', carriedArmor.armor < 100 && carriedArmor.armor > 0
    && carriedArmor.hp === 100 && carriedArmor.gun === 'AUG', carriedArmor, 'HP resets, worn armor and AUG persist');
  await chat(rifleman, '/pvp mygo buy vest', 100);
  const repaired = await status(rifleman);
  check('repairing worn armor costs $650', repaired.armor === 100 && repaired.cash === carriedArmor.cash - 650,
    {carriedArmor, repaired}, '100 armor for $650');
  await chat(rifleman, '/pvp mygo buy helmet', 100);
  check('adding a helmet to full armor costs only $350', (await status(rifleman)).helmet
    && (await status(rifleman)).cash === repaired.cash - 350, await status(rifleman), 'helmet for $350');
  await chat(defuser, '/pvp mygo buy awp', 100); await select(defuser, 0);
  const awpBought = await status(defuser);
  check('earned economy buys the final CS:GO AWP with a five-round magazine', awpBought.gun === 'AWP'
    && awpBought.cash === secondPost.cash - 4750 && awpBought.ammo === 5 && awpBought.reserve === 30,
    awpBought, '$4750; AWP 5/30');
  await waitPhase(defuser, 'LIVE', 20000); await select(defuser, 0);
  const awpNormal = {...abilities[defuser]}, awpUnscoped = await status(defuser);
  const awpFirst = await csgoScopeClick(defuser);
  check('AWP first right click selects 40 degrees', awpFirst.scopeLevel === 1 && awpFirst.scopeFov === 40
    && awpFirst.scoped && Math.abs(csgoFov(defuser, awpFirst) - 40) < .02,
    {state: awpFirst, packet: abilities[defuser]}, 'scope level 1 at 40 degrees');
  const awpSecond = await csgoScopeClick(defuser);
  check('AWP second right click selects a real 10-degree view', awpSecond.scopeLevel === 2 && awpSecond.scopeFov === 10
    && Math.abs(csgoFov(defuser, awpSecond) - 10) < .02 && awpSecond.usingItem === false
    && Math.abs(awpSecond.walkSpeed - .1) < 1e-6 && awpSecond.moveSpeed > 0
    && abilities[defuser].flags === awpNormal.flags && abilities[defuser].flyingSpeed === awpNormal.flyingSpeed,
    {state: awpSecond, packet: abilities[defuser], computedFov: csgoFov(defuser, awpSecond)}, 'scope level 2 at 10 degrees; positive server movement; unchanged permissions');
  await csgoLook(defuser, 0, -1.2); await trigger(defuser, 1, 300);
  const awpFired = await status(defuser);
  check('AWP fires from second scope and temporarily unzooms to cycle its bolt', awpFired.ammo === 4 && awpFired.hp === 100
    && awpFired.alive && awpFired.scopeLevel === 0 && !awpFired.scoped, awpFired, '5 to 4 bullets, 100 HP, temporarily unscoped');
  const awpRescoped = await csgoState(defuser, value => value.scopeLevel === 2, 2500, 'AWP automatic return to previous scope stage');
  check('AWP returns to the previous 10-degree scope after its firing cycle', awpRescoped.ammo === 4 && awpRescoped.scoped
    && Math.abs(csgoFov(defuser, awpRescoped) - 10) < .02, {state: awpRescoped, packet: abilities[defuser]}, 'level 2 restores automatically without another shot');
  const awpClosed = await csgoScopeClick(defuser);
  check('AWP third right click returns to the original view', awpClosed.scopeLevel === 0 && !awpClosed.scoped
    && Math.abs(abilities[defuser].walkingSpeed - awpNormal.walkingSpeed) < 1e-6
    && Math.abs(awpClosed.moveSpeed - awpUnscoped.moveSpeed) < 1e-6
    && !bots[defuser].inventory.slots[5], awpClosed, 'unscoped; empty in-match head slot, original movement and ability baseline');
  await csgoScopeClick(defuser); await trigger(defuser, 1, 120); await select(defuser, 1);
  await sleep(1600);
  state = await status(defuser);
  check('switching to a sidearm during the AWP bolt cycle cancels automatic rescope', state.scopeLevel === 0 && !state.scoped
    && Math.abs(abilities[defuser].walkingSpeed - .1) < 1e-6, state, 'unscoped P250; client baseline .1');
  state = await csgoScopeClick(defuser);
  check('right click on P250 cannot reopen the AWP scope', state.scopeLevel === 0 && !state.scoped, state, 'unscoped P250');
  await select(defuser, 0); await teleport(defuser, range); await teleport(gunner, {...range, x: range.x + 6});
  await csgoScopeClick(defuser); await csgoScopeClick(defuser); await sleep(500);
  await csgoLookAt(defuser, bots[gunner].entity.position.offset(0, .9, 0));
  const beforeKill = await status(defuser);
  await trigger(defuser, 1, 350);
  const afterKill = await status(defuser), victim = await status(gunner);
  check('real AWP elimination awards its $100 weapon reward', !victim.alive && victim.hp === 0
    && afterKill.kills === beforeKill.kills + 1 && afterKill.cash === beforeKill.cash + 100,
    {beforeKill, afterKill, victim}, 'one death; AWP +$100');
  for (const id of ids) { bots[id].setControlState('sneak', false); await chat(id, '/hub', 200); }
  await sleep(300);
  for (const id of ids) check(`${id} exits with original lobby inventory, helmet and abilities`, bots[id].testWorldName === lobby[id]
    && bots[id].inventory.slots[5]?.name === 'golden_helmet'
    && bots[id].inventory.items().some(item => item.name === 'apple' && item.count === 3)
    && !bots[id].inventory.items().some(item => item.name === 'iron_pickaxe') && !packs[id]
    && Math.abs(abilities[id].walkingSpeed - .1) < 1e-6, snapshot(id), 'gold helmet, three apples, no MyGO equipment/pack, client baseline .1');
  const summary = {passed: assertions.filter(result => result.pass).length, failed: assertions.filter(result => !result.pass).length,
    rounds: 3, fundedByNormalEconomy: true, renderingVerified: false};
  emit('csgoComplete', summary);
  return {opening, ...summary};
}

// Reason: metadata tests cannot prove the real raycast selects stomach/legs,
// truncates final bullet damage or preserves AK/M4/AWP lethal thresholds.
// These are actual left-click packets against full server sessions. Money is
// earned through ordinary rounds; vanilla /tick only shortens idle deadlines.
async function damageRegression() {
  const ids = Object.keys(bots), startAssertions = assertions.length;
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'damage clients');
  check('damage regression has four isolated 1.21.1 clients', ids.length === 4
    && ids.every(id => bots[id].version === '1.21.1'), ids, 'four players');
  let rateChanged = false;
  const rate = async value => { await chat('A', `/tick rate ${value}`, 80); rateChanged = value !== 20; };
  const freezeToLive = async id => {
    await rate(200);
    await csgoFastState(id, state => state.phase === 'LIVE', 6000, 'damage round unfreeze');
    await rate(20);
  };
  const nextBuy = async id => {
    await rate(200);
    await csgoFastState(id, state => state.phase === 'BUY', 16000, 'next damage round');
    await rate(20);
  };
  const shoot = async (shooter, target, height, expectedLoss, name, expectedArmorLoss = 0) => {
    const before = await status(target), gunBefore = await status(shooter);
    // More than two weapon cycles has elapsed before every rifle input, so the
    // pure-server automatic-input inference cannot add another bullet here.
    await sleep(650);
    await csgoLookAt(shooter, bots[target].entity.position.offset(0, height, 0));
    await trigger(shooter, 1, 200);
    const after = await status(target), gunAfter = await status(shooter);
    check(`${name}: exact health damage`, after.hp === Math.max(0, before.hp - expectedLoss),
      {before, after, expectedLoss}, `HP ${Math.max(0, before.hp - expectedLoss)}`);
    check(`${name}: one actual bullet`, gunAfter.ammo === gunBefore.ammo - 1,
      {before: gunBefore.ammo, after: gunAfter.ammo}, 'one bullet consumed');
    if (after.alive) check(`${name}: exact armor damage`, after.armor === Math.max(0, before.armor - expectedArmorLoss),
      {before: before.armor, after: after.armor}, `${expectedArmorLoss} armor lost`);
    return after;
  };
  const setupPair = async (shooter, target, range) => {
    await teleport(shooter, range);
    await teleport(target, {...range, x: range.x + 2});
    await sleep(250);
  };
  try {
    for (const id of ids) await chat(id, '/hub', 80);
    await sleep(6500);
    const readyAt = chats.length;
    for (const id of ids) await chat(id, '/pvp join mygo', 120);
    await waitUntil(() => ids.every(id => packs[id] && chats.slice(readyAt).some(entry => entry.id === id
      && entry.message.includes('MyGO 资源包加载成功'))), 25000, 'damage resource packs');
    for (const id of ids) await chat(id, '/pvp join mygo', 80);
    await chat('A', '/pvp start', 100);
    await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
      30000, 'damage starting loadouts');
    const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
    const ts = ids.filter(id => opening[id].side === 'T'), cts = ids.filter(id => opening[id].side === 'CT');
    check('damage match is balanced and funded normally', ts.length === 2 && cts.length === 2
      && Object.values(opening).every(state => state.cash === 800 && state.hp === 100), opening, '$800; 100 HP; 2v2');
    const [tGun, tTarget] = ts, [ctTarget, ctGun] = cts, range = opening[tGun].range;
    await chat(ctTarget, '/pvp mygo buy vest', 100);
    await select(tGun, 1); await select(ctGun, 1);
    await freezeToLive(tGun);
    await setupPair(tGun, ctTarget, range);
    // At a two-block origin separation the near hitbox is 1.7 blocks away;
    // tiny angle/spread differences stay safely within each integer interval.
    await shoot(tGun, ctTarget, .4, 21, 'Glock armored legs');
    await shoot(tGun, ctTarget, 1.1, 13, 'Glock armored chest', 8);
    await shoot(tGun, ctTarget, .85, 17, 'Glock armored stomach', 10);
    await shoot(tGun, ctTarget, 1.6, 117, 'Glock head without helmet');
    await setupPair(ctGun, tTarget, range);
    await shoot(ctGun, tTarget, 1.1, 34, 'USP-S unarmored chest');
    await shoot(ctGun, tTarget, .4, 25, 'USP-S unarmored legs');
    bots[tTarget].setControlState('sneak', true); await sleep(250);
    // Near-face y~.617 is a standing leg but a crouched stomach: this proves
    // hit regions follow the actual pose-sized bounding box.
    await shoot(ctGun, tTarget, .44, 43, 'USP-S crouched unarmored stomach');
    bots[tTarget].setControlState('sneak', false);
    await setupPair(ctGun, tGun, range);
    await shoot(ctGun, tGun, 1.6, 138, 'USP-S unarmored head');

    // A normal CT elimination funds AK/M4 and helmets. No product money/HP hooks.
    await nextBuy(tGun);
    await chat(tGun, '/pvp mygo buy ak47', 100);
    await chat(tTarget, '/pvp mygo buy helmet', 100);
    await chat(ctGun, '/pvp mygo buy m4a4', 100);
    await chat(ctGun, '/pvp mygo buy helmet', 100);
    await chat(ctTarget, '/pvp mygo buy helmet', 100);
    await select(tGun, 0); await select(ctGun, 0);
    const equipment = await Promise.all(ids.map(id => status(id)));
    check('round income funds AK-47, M4A4 and helmeted targets', equipment.some(state => state.gun === 'AK47')
      && equipment.some(state => state.gun === 'M4A4')
      && (await status(tTarget)).helmet && (await status(ctGun)).helmet && (await status(ctTarget)).helmet,
      equipment, 'real purchased rifles and helmets');
    await freezeToLive(tGun);
    await setupPair(ctGun, tTarget, range);
    const m4Target = await shoot(ctGun, tTarget, 1.6, 92, 'M4A4 helmeted head', 20);
    check('M4A4 cannot one-shot a helmet', m4Target.alive && m4Target.hp === 8, m4Target, '8 HP alive');
    await setupPair(tGun, ctGun, range);
    const akTarget = await shoot(tGun, ctGun, 1.6, 111, 'AK-47 helmeted head');
    check('AK-47 one-shots a helmet at close range', !akTarget.alive, akTarget, 'dead');
    await setupPair(tGun, ctTarget, range);
    await shoot(tGun, ctTarget, 1.1, 27, 'AK-47 armored chest', 5);
    await shoot(tGun, ctTarget, .85, 34, 'AK-47 armored stomach', 6);
    await shoot(tGun, ctTarget, .4, 26, 'AK-47 armored legs');
    await shoot(tGun, ctTarget, 1.6, 111, 'AK-47 finishes helmeted opponent');
    await nextBuy(tGun);
    // Third-round timeout supplies the losing CT economy with enough cash for
    // AWP; both teams retain their real weapons/armor according to game rules.
    await freezeToLive(tGun);
    await nextBuy(tGun);
    await chat(ctTarget, '/pvp mygo buy awp', 120); await select(ctTarget, 0);
    check('normal four-round economy funds AWP', (await status(ctTarget)).gun === 'AWP',
      await status(ctTarget), 'AWP 5/30');
    await freezeToLive(tGun);
    await setupPair(ctTarget, tTarget, range);
    await csgoScopeClick(ctTarget); await sleep(500);
    const armorBeforeAwpLeg = (await status(tTarget)).armor;
    const awpLeg = await shoot(ctTarget, tTarget, .4, 86, 'AWP armored legs');
    check('AWP leg shot survives and ignores kevlar', awpLeg.alive && awpLeg.hp === 14 && awpLeg.armor === armorBeforeAwpLeg,
      awpLeg, `14 HP; unchanged ${armorBeforeAwpLeg} armor`);
    // The previous leg-shot target remains alive at 14 HP. Move it off the
    // firing lane before placing a different enemy in the same test position.
    await teleport(tTarget, {...range, z: range.z + 4});
    await setupPair(ctTarget, tGun, range);
    // Bolt-cycle auto-rescope is allowed to finish before the chest shot.
    await sleep(1600);
    const awpChest = await shoot(ctTarget, tGun, 1.1, 114, 'AWP unarmored chest');
    check('AWP chest shot kills a full-health opponent', !awpChest.alive, awpChest, 'dead');
    const results = assertions.slice(startAssertions);
    const summary = {passed: results.filter(result => result.pass).length,
      failed: results.filter(result => !result.pass).length, rounds: 4, fundedByNormalEconomy: true,
      renderingVerified: false};
    emit('damageRegressionComplete', summary);
    return summary;
  } finally {
    if (rateChanged) await rate(20);
    for (const id of ids) if (bots[id]._client.state === 'play') {
      bots[id].setControlState('sneak', false);
      await chat(id, '/hub', 100);
    }
  }
}

// Reason: throwing strength, silent movement and positional audio require
// actual input/physics packets; a resource ZIP or constants test cannot prove
// them. Use ordinary round income and a temporary platform above all map blocks.
async function utilitiesRegression() {
  const ids = Object.keys(bots), startAssertions = assertions.length;
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'utility clients');
  check('utilities use four isolated 1.21.1 clients', ids.length === 4
    && ids.every(id => bots[id].version === '1.21.1'), ids, 'four clients');
  let platform, lane, rateChanged = false;
  const rate = async value => { await chat('A', `/tick rate ${value}`, 80); rateChanged = value !== 20; };
  const grenadeCount = (state, type) => Number(state.grenades.match(new RegExp(`${type}=(\\d+)`))?.[1] || 0);
  const namedSounds = (id, since, name) => soundEvents.filter(row => row.id === id && row.at >= since && row.name === name);
  const fill = async (a, b, state) => chat('A', `/execute in pvp:arena run fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${state}`, 180);
  const horizontal = vector => Math.hypot(vector.x, vector.z);
  const throwUtility = async (id, type, slot, strong, point) => {
    await select(id, slot);
    await csgoLook(id, Math.PI, point ?? 0);
    const before = await status(id), at = Date.now();
    const displays = new Set(Object.values(bots[id].entities).filter(entity => entity.name === 'item_display').map(entity => entity.id));
    if (strong) bots[id]._client.write('arm_animation', {hand: 0});
    else bots[id].activateItem();
    await waitUntil(() => Object.values(bots[id].entities).some(entity => entity.name === 'item_display' && !displays.has(entity.id)),
      2500, `${type} flying display`);
    const after = await status(id);
    check(`${strong ? 'left' : 'right'} click consumes exactly one ${type}`, grenadeCount(after, type) === grenadeCount(before, type) - 1,
      {before: before.grenades, after: after.grenades}, 'one grenade removed');
    check(`${strong ? 'left' : 'right'} click records ${strong ? 'STRONG' : 'WEAK'} throw`, after.throwMode === (strong ? 'STRONG' : 'WEAK')
      && after.throwType === type && after.activeGrenades === 1, after, 'correct mode; one active grenade');
    check(`${type} switches back to a gun after throwing`, bots[id].quickBarSlot === 0 || bots[id].quickBarSlot === 1,
      {slot: bots[id].quickBarSlot, held: itemInfo(bots[id].heldItem)}, 'primary or pistol selected');
    return {at, before, after};
  };
  const waitFuse = async (id, at, label) => {
    await sleep(Math.max(0, at + 950 - Date.now()));
    const early = await status(id);
    check(`${label} has not detonated before its 1.5 second fuse`, early.activeGrenades === 1,
      early, 'one grenade still flying');
    await sleep(Math.max(0, at + 1850 - Date.now()));
    const done = await status(id);
    check(`${label} detonates after its 1.5 second fuse`, done.activeGrenades === 0,
      done, 'no flying grenade');
  };
  const walk = async (id, duration, sneak = false, sprint = false) => {
    const bot = bots[id];
    bot.testAllowMovement = true; bot.physicsEnabled = true;
    bot.setControlState('sneak', sneak); bot.setControlState('sprint', sprint);
    await csgoLook(id, -Math.PI / 2, 0);
    await sleep(180);
    const before = bot.entity.position.clone();
    bot.setControlState('forward', true);
    await sleep(duration);
    bot.clearControlStates();
    await sleep(180);
    const after = bot.entity.position.clone();
    bot.physicsEnabled = false; bot.testAllowMovement = false;
    return {before, after, distance: Math.hypot(after.x - before.x, after.z - before.z)};
  };
  try {
    for (const id of ids) await chat(id, '/hub', 80);
    await sleep(6500);
    const packAt = chats.length;
    for (const id of ids) await chat(id, '/pvp join mygo', 130);
    await waitUntil(() => ids.every(id => packs[id] && chats.slice(packAt).some(row => row.id === id
      && row.message.includes('MyGO 资源包加载成功'))), 30000, 'utility pack acknowledgements');
    for (const id of ids) await chat(id, '/pvp join mygo', 80);
    await chat('A', '/pvp start', 100);
    await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
      30000, 'utility opening loadouts');
    const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
    const cts = ids.filter(id => opening[id].side === 'CT'), ts = ids.filter(id => opening[id].side === 'T');
    check('utility funding starts with balanced $800 pistol round', cts.length === 2 && ts.length === 2
      && Object.values(opening).every(state => state.cash === 800 && state.hp === 100), opening, '2v2 and $800');
    const [grenadier, friend] = cts, [target, away] = ts;
    await rate(200);
    await csgoFastState(grenadier, state => state.round === 2 && state.phase === 'BUY', 22000, 'natural timeout funds utilities');
    await rate(20);
    const funded = await status(grenadier);
    check('natural CT timeout victory funds utilities without injected money', funded.cash === 4050, funded, '$800 + $3250');
    for (const product of ['he', 'flash', 'flash', 'smoke', 'vest']) await chat(grenadier, `/pvp mygo buy ${product}`, 90);
    for (const product of ['he', 'vest']) await chat(friend, `/pvp mygo buy ${product}`, 90);
    await chat(target, '/pvp mygo buy vest', 90);
    const equipped = await status(grenadier);
    check('four-grenade loadout contains HE, two flashes and one smoke', equipped.grenades === '{HE=1, FLASH=2, SMOKE=1}'
      && equipped.cash === 2400 && equipped.armor === 100, equipped, '$1000 grenades + $650 vest');
    for (const product of ['flash', 'he', 'smoke']) await chat(grenadier, `/pvp mygo buy ${product}`, 70);
    const rejected = await status(grenadier);
    check('grenade carry caps reject extras without charging cash', rejected.grenades === equipped.grenades && rejected.cash === equipped.cash,
      rejected, 'unchanged loadout and cash');
    await select(grenadier, 4); bots[grenadier].activateItem(); await sleep(120);
    check('freeze phase cannot consume or throw a purchased grenade', grenadeCount(await status(grenadier), 'FLASH') === 2
      && (await status(grenadier)).activeGrenades === 0, await status(grenadier), 'two flashes; zero projectiles');
    await rate(200); await csgoFastState(grenadier, state => state.phase === 'LIVE', 6000, 'utility unfreeze'); await rate(20);
    const range = (await status(grenadier)).range;
    // The imported Dust II geometry ends at range.y +27. This floor at +28
    // only replaces air and keeps player feet (+29) inside the play bounds.
    platform = {a: {x: range.x - 4, y: range.y + 28, z: range.z - 4}, b: {x: range.x + 15, y: range.y + 28, z: range.z + 12}};
    const origin = {x: range.x, y: range.y + 29, z: range.z};
    for (const id of ids) await teleport(id, origin);
    await waitUntil(() => bots[grenadier].blockAt(new Vec3(platform.a.x, platform.a.y, platform.a.z)) !== null,
      5000, 'high utility platform chunk');
    check('temporary platform starts above original map geometry', [platform.a, platform.b, {x: range.x, y: platform.a.y, z: range.z}]
      .every(point => bots[grenadier].blockAt(new Vec3(point.x, point.y, point.z))?.name === 'air'), platform, 'air at platform corners and center');
    await fill(platform.a, platform.b, 'minecraft:stone');
    await teleport(grenadier, origin); await teleport(friend, {...origin, x: origin.x - 2});
    await teleport(target, {...origin, x: origin.x + 3}); await teleport(away, {...origin, x: origin.x + 6});
    const heTarget = await status(target), heOwner = await status(grenadier), heFriend = await status(friend);
    const he = await throwUtility(grenadier, 'HE', 3, false, -1.35);
    await waitFuse(grenadier, he.at, 'HE');
    const heAfter = await status(target), ownerAfter = await status(grenadier), friendAfter = await status(friend);
    check('HE hurts an armored enemy without destroying blocks', heAfter.hp < heTarget.hp && heAfter.hp > 0
      && heAfter.armor < heTarget.armor && bots[grenadier].blockAt(new Vec3(origin.x, platform.a.y, origin.z))?.name === 'stone',
      {before: heTarget, after: heAfter}, 'enemy HP/armor reduced; floor intact');
    check('HE damages its owner but does not damage a teammate', ownerAfter.hp < heOwner.hp && ownerAfter.alive && friendAfter.hp === heFriend.hp,
      {ownerBefore: heOwner.hp, ownerAfter: ownerAfter.hp, friendBefore: heFriend.hp, friendAfter: friendAfter.hp}, 'self damage; no team damage');

    // A full-height wall lies between the weak HE and the same enemy target.
    const wallA = {x: origin.x + 1, y: origin.y, z: origin.z - 3}, wallB = {x: origin.x + 1, y: origin.y + 4, z: origin.z + 6};
    await fill(wallA, wallB, 'minecraft:stone');
    await teleport(friend, origin); await teleport(grenadier, {...origin, x: origin.x - 3});
    const coveredBefore = await status(target);
    const coveredHe = await throwUtility(friend, 'HE', 3, false, -1.35);
    await waitFuse(friend, coveredHe.at, 'covered HE');
    check('solid cover blocks HE damage to the enemy', (await status(target)).hp === coveredBefore.hp,
      {before: coveredBefore.hp, after: (await status(target)).hp}, 'same HP behind cover');
    await fill(wallA, wallB, 'minecraft:air');

    await teleport(grenadier, origin); await teleport(friend, {...origin, x: origin.x - 3});
    await teleport(target, {...origin, x: origin.x + 2, z: origin.z + 2}); await teleport(away, {...origin, x: origin.x - 2, z: origin.z + 2});
    const weak = await throwUtility(grenadier, 'FLASH', 4, false, 0);
    // A hold/repeated-use packet occurs after auto-selection, and must not
    // silently spend the second flash. Real left-click uses the gun meanwhile.
    bots[grenadier].activateItem(); await sleep(80);
    const held = await status(grenadier);
    check('held right-click cannot automatically throw the second flash', grenadeCount(held, 'FLASH') === 1,
      held, 'one flash remains');
    const flying = Object.values(bots[grenadier].entities).find(entity => entity.name === 'item_display');
    const flashPoint = flying?.position.clone() || new Vec3(origin.x + .5, origin.y + .5, origin.z + 3.5);
    await csgoLookAt(target, flashPoint);
    await csgoLookAt(away, bots[away].entity.position.scaled(2).minus(flashPoint));
    await waitFuse(grenadier, weak.at, 'weak flash');
    const durations = id => effectEvents.filter(row => row.id === id && row.at >= weak.at
      && row.packet.entityId === bots[id].entity.id).map(row => row.packet.duration || 0);
    const facingDuration = Math.max(0, ...durations(target)), awayDuration = Math.max(0, ...durations(away));
    check('flash affects a visible opponent and facing it lasts longer', facingDuration > awayDuration && facingDuration > 0,
      {facingDuration, awayDuration}, 'facing duration greater than facing away');
    const strong = await throwUtility(grenadier, 'FLASH', 4, true, 0);
    check('left high throw is faster and starts higher than right low throw', horizontal(strong.after.throwVelocity) > horizontal(weak.after.throwVelocity) * 2
      && strong.after.throwVelocity.y > weak.after.throwVelocity.y && strong.after.throwOrigin.y > weak.after.throwOrigin.y,
      {strong: strong.after, weak: weak.after}, 'stronger forward/upward velocity; higher release point');
    await waitFuse(grenadier, strong.at, 'strong flash');
    check('both flash inputs leave no extra grenade', grenadeCount(await status(grenadier), 'FLASH') === 0,
      await status(grenadier), 'zero flashes');

    const smoke = await throwUtility(grenadier, 'SMOKE', 6, false, -1.35);
    const smoky = await csgoState(grenadier, state => state.smokes === 1, 16000, 'settled smoke cloud');
    const smokeAt = Date.now();
    check('smoke waits for rest and creates client-visible screens', smoky.activeGrenades === 0
      && Object.values(bots[grenadier].entities).filter(entity => entity.name === 'item_display').length >= 5,
      {smoky, elapsedMs: smokeAt - smoke.at}, 'one smoke cloud and at least five display screens');
    await sleep(16500);
    check('smoke remains active before eighteen seconds', (await status(grenadier)).smokes === 1,
      {elapsedMs: Date.now() - smokeAt, state: await status(grenadier)}, 'smoke still active');
    await sleep(2200);
    check('smoke expires after eighteen seconds', (await status(grenadier)).smokes === 0,
      {elapsedMs: Date.now() - smokeAt, state: await status(grenadier)}, 'smoke expired');
    check('throw, blast, bounce and smoke sound cues reach nearby players',
      ['pvp:mygo.throw', 'pvp:mygo.explode', 'pvp:mygo.flash', 'pvp:mygo.smoke', 'pvp:mygo.smoke.end']
        .every(name => namedSounds(grenadier, he.at, name).length > 0)
      && soundEvents.some(row => row.id === grenadier && row.at >= he.at && row.name.startsWith('pvp:mygo.grenade.bounce.')),
      soundEvents.filter(row => row.id === grenadier && row.at >= he.at).map(row => row.name), 'grenade sound set received');

    lane = {a: {x: origin.x - 1, y: platform.a.y, z: origin.z - 1}, b: {x: origin.x + 11, y: platform.a.y, z: origin.z + 1}};
    for (const [block, surface] of [['minecraft:stone', 'stone'], ['minecraft:iron_block', 'metal'], ['minecraft:oak_planks', 'wood']]) {
      await fill(lane.a, lane.b, block);
      await teleport(grenadier, origin); await teleport(friend, {...origin, z: origin.z + 3});
      const before = await status(grenadier), at = Date.now();
      const movement = await walk(grenadier, 1500);
      const after = await status(grenadier);
      check(`${surface} walking uses real movement and emits positional footsteps`, movement.distance > 2
        && after.stepSounds > before.stepSounds && after.lastStepSurface === surface
        && namedSounds(friend, at, `pvp:mygo.step.${surface}`).length > 0,
        {movement, before: before.stepSounds, after: after.stepSounds, surface: after.lastStepSurface}, 'moved over two blocks; observer heard surface steps');
    }
    await fill(lane.a, lane.b, 'minecraft:stone'); await teleport(grenadier, origin);
    const quietBefore = await status(grenadier), quietAt = Date.now();
    const sneaking = await walk(grenadier, 1500, true);
    const quietAfter = await status(grenadier);
    check('crouch movement is silent without freezing the player', sneaking.distance > .3
      && quietAfter.stepSounds === quietBefore.stepSounds
      && soundEvents.every(row => row.id !== friend || row.at < quietAt || !row.name.startsWith('pvp:mygo.step.')),
      {sneaking, before: quietBefore.stepSounds, after: quietAfter.stepSounds}, 'real crouch movement; no positional footsteps');
    await teleport(grenadier, origin);
    const standing = await status(grenadier), standingAt = Date.now();
    await sleep(1100);
    check('standing still produces no footstep packets', (await status(grenadier)).stepSounds === standing.stepSounds
      && soundEvents.every(row => row.id !== friend || row.at < standingAt || !row.name.startsWith('pvp:mygo.step.')),
      await status(grenadier), 'no movement; no footstep');
    bots[grenadier].testAllowMovement = true; bots[grenadier].physicsEnabled = true;
    const jumpAt = Date.now();
    bots[grenadier].setControlState('jump', true); await sleep(150); bots[grenadier].setControlState('jump', false);
    await sleep(1500); bots[grenadier].physicsEnabled = false; bots[grenadier].testAllowMovement = false;
    check('jump and landing cues are positional and audible to another player', namedSounds(friend, jumpAt, 'pvp:mygo.jump').length > 0
      && namedSounds(friend, jumpAt, 'pvp:mygo.land.stone').length > 0,
      soundEvents.filter(row => row.id === friend && row.at >= jumpAt).map(row => row.name), 'jump plus stone landing');
    // Climbing has little horizontal displacement, so a ground-only step
    // detector would remain silent even after the pack mutes vanilla ladders.
    await fill({x: origin.x + 5, y: origin.y, z: origin.z},
      {x: origin.x + 5, y: origin.y + 3, z: origin.z}, 'minecraft:stone');
    await fill({x: origin.x + 4, y: origin.y, z: origin.z},
      {x: origin.x + 4, y: origin.y + 3, z: origin.z}, 'minecraft:ladder[facing=west,waterlogged=false]');
    await teleport(grenadier, {...origin, x: origin.x + 4});
    const climbBefore = await status(grenadier), climbAt = Date.now();
    const climbing = await walk(grenadier, 1200);
    const climbAfter = await status(grenadier);
    check('real ladder climbing emits wooden footsteps to an observer', climbing.after.y - climbing.before.y > 1
      && climbAfter.stepSounds > climbBefore.stepSounds
      && namedSounds(friend, climbAt, 'pvp:mygo.step.wood').length > 0,
      {climbing, before: climbBefore.stepSounds, after: climbAfter.stepSounds}, 'rose over one block; observer heard wooden steps');
    const results = assertions.slice(startAssertions);
    const summary = {passed: results.filter(row => row.pass).length, failed: results.filter(row => !row.pass).length,
      fundedByNormalEconomy: true, realMovementReplies: true, renderingVerified: false};
    emit('utilitiesRegressionComplete', summary);
    return summary;
  } finally {
    if (rateChanged) await rate(20);
    for (const id of ids) {
      bots[id].clearControlStates(); bots[id].physicsEnabled = false; bots[id].testAllowMovement = false;
      if (bots[id]._client.state === 'play') await chat(id, '/hub', 80);
    }
    if (platform && bots.A._client.state === 'play') await fill(platform.a,
      {...platform.b, y: platform.b.y + 5}, 'minecraft:air');
  }
}

// Vanilla /tick is used only on the isolated test server. Rate changes are
// restored even when an assertion fails; no product-only money or round hooks.
async function csgoFastStatus(id) {
  const start = chats.length;
  await chat(id, '/pvp mygo status', 30);
  const deadline = Date.now() + 2000;
  let message;
  while (!(message = chats.slice(start).find(entry => entry.id === id && entry.message.includes('MyGO round='))?.message)) {
    if (Date.now() > deadline) throw new Error(`No fast MyGO status for ${id}`);
    await sleep(20);
  }
  return parseStatusMessage(message);
}
async function csgoFastState(id, predicate, timeout = 7000, label = 'accelerated CS:GO phase') {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const state = await csgoFastStatus(id);
    if (predicate(state)) return state;
    await sleep(30);
  }
  throw new Error(`Timeout waiting for ${label}`);
}
async function csgoFastTeleport(id, position) {
  await chat('A', `/execute in pvp:arena run tp MyGoCheck${id} ${position.x + .5} ${position.y} ${position.z + .5}`, 30);
}
async function csgoHalftime() {
  const ids = Object.keys(bots);
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'halftime four clients');
  check('MR15 test has exactly four clients on an isolated server', ids.length === 4 && port !== 25565, {ids, port}, 'four players; test port');
  const lobby = Object.fromEntries(ids.map(id => [id, bots[id].testWorldName]));
  try {
    for (const id of ids) await chat(id, '/hub', 60);
    await sleep(6500);
    for (const id of ids) {
      await chat('A', `/item replace entity MyGoCheck${id} armor.head with minecraft:golden_helmet`, 30);
      await chat('A', `/item replace entity MyGoCheck${id} hotbar.7 with minecraft:apple 3`, 30);
    }
    const packAt = chats.length;
    for (const id of ids) await chat(id, '/pvp join mygo', 100);
    await waitUntil(() => ids.every(id => packs[id] && chats.slice(packAt).some(entry => entry.id === id
      && entry.message.includes('MyGO 资源包加载成功'))), 25000, 'halftime resource pack acknowledgements');
    for (const id of ids) await chat(id, '/pvp join mygo', 60);
    await chat('A', '/pvp start', 60);
    await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 30000, 'halftime first-round loadouts');
    for (const id of ids) await select(id, 1);
    const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await csgoFastStatus(id)])));
    const originalT = ids.filter(id => opening[id].side === 'T'), originalCt = ids.filter(id => opening[id].side === 'CT');
    check('MR15 opening has two players on each side at $800', originalT.length === 2 && originalCt.length === 2
      && ids.every(id => opening[id].phase === 'BUY' && opening[id].cash === 800), opening, '2v2; first freeze; $800');
    const carrier = originalT.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
    const receiver = originalT.find(id => id !== carrier), position = bots[carrier].entity.position;
    const bombSpot = {x: Math.floor(position.x), y: Math.floor(position.y), z: Math.floor(position.z)};
    await select(carrier, 5);
    bots[carrier]._client.write('block_dig', {status: 4, location: {x: 0, y: 0, z: 0}, face: 0, sequence: 0});
    await sleep(100);
    // Both positions remain in the T buy zone. Move the original owner away
    // before the one-second pickup lock expires so it cannot reclaim C4 first.
    await csgoFastTeleport(carrier, {...bombSpot, x: bombSpot.x + 8});
    await csgoFastTeleport(receiver, bombSpot);
    await sleep(1200);
    const transferred = await csgoFastStatus(receiver);
    check('C4 can be handed to a teammate during the normal 15-second freeze', transferred.phase === 'BUY'
      && bots[receiver].inventory.items().some(item => item.name === 'redstone')
      && !bots[carrier].inventory.items().some(item => item.name === 'redstone'),
      {carrier, receiver, transferred, carrierInventory: snapshot(carrier).items}, 'C4 belongs to receiver before LIVE');
    // Keep the original CT team alive for fifteen wins, so kit, grenade, armor
    // and primary really survive until halftime instead of disappearing on death.
    const winners = originalCt, losers = originalT, leader = winners[0], winningTeam = opening[leader].team;
    const roundShooter = winners[1];
    // /kill calls LivingEntity.damage(genericKill), which still passes Fabric
    // ALLOW_DAMAGE and is rejected by MyGO's virtual-health policy. The second
    // winner instead uses the real default pistol, keeping the leader's money
    // and equipment assertions independent of kill rewards. Surviving pistols
    // receive their normal round-start ammo refill; no money/health debug hook.
    const eliminateLosers = async live => {
      const finishFrom = chats.length;
      const decidingScore = `${winningTeam === 0 ? 16 : 0}:${winningTeam === 1 ? 16 : 0}`;
      const ended = () => live.round === 16 && (chats.slice(finishFrom).some(entry => entry.message.includes('回合结束：')
        && entry.message.includes(decidingScore)) || ids.every(id => bots[id].testWorldName !== 'pvp:arena'));
      await select(roundShooter, 1);
      await csgoFastTeleport(roundShooter, live.range);
      const armed = await csgoFastStatus(roundShooter);
      check(`round ${live.round} shooter has its side's normally refilled pistol`, armed.gun === (live.round <= 15 ? 'USP_S' : 'GLOCK')
        && armed.ammo === (live.round <= 15 ? 12 : 20), armed, 'USP-S 12 or Glock 20; normal round refill');
      for (const victim of losers) {
        await csgoFastTeleport(victim, {...live.range, x: live.range.x + 6});
        let state = await csgoFastStatus(victim), shots = 0, matchFinished = false;
        while (state.alive && shots < 10) {
          await csgoLookAt(roundShooter, bots[victim].entity.position.offset(0, .9, 0));
          bots[roundShooter]._client.write('arm_animation', {hand: 0});
          await sleep(35);
          shots++;
          // A sixteenth win can restore every player before the following
          // status command is handled. Preserve the real match-ending evidence
          // rather than requesting a status from a session that no longer exists.
          if (ended()) { matchFinished = true; break; }
          try { state = await csgoFastStatus(victim); }
          catch (error) {
            if (!ended()) throw error;
            matchFinished = true;
            break;
          }
        }
        check(`round ${live.round} ${victim} is eliminated by genuine pistol fire`, !state.alive && state.hp === 0 || matchFinished,
          {victim, shooter: roundShooter, shots, state, matchFinished}, 'virtual HP reaches zero, or deciding victory immediately ends the match');
      }
    };
    await chat(leader, '/pvp mygo buy kit', 30);
    await chat(leader, '/pvp mygo buy flash', 30);
    check('winning CT carries a bought kit and flash before accelerated rounds', bots[leader].inventory.items().some(item => item.name === 'shears')
      && (await csgoFastStatus(leader)).grenades === '{FLASH=1}', snapshot(leader), 'kit and one flash bought normally');
    await chat('A', '/tick rate 200', 30);
    emit('csgoTestTickRate', {rate: 200, reason: 'isolated MR15 integration test'});
    for (let round = 1; round <= 15; round++) {
      if (round === 2) {
        await csgoFastState(leader, value => value.phase === 'BUY' && value.round === 2, 6000, 'round two purchase freeze');
        for (const product of ['mp9', 'vest', 'helmet']) await chat(leader, `/pvp mygo buy ${product}`, 30);
        await select(leader, 0);
        const equipped = await csgoFastStatus(leader);
        check('first win funds MP9, full armor and helmet without injected cash', equipped.gun === 'MP9' && equipped.armor === 100
          && equipped.helmet && equipped.cash === 1200, equipped, '800 - 400 - 200 + 3250 - 1250 - 650 - 350 = 1200');
      }
      const live = await csgoFastState(leader, value => value.phase === 'LIVE' && value.round === round, 7000, `round ${round} LIVE`);
      check(`round ${round} remains in the original half before settlement`, live.side === 'CT' && live.team === winningTeam, live, 'original CT side through round 15');
      // Boundary exits now rescue players; use actual gun damage instead of
      // relying on out-of-bounds death or a generic /kill damage bypass.
      await eliminateLosers(live);
      const post = await csgoFastState(leader, value => value.phase === 'POST' && value.round === round, 2500, `round ${round} single settlement`);
      if (round === 15) {
        // Slow down before the end of POST so the full half-reset state can be
        // inspected during a real fifteen-second freeze, without a timing race.
        await chat('A', '/tick rate 20', 30);
        emit('csgoTestTickRate', {rate: 20, reason: 'inspect round 16 halftime reset'});
      }
      const expectedScore = `${winningTeam === 0 ? round : 0}:${winningTeam === 1 ? round : 0}`;
      check(`round ${round} elimination awards exactly one point`, post.score === expectedScore, post, expectedScore);
    }
    const beforeHalf = await csgoFastStatus(leader);
    check('fifteenth POST preserves old side and surviving purchased equipment', beforeHalf.round === 15 && beforeHalf.phase === 'POST'
      && beforeHalf.side === 'CT' && beforeHalf.gun === 'MP9' && beforeHalf.armor === 100 && beforeHalf.helmet
      && beforeHalf.grenades === '{FLASH=1}' && beforeHalf.cash === 16000
      && bots[leader].inventory.items().some(item => item.name === 'shears'), beforeHalf,
      'old CT side; MP9, kit, flash, armor and helmet retained; money capped at $16000');
    await csgoFastState(leader, value => value.phase === 'BUY' && value.round === 16, 10000, 'sixteenth round halftime freeze');
    for (const id of ids) await select(id, 1);
    const halftime = Object.fromEntries(await Promise.all(ids.map(async id => [id, await csgoFastStatus(id)])));
    for (const id of ids) {
      const state = halftime[id], expectedSide = opening[id].side === 'T' ? 'CT' : 'T';
      const expectedPistol = expectedSide === 'T' ? 'GLOCK' : 'USP_S';
      check(`${id} changes side at round 16 with reset money and loss level`, state.phase === 'BUY' && state.round === 16
        && state.side === expectedSide && state.team === opening[id].team && state.cash === 800 && state.lossLevel === 1
        && state.alive && state.hp === 100, state, 'opposite side, same team, $800, loss level 1, alive at 100 HP');
      check(`${id} receives new side default pistol and clears old equipment`, state.pistol === expectedPistol && state.gun === expectedPistol
        && state.ammo === (expectedSide === 'T' ? 20 : 12) && state.reserve === (expectedSide === 'T' ? 120 : 24)
        && state.armor === 0 && !state.vest && !state.helmet && state.grenades === '{}'
        && bots[id].inventory.items().filter(item => item.name === 'iron_pickaxe').length === 1
        && !bots[id].inventory.items().some(item => item.name === 'shears'), state, 'new default pistol; no old primary, kit, armor, helmet or grenades');
    }
    await chat('A', '/tick rate 200', 30);
    emit('csgoTestTickRate', {rate: 200, reason: 'finish sixteenth round'});
    const finalLive = await csgoFastState(leader, value => value.phase === 'LIVE' && value.round === 16, 7000, 'sixteenth round LIVE');
    check('former CT winners now play T in the final round', finalLive.side === 'T', finalLive, 'T after halftime');
    const finishAt = chats.length;
    // At halftime the same shooter now carries the T default Glock. Its real
    // shots must finish the deciding round through the same damage pipeline.
    await eliminateLosers(finalLive);
    // Dimension/pack removal arrives before the following inventory packet;
    // wait for the whole restoration instead of inspecting a half-read stream.
    await waitUntil(() => ids.every(id => bots[id].testWorldName === lobby[id] && !packs[id]
      && bots[id].inventory.slots[5]?.name === 'golden_helmet'
      && bots[id].inventory.items().some(item => item.name === 'apple' && item.count === 3)
      && !bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
    15000, '16-point match ending and complete lobby inventory restoration');
    const expectedScore = `${winningTeam === 0 ? 16 : 0}:${winningTeam === 1 ? 16 : 0}`;
    check('sixteenth victory ends the match instead of starting round 17', chats.slice(finishAt).some(entry => entry.message.includes('回合结束：')
      && entry.message.includes(expectedScore)), chats.slice(finishAt).filter(entry => entry.message.includes('回合结束：')), expectedScore);
    for (const id of ids) check(`${id} restores lobby equipment after the MR15 match`, bots[id].inventory.slots[5]?.name === 'golden_helmet'
      && bots[id].inventory.items().some(item => item.name === 'apple' && item.count === 3)
      && !bots[id].inventory.items().some(item => item.name === 'iron_pickaxe'), snapshot(id), 'gold helmet, three apples, no guns');
    const summary = {passed: assertions.filter(result => result.pass).length, failed: assertions.filter(result => !result.pass).length,
      finalScore: expectedScore, completedRounds: 16, halftimeRound: 16, renderingVerified: false};
    emit('csgoHalftimeComplete', summary);
    return {opening, halftime, ...summary};
  } finally {
    await chat('A', '/tick rate 20', 100);
    emit('csgoTestTickRate', {rate: 20, reason: 'finally restore isolated server default'});
  }
}

// Reason: the original client echoes a self-directed swing animation as a new
// C2S swing. Mineflayer does not, so earlier scope probes missed right-click fire.
// Reproduce that exact feedback path, and keep real left clicks outside any
// timing-based suppression. Raw block-use packets deliberately avoid
// Mineflayer.activateBlock(), which unconditionally swings even at plain stone.
async function inputRegression() {
  const ids = Object.keys(bots), echoListeners = {}, selfAnimations = [];
  let fixture, originalFixture;
  const writeSwing = id => bots[id]._client.write('arm_animation', {hand: 0});
  const blockUse = (id, position) => bots[id]._client.write('block_place', {
    location: position, direction: 1, hand: 0, cursorX: .5, cursorY: .5, cursorZ: .5,
    insideBlock: false, sequence: 0
  });
  const setFixture = async state => chat('A', `/execute in pvp:arena run setblock ${fixture.x} ${fixture.y} ${fixture.z} ${state}`, 150);
  try {
    await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'input regression four clients');
    check('input regression uses four genuine 1.21.1 clients', ids.length === 4
      && ids.every(id => bots[id].version === '1.21.1'), ids, 'ABCD');
    for (const id of ids) {
      echoListeners[id] = packet => {
        if (packet.entityId !== bots[id].entity?.id || ![0, 3].includes(packet.animation)) return;
        const entry = {id, packet, at: Date.now()};
        selfAnimations.push(entry); emit('vanillaSelfSwingEcho', entry);
        bots[id]._client.write('arm_animation', {hand: packet.animation === 3 ? 1 : 0});
      };
      bots[id]._client.on('animation', echoListeners[id]);
      await chat(id, '/hub', 70);
    }
    await sleep(6500);
    const packAt = chats.length;
    for (const id of ids) await chat(id, '/pvp join mygo', 150);
    await waitUntil(() => ids.every(id => packs[id] && chats.slice(packAt).some(entry => entry.id === id
      && entry.message.includes('MyGO 资源包加载成功'))), 25000, 'input resource pack server acknowledgements');
    for (const id of ids) await chat(id, '/pvp join mygo', 80);
    await chat('A', '/pvp start', 80);
    await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
      30000, 'input round one loadouts');
    for (const id of ids) await select(id, 1);
    const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
    const defenders = ids.filter(id => opening[id].side === 'CT');
    check('normal pistol round starts with balanced sides and $800', defenders.length === 2
      && ids.every(id => opening[id].cash === 800 && opening[id].alive), opening, 'two CT; four players with $800');
    const gunner = defenders[0], target = defenders[1];
    await chat('A', '/tick rate 200', 30);
    emit('inputTestTickRate', {rate: 200, reason: 'natural first-round timeout funds AUG'});
    await csgoFastState(gunner, state => state.round === 2 && state.phase === 'BUY', 22000, 'natural CT timeout victory');
    await chat('A', '/tick rate 20', 30);
    emit('inputTestTickRate', {rate: 20, reason: 'normal-speed input verification'});
    const funded = await status(gunner);
    check('natural CT timeout victory funds $4050 without injected money', funded.round === 2 && funded.phase === 'BUY'
      && funded.cash === 4050, funded, '$800 plus $3250');
    await chat(gunner, '/pvp mygo buy aug', 150); await select(gunner, 0);
    const bought = await status(gunner);
    check('AUG purchase is charged by the normal economy', bought.gun === 'AUG' && bought.cash === 750
      && bought.ammo === 30, bought, '$4050 - $3300; AUG 30');
    await waitPhase(gunner, 'LIVE', 22000);
    const range = (await status(gunner)).range;
    await teleport(gunner, range);
    await teleport(target, {...range, x: range.x + 2});
    // One reversible in-world fixture supplies a reachable solid wall. All
    // coordinates derive from the map's existing test anchor, not old spawns.
    fixture = {x: range.x, y: range.y, z: range.z + 2};
    await waitUntil(() => bots[gunner].blockAt(new Vec3(fixture.x, fixture.y, fixture.z)) !== null,
      5000, 'input test fixture chunk');
    const previous = bots[gunner].blockAt(new Vec3(fixture.x, fixture.y, fixture.z));
    const props = Object.entries(previous.getProperties()).map(([key, value]) => `${key}=${value}`).join(',');
    originalFixture = `minecraft:${previous.name}${props ? `[${props}]` : ''}`;
    await setFixture('minecraft:stone');
    const fixturePoint = new Vec3(fixture.x + .5, fixture.y + .5, fixture.z + .5);
    const useEntity = id => {
      bots[id]._client.write('use_entity', {target: bots[target].entity.id, mouse: 2, hand: 0,
        x: 0, y: 1, z: 0, sneaking: false});
      bots[id]._client.write('use_entity', {target: bots[target].entity.id, mouse: 0, hand: 0, sneaking: false});
    };
    const rightPaths = [
      {name: 'air', aim: () => csgoLook(gunner, 0, -Math.PI / 2), use: () => bots[gunner].activateItem()},
      {name: 'stone wall', aim: () => csgoLookAt(gunner, fixturePoint), use: () => blockUse(gunner, fixture)},
      {name: 'player entity', aim: () => csgoLookAt(gunner, bots[target].entity.position.offset(0, 1, 0)), use: () => useEntity(gunner)}
    ];
    for (const path of rightPaths) {
      await path.aim();
      let state = await status(gunner);
      if (state.scopeLevel !== 0) { await csgoScopeClick(gunner); state = await status(gunner); }
      await sleep(350);
      const animationsAt = selfAnimations.length;
      path.use(); await sleep(220);
      const scoped = await status(gunner);
      check(`right click at ${path.name} opens the scope without consuming ammo`, scoped.scopeLevel === 1
        && scoped.scoped && scoped.ammo === state.ammo && scoped.hp === 100, {before: state, after: scoped}, 'scope 1, same ammo, 100 HP');
      check(`right click at ${path.name} does not send a self-swing feedback animation`, selfAnimations.length === animationsAt,
        selfAnimations.slice(animationsAt), 'no self animation; original-client echo would otherwise fire');
      await sleep(350); path.use(); await sleep(180);
      const closed = await status(gunner);
      check(`right click at ${path.name} also closes the scope without consuming ammo`, closed.scopeLevel === 0
        && closed.ammo === state.ammo, closed, 'scope 0; unchanged ammo');
      // Same ordered connection, no delay between the use and actual attack.
      // This would expose a broad "ignore swings after right click" workaround.
      await sleep(350); path.use(); writeSwing(gunner); await sleep(220);
      const immediate = await status(gunner);
      check(`a left click immediately after right click at ${path.name} fires exactly once`, immediate.scopeLevel === 1
        && immediate.ammo === state.ammo - 1 && immediate.hp === 100,
        {before: state, after: immediate}, 'scope opens; one bullet; no input suppression');
      await csgoScopeClick(gunner);
    }
    const attackPaths = [
      {name: 'air', aim: () => csgoLook(gunner, 0, -Math.PI / 2), attack: () => writeSwing(gunner)},
      {name: 'player entity', aim: () => csgoLookAt(gunner, bots[target].entity.position.offset(0, 1, 0)), attack: () => {
        bots[gunner]._client.write('use_entity', {target: bots[target].entity.id, mouse: 1, sneaking: false});
        writeSwing(gunner);
      }},
      {name: 'stone block', aim: () => csgoLookAt(gunner, fixturePoint), attack: () => {
        bots[gunner]._client.write('block_dig', {status: 0, location: fixture, face: 1, sequence: 0});
        writeSwing(gunner);
      }}
    ];
    for (const path of attackPaths) {
      await path.aim(); await sleep(180);
      const before = await status(gunner);
      path.attack(); await sleep(180);
      const after = await status(gunner);
      check(`left click at ${path.name} consumes exactly one bullet`, after.ammo === before.ammo - 1
        && after.hp === 100 && after.alive, {before, after}, 'one bullet; shooter alive');
    }
    // These original block classes always predict one swing, regardless of
    // open/powered state. It must be consumed once; a real following left click
    // on the same ordered connection must still fire with no time delay.
    const predictedUseBoundaries = [];
    for (const [name, block] of [['wooden door', 'minecraft:oak_door[half=lower,facing=north,open=false]'],
      ['copper door', 'minecraft:copper_door[half=lower,facing=north,open=false]'],
      ['wooden trapdoor', 'minecraft:oak_trapdoor[half=bottom,facing=north,open=false]'],
      ['copper trapdoor', 'minecraft:copper_trapdoor[half=bottom,facing=north,open=false]'],
      ['wooden fence gate', 'minecraft:oak_fence_gate[facing=north,open=false]'],
      ['lever', 'minecraft:lever[face=floor,facing=north,powered=false]']]) {
      await setFixture(block); await csgoLookAt(gunner, fixturePoint);
      let ready = await status(gunner);
      if (ready.scopeLevel !== 0) await csgoScopeClick(gunner);
      const before = await status(gunner); await sleep(350);
      blockUse(gunner, fixture); writeSwing(gunner); await sleep(200);
      const after = await status(gunner);
      const result = {name, beforeAmmo: before.ammo, afterAmmo: after.ammo, scopeLevel: after.scopeLevel,
        predictedSwingConsumesAmmo: before.ammo !== after.ammo,
        note: 'One client-predicted use swing must be consumed without blocking the next genuine attack.'};
      predictedUseBoundaries.push(result); emit('predictedUseBoundary', result);
      check(`right click on ${name} consumes its predicted swing without firing`, after.scopeLevel === 1
        && after.ammo === before.ammo, {before, after}, 'scope 1; unchanged ammunition');
      await sleep(350);
      blockUse(gunner, fixture); writeSwing(gunner); writeSwing(gunner); await sleep(200);
      const nextAttack = await status(gunner);
      check(`a real left click immediately after ${name} use fires exactly once`, nextAttack.scopeLevel === 0
        && nextAttack.ammo === before.ammo - 1, {before, after: nextAttack}, 'one predicted swing ignored; one real swing fires');
    }
    await setFixture('minecraft:oak_trapdoor[half=bottom,facing=north,open=false]');
    for (const [name, clear] of [
      ['fallback item-use', () => bots[gunner].activateItem()],
      ['explicit entity attack', () => bots[gunner]._client.write('use_entity', {target: bots[target].entity.id, mouse: 1, sneaking: false})],
      ['explicit START_DESTROY_BLOCK', () => bots[gunner]._client.write('block_dig', {status: 0, location: fixture, face: 1, sequence: 0})]
    ]) {
      const before = await status(gunner); await sleep(350);
      blockUse(gunner, fixture); clear(); writeSwing(gunner); await sleep(180);
      const after = await status(gunner);
      check(`${name} clears an unconsumed block prediction before a genuine attack`, after.ammo === before.ammo - 1,
        {before, after}, 'one genuine bullet; no stale block token');
    }
    // Powered buttons return CONSUME without a client swing. Their mutable
    // client/server state can differ, so they are deliberately not tokenized.
    // Preserve the legal next attack instead of guessing a suppression window.
    await setFixture('minecraft:stone_button[face=floor,facing=north,powered=true]');
    const buttonBefore = await status(gunner); await sleep(350);
    blockUse(gunner, fixture); writeSwing(gunner); await sleep(180);
    const buttonAfter = await status(gunner);
    check('a non-swinging powered button use cannot suppress the following left click', buttonAfter.ammo === buttonBefore.ammo - 1,
      {before: buttonBefore, after: buttonAfter}, 'next genuine left click fires');
    const summary = {passed: assertions.filter(row => row.pass).length, failed: assertions.filter(row => !row.pass).length,
      selfAnimationEchoes: selfAnimations.length, predictedUseBoundaries,
      unclassifiedPrediction: 'Buttons depend on mutable client powered state; no token is guessed.',
      fundedByNormalEconomy: true, renderingVerified: false};
    emit('inputRegressionComplete', summary);
    return summary;
  } finally {
    await chat('A', '/tick rate 20', 50);
    if (fixture && originalFixture) await setFixture(originalFixture);
    for (const id of ids) {
      if (echoListeners[id]) bots[id]._client.removeListener('animation', echoListeners[id]);
      await chat(id, '/hub', 100);
    }
  }
}

async function execute(command) {
  const id = command.who || 'A', bot = bots[id];
  switch (command.op) {
    case 'chat': await chat(id, command.text, command.delay ?? 250); return;
    case 'commands': for (const text of command.commands) await chat(id, text, command.delay ?? 200); return;
    case 'wait': await sleep(Math.min(command.ms ?? 500, 30000)); return;
    case 'ready': await waitUntil(() => Object.values(bots).every(b => b.entity), 30000, 'all bots spawned'); return;
    case 'snapshot': return command.who ? snapshot(id) : Object.fromEntries(Object.keys(bots).map(key => [key, snapshot(key)]));
    case 'status': {
      return status(id);
    }
    case 'smoke': return smoke();
    case 'gate': return gate();
    case 'shutdownSetup': return shutdownSetup();
    case 'fullMatch': return fullMatch();
    case 'explosion': return explosion();
    case 'menu': return menu();
    case 'arsenal': return arsenal();
    case 'awZoom': return awZoom();
    case 'twoPlayerAuto': return twoPlayerAuto();
    case 'csgo': return csgo();
    case 'csgoHalftime': return csgoHalftime();
    case 'inputRegression': return inputRegression();
    case 'damageRegression': return damageRegression();
    case 'utilitiesRegression': return utilitiesRegression();
    case 'packMode': packMode[id] = command.value; return packMode[id];
    case 'packProcess': if (!packRequests[id]) throw new Error('No pack requested'); await handlePack(bot, packRequests[id]); return;
    case 'packReply': packStatus(bot, { uuid: command.uuid || packRequests[id]?.uuid }, command.result); return;
    case 'slot': bot.setQuickBarSlot(command.slot); await sleep(150); return itemInfo(bot.heldItem);
    case 'use': bot.activateItem(command.offhand || false); await sleep(command.delay ?? 250); return itemInfo(bot.heldItem);
    case 'fire':
      for (let i = 0; i < (command.count ?? 1); i++) {
        bot.swingArm('right'); await sleep(command.interval ?? 250);
      }
      return snapshot(id);
    case 'reload':
      bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
      await sleep(command.delay ?? 250); return;
    case 'drop':
      bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
      await sleep(command.delay ?? 250); return;
    case 'release': bot.deactivateItem(); return;
    case 'move': bot.setControlState(command.control || 'forward', command.value); await sleep(command.delay ?? 150); return;
    case 'physics': bot.physicsEnabled = command.value; return;
    case 'look': await bot.look(command.yaw, command.pitch, true); return;
    case 'lookAt': await bot.lookAt(new Vec3(command.x, command.y, command.z), true); return;
    case 'aim': {
      const target = bots[command.target || 'B']?.entity;
      if (!target) throw new Error('Target is not present');
      await bot.lookAt(target.position.offset(0, command.height ?? 1.1, 0), true); return;
    }
    case 'click': await bot.clickWindow(command.slot, command.button ?? 0, command.mode ?? 0); await sleep(200); return;
    case 'close': if (bot.currentWindow) bot.closeWindow(bot.currentWindow); return;
    case 'block': {
      const position = new Vec3(command.x, command.y, command.z), block = bot.blockAt(position);
      return { position, name: block?.name, properties: block?.getProperties() };
    }
    case 'activateBlock': {
      const block = bot.blockAt(new Vec3(command.x, command.y, command.z));
      if (!block) throw new Error('Block is not loaded');
      await bot.activateBlock(block); return;
    }
    case 'assertItem': {
      const items = bot.inventory.slots.filter(Boolean).filter(item => item.name === command.item);
      const count = items.reduce((sum, item) => sum + item.count, 0);
      const pass = (command.count === undefined || count === command.count) &&
        (command.slot === undefined || items.some(item => item.slot === command.slot)) &&
        (command.contains === undefined || stringify(items).includes(command.contains));
      check(command.name || `${id} ${command.item}`, pass, items.map(itemInfo), command); return;
    }
    case 'assertChat': {
      const found = chats.filter(entry => entry.id === id && entry.message.includes(command.contains));
      check(command.name || `${id} chat ${command.contains}`, found.length > 0, found.slice(-3), command.contains); return;
    }
    case 'assertMode': check(command.name || `${id} mode`, bot.game.gameMode === command.mode, bot.game.gameMode, command.mode); return;
    case 'assertState': {
      const actual = await status(id);
      check(command.name || `${id} state`, Object.entries(command.expected).every(([key, value]) => actual[key] === value), actual, command.expected); return;
    }
    case 'assertHealth': check(command.name || `${id} health`, Math.abs(bot.health - command.value) < 0.05, bot.health, command.value); return;
    case 'assertPosition': {
      const actual = bot.entity.position;
      check(command.name || `${id} position`, actual.distanceTo(new Vec3(command.x, command.y, command.z)) <= (command.tolerance ?? 0.2), actual, command); return;
    }
    case 'summary': return { passed: assertions.filter(result => result.pass).length,
      failed: assertions.filter(result => !result.pass).length, assertions, log: logfile,
      renderingVerified: false };
    case 'reconnect': connect(id); return;
    case 'quit': if (command.who) bot.quit(); else Object.values(bots).forEach(b => b.quit()); return;
    default: throw new Error(`Unknown operation ${command.op}`);
  }
}

for (const id of process.env.MYGO_TEST_BOTS || 'ABCD') connect(id);
let queue = Promise.resolve();
readline.createInterface({ input: process.stdin }).on('line', line => {
  queue = queue.then(async () => {
    let command;
    try {
      command = JSON.parse(line); emit('commandStart', { command });
      const result = await execute(command); emit('result', { command, result });
    } catch (error) { emit('commandError', { command, message: error.stack }); }
  });
});
