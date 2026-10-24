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
const bots = {}, packMode = {}, packs = {}, packRequests = {}, assertions = [], chats = [], healthEvents = [], recoils = {}, abilities = {}, hud = [];
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
  const state = Object.fromEntries([...message.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(match => [match[1], match[2]]));
  for (const key of ['round', 'remainingTicks', 'team', 'hp', 'cash', 'ammo', 'reserve', 'kills', 'activeGrenades', 'smokes', 'moveSpeed', 'walkSpeed']) state[key] = Number(state[key]);
  for (const key of ['alive', 'vest', 'helmet', 'bombPlanted', 'scoped', 'awZoom']) state[key] = state[key] === 'true';
  for (const key of ['range', 'spawn', 'waiting']) {
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
  check('quick match begins with $1600 and 100 HP', Object.values(initial).every(state => state.cash === 1600 && state.hp === 100), initial, '1600 cash, 100 virtual HP');
  await waitUntil(() => hudMoney('A',1600).length > 0,5000,'first money HUD after match activation');
  check('money HUD is a bold gold action bar with thousands separators', hudMoney('A',1600).length > 0, hudMoney('A',1600).slice(-1), '$ 1,600 above hotbar');
  const attackers = ids.filter(id => initial[id].side === 'T'), defenders = ids.filter(id => initial[id].side === 'CT');
  const carrier = attackers.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  check('exactly one attacker receives C4', !!carrier && attackers.filter(id => bots[id].inventory.items().some(item => item.name === 'redstone')).length === 1, carrier, 'one carrier');
  const gunner = attackers.find(id => id !== carrier), defender = defenders[0], otherDefender = defenders[1];
  await select(gunner, 8); bots[gunner].activateItem(); await sleep(150);
  check('purchase device opens real shop GUI', !!bots[gunner].currentWindow, bots[gunner].currentWindow?.title, 'MyGO shop');
  await bots[gunner].clickWindow(10, 0, 0); await sleep(100); bots[gunner].closeWindow(bots[gunner].currentWindow);
  const bought = await status(gunner);
  check('GUI purchase charges MP5 price', bought.cash === 350 && bots[gunner].inventory.items().filter(item => item.name === 'iron_pickaxe').length === 2, bought, 'MP5 plus P9; cash=350');
  check('successful purchase immediately shows new action-bar balance', hudMoney(gunner,350,'购买成功').length > 0, hudMoney(gunner,350,'购买成功').slice(-1), '$ 350');
  await chat(defender, '/pvp mygo buy vest');
  await chat(defender, '/pvp mygo buy helmet');
  await chat(defender, '/pvp mygo buy kit');
  const armor = await status(defender);
  check('vest helmet and defuse kit charge correct prices', armor.vest && armor.helmet && armor.cash === 200, armor, '1600 - 650 - 350 - 400 = 200');
  await waitPhase(gunner, 'LIVE');
  // Use the unobstructed corridor, away from cover and the bomb site.
  const site = initial[carrier].sites.at(-1);
  if (!site) throw new Error('Status did not include a bomb site');
  const range = initial[carrier].range;
  await teleport(gunner, range);
  await teleport(defender, { ...range, x: range.x + 6 });
  await teleport(carrier, { ...range, x: range.x + 3 });
  await select(gunner, 0); await scope(gunner, true); await sleep(450);
  check('right click with gun equips scope pumpkin', bots[gunner].inventory.slots[5]?.name === 'carved_pumpkin', itemInfo(bots[gunner].inventory.slots[5]), 'carved_pumpkin');
  const friendBefore = await status(carrier), enemyBefore = await status(defender);
  await bots[gunner].lookAt(bots[defender].entity.position.offset(0, .9, 0), true);
  await trigger(gunner, 1, 300);
  const shot = await status(gunner), friendAfter = await status(carrier), enemyAfter = await status(defender);
  check('MP5 emits exactly two bullets per trigger', shot.ammo === 28, shot, '30 -> 28');
  check('friendly target does not take damage or block bullets', friendAfter.hp === friendBefore.hp && enemyAfter.hp < enemyBefore.hp, { friendBefore, friendAfter, enemyBefore, enemyAfter }, 'friend unchanged, enemy damaged');
  check('hit feedback keeps the bold money label visible', hudMoney(gunner,350,'命中').length > 0, hudMoney(gunner,350,'命中').slice(-1), '$ 350 with hit feedback');
  bots[gunner]._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
  await sleep(2300);
  const reloaded = await status(gunner);
  check('F swap packet reloads magazine and consumes reserve', reloaded.ammo === 30 && reloaded.reserve === 88, reloaded, '30/88');
  check('reload prompt keeps the money label visible', hudMoney(gunner,350,'正在换弹').length > 0, hudMoney(gunner,350,'正在换弹').slice(-1), '$ 350 with reload');
  await scope(gunner, false);
  check('second right click clears scope helmet', !bots[gunner].inventory.slots[5], itemInfo(bots[gunner].inventory.slots[5]), 'empty head');
  await teleport(carrier, site); await select(carrier, 5);
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(1000);
  bots[carrier].setControlState('sneak', false); await sleep(2400);
  check('releasing sneak cancels installation', !(await status(carrier)).bombPlanted, await status(carrier), 'unplanted');
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(3300);
  check('plant progress keeps current balance visible', hudMoney(carrier,1600,'安装剩余').length > 0, hudMoney(carrier,1600,'安装剩余').slice(-1), '$ 1,600 with progress');
  const planted = await status(carrier);
  check('three second plant starts 40 second fuse and rewards $300', planted.bombPlanted && planted.remainingTicks <= 800 && planted.remainingTicks > 700 && planted.cash === 1900, planted, 'planted; approximately 40s; 1900 cash');
  await waitUntil(() => hudMoney(carrier,1900).length > 0,1500,'plant money HUD');
  check('plant reward updates the highlighted balance', hudMoney(carrier,1900).length > 0, hudMoney(carrier,1900).slice(-1), '$ 1,900');
  bots[carrier].setControlState('sneak', false);
  await teleport(defender, { ...site, z: site.z + 1 }); await select(defender, 5);
  bots[defender].setControlState('sneak', true); await sleep(150); bots[defender].activateItem(); await sleep(1800);
  check('defuse progress keeps current balance visible', hudMoney(defender,200,'拆除剩余').length > 0, hudMoney(defender,200,'拆除剩余').slice(-1), '$ 200 with progress');
  await select(defender, 1); await sleep(2500);
  check('switching slots cancels defuse', (await status(defender)).phase === 'LIVE' && (await status(defender)).bombPlanted, await status(defender), 'LIVE and planted');
  await select(defender, 5); bots[defender].activateItem(); await sleep(4300);
  const defused = await status(defender);
  check('four second kit defuse wins round and grants economy rewards', defused.phase === 'POST' && defused.score === '0:1' && defused.cash === 3750, defused, 'POST, defenders lead, 200+300+3250=3750');
  await waitUntil(() => hudMoney(defender,3750).length > 0,1500,'round money HUD');
  check('defuse and win rewards update bold thousands-formatted balance', hudMoney(defender,3750).length > 0, hudMoney(defender,3750).slice(-1), '$ 3,750');
  bots[defender].setControlState('sneak', false);
  const next = await waitPhase(defender, 'BUY', 10000);
  check('new round resets HP and unplants C4', next.round === 2 && next.hp === 100 && !next.bombPlanted, next, 'round=2, hp=100, no planted bomb');
  const gunnerNext = await status(gunner);
  check('survivor retains purchased gun and refills ammo', gunnerNext.gun === 'MP5' && gunnerNext.ammo === 30 && gunnerNext.reserve === 90, gunnerNext, 'MP5 30/90');
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
