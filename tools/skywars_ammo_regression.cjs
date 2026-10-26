'use strict';

// Reason: investigate lost ammunition after pickup, reload and moving guns.
// Pre-review: count magazine + reserve + loose rounds after real protocol input;
// use only an isolated server, preserve every failed check, and always quit bots.
// Resource packs are downloaded/hash-checked but HEADLESS reports are not a
// visual, audio or first-person control acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const modules = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-skywars-ammo'));
const port = Number(process.env.MYGO_TEST_PORT || 25637);
const projectBuild = path.resolve(__dirname, '../build') + path.sep;
if (port === 25565 || !Number.isInteger(port) || port < 1024 || port > 65535
    || !runDir.startsWith(projectBuild) || !path.basename(runDir).includes('mygo')) {
  throw new Error('Use a mygo-named directory inside build/ and an isolated port, never 25565.');
}
fs.mkdirSync(runDir, { recursive: true });
const evidence = path.join(runDir, 'skywars-ammo-results.jsonl');
const resultFile = path.join(runDir, 'skywars-ammo-verification.json');
if (fs.existsSync(evidence) || fs.existsSync(resultFile)) {
  throw new Error('Choose a fresh isolated run directory; existing ammunition evidence is never overwritten.');
}
const bots = {}, chats = [], checks = [], packs = {};
const nbt = require(path.join(modules, 'prismarine-nbt'));
const baseline = process.env.AMMO_BASELINE === 'true';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function emit(event, details = {}) {
  const line = JSON.stringify({ time: new Date().toISOString(), event, ...details });
  fs.appendFileSync(evidence, line + '\n');
  process.stdout.write(line + '\n');
}
function check(name, ok, actual, expected) {
  const row = { name, pass: !!ok, actual, expected };
  checks.push(row); emit('assertion', row);

}
async function waitFor(predicate, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timed out: ${label}`);
    await sleep(100);
  }
}
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

function data(item) {
  const component = item?.components?.find(value => value.type === 'custom_data');
  return component ? nbt.simplify(component.data) : {};
}
function inventory() {
  return bots.A.inventory.slots.flatMap((item, slot) => {
    const tags = data(item);
    if (tags['pvp.skywars_gun']) return [{ slot, kind: 'gun', weapon: tags['pvp.skywars_gun'],
      ammo: tags['pvp.gun_ammo'], reserve: tags['pvp.gun_reserve'] }];
    if (tags['pvp.skywars_ammo']) return [{ slot, kind: 'ammo', category: tags['pvp.skywars_ammo'], count: item.count }];
    return [];
  });
}
function total(rows = inventory()) {
  return rows.reduce((sum, row) => sum + (row.kind === 'gun' ? row.ammo + row.reserve : row.count), 0);
}
function gunAt(slot) { return inventory().find(row => row.kind === 'gun' && row.slot === slot); }
function gunItem(ammo, reserve, weapon = 'AK47', model = 71022) {
  return `minecraft:iron_pickaxe[minecraft:custom_model_data=${model},minecraft:custom_data={"pvp.skywars_gun":"${weapon}","pvp.gun_ammo":${ammo},"pvp.gun_reserve":${reserve}}]`;
}
async function equip(slot, ammo, reserve) {
  await command('A', `/item replace entity MyGoCheckA ${slot} with ${gunItem(ammo, reserve)}`);
}
async function clear() {
  await command('A', '/clear MyGoCheckA');
  await command('A', '/execute in pvp:arena run kill @e[type=minecraft:item]');
  await select(0);
}
async function select(slot, delay = 180) {
  bots.A.setQuickBarSlot(slot);
  await sleep(delay);
}
function action(status) {
  bots.A._client.write('block_dig', { status, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
}
function swing() { bots.A._client.write('arm_animation', { hand: 0 }); }
async function pickup(category, count) {
  await command('A', `/execute at MyGoCheckA run summon minecraft:item ~ ~ ~ {PickupDelay:0s,Item:{id:"minecraft:gunpowder",count:${count},components:{"minecraft:custom_data":{"pvp.skywars_ammo":"${category}"}}}}`, 500);
}
async function snapshot(label) {
  await sleep(300);
  const rows = inventory();
  emit('inventory', { label, rows, total: total(rows), cursor: bots.A.inventory.selectedItem });
  // Record the authoritative server inventory as well as the client component view.
  await command('A', '/data get entity MyGoCheckA Inventory', 100);
  return rows;
}
async function move(from, to) {
  await bots.A.clickWindow(from, 0, 0);
  await sleep(180);
  await bots.A.clickWindow(to, 0, 0);
  await sleep(300);
}
async function activeMatch() {
  const start = chats.length;
  for (const id of ['A', 'B']) await command(id, '/pvp join skywarsguns');
  await waitFor(() => packs.A && packs.B, 'both local resource packs');
  await sleep(500);
  for (const id of ['A', 'B']) {
    if (!chats.slice(start).some(row => row.id === id && row.message.includes('已加入枪械空岛')))
      await command(id, '/pvp join skywarsguns');
  }
  await waitFor(() => ['A', 'B'].every(id => chats.slice(start).some(row =>
    row.id === id && row.message.includes('已加入枪械空岛'))), 'both queued');
  await command('A', '/pvp start OVERWORLD');
  await waitFor(() => ['A', 'B'].every(id => chats.slice(start).some(row =>
    row.id === id && row.message.includes('战斗开始'))), 'match ACTIVE', 60000);
  for (const id of ['A', 'B']) if (bots[id].currentWindow) bots[id].closeWindow(bots[id].currentWindow);
  check('normal queue starts gun SkyWars for both clients', ['A', 'B'].every(id => bots[id].probeWorld === 'pvp:arena'),
    ['A', 'B'].map(id => bots[id].probeWorld), ['pvp:arena', 'pvp:arena']);
  // Point away from the other spawn. These checks measure ammunition, not damage.
  await bots.A.look(0, Math.PI / 2, true);
}
async function conservation() {
  await clear();
  await pickup('步枪', 24);
  let rows = await snapshot('loose rounds without a gun');
  check('pickup without compatible gun keeps all loose rounds', total(rows) === 24
    && rows.some(row => row.kind === 'ammo' && row.count === 24), rows, '24 loose');
  await equip('hotbar.1', 11, 10);
  rows = await snapshot('compatible pickup transfers to reserve');
  check('compatible rounds move to reserve without loss', total(rows) === 45
    && gunAt(37)?.ammo === 11 && gunAt(37)?.reserve === 34, rows, '11 magazine + 34 reserve');
  await pickup('手枪', 17);
  rows = await snapshot('incompatible ammo stays loose');
  check('incompatible rounds remain loose', total(rows) === 62
    && rows.some(row => row.kind === 'ammo' && row.category === '手枪' && row.count === 17), rows, '45 gun + 17 pistol');

  await clear();
  await equip('hotbar.0', 11, 85);
  await pickup('步枪', 20);
  rows = await snapshot('reserve capacity overflow');
  check('full reserve preserves excess ammo stack', total(rows) === 116 && gunAt(36)?.reserve === 90
    && rows.some(row => row.kind === 'ammo' && row.count === 15), rows, '11 + 90 + 15');
  action(6);
  await sleep(3000);
  rows = await snapshot('reload and remaining loose ammo');
  check('F reload conserves magazine reserve and loose ammunition', total(rows) === 116
    && gunAt(36)?.ammo === 30, rows, '30 magazine; total 116');

  await clear();
  await equip('hotbar.0', 7, 23);
  await move(36, 9);
  rows = await snapshot('gun moved to backpack');
  check('moving gun into backpack preserves its own ammo', total(rows) === 30
    && gunAt(9)?.ammo === 7 && gunAt(9)?.reserve === 23, rows, 'slot 9: 7/23');
  await bots.A.clickWindow(9, 1, 2);
  rows = await snapshot('number key moves gun to second hotbar slot');
  check('number-key hotbar move preserves gun ammunition', total(rows) === 30
    && gunAt(37)?.ammo === 7 && gunAt(37)?.reserve === 23, rows, 'slot 37: 7/23');
  await equip('hotbar.0', 19, 51);
  await bots.A.clickWindow(36, 1, 2);
  rows = await snapshot('same-type guns exchange hotbar slots');
  check('same-type gun swap keeps ammunition attached to each item', total(rows) === 100
    && gunAt(36)?.ammo === 7 && gunAt(36)?.reserve === 23
    && gunAt(37)?.ammo === 19 && gunAt(37)?.reserve === 51, rows, 'slot 36: 7/23; slot 37: 19/51');
  await select(0);
  action(6);
  await sleep(250);
  await move(36, 10);
  await sleep(2700);
  rows = await snapshot('moving gun during reload');
  check('moving gun during reload cannot destroy rounds', total(rows) === 100
    && gunAt(10)?.ammo + gunAt(10)?.reserve === 30, rows, 'first gun total 30; all guns 100');

  await clear();
  await equip('hotbar.0', 13, 29);
  action(4);
  // Sent immediately after Q, before collision pickup, to observe the real entity.
  await command('A', '/execute in pvp:arena as @e[type=minecraft:item,sort=nearest,limit=1] run data merge entity @s {PickupDelay:32767s}', 250);
  const before = chats.length;
  await command('A', '/execute in pvp:arena as @e[type=minecraft:item,sort=nearest,limit=1] run data get entity @s Item');
  const dropEvidence = chats.slice(before).filter(row => row.message.includes('pvp.gun_ammo'));
  check('Q creates a dropped gun containing its magazine and reserve', dropEvidence.some(row =>
    row.message.includes('13') && row.message.includes('29')), dropEvidence, 'dropped gun 13/29');
  await command('A', '/execute in pvp:arena as @e[type=minecraft:item] run data merge entity @s {PickupDelay:0s,Motion:[0.0d,0.0d,0.0d]}');
  await command('A', '/execute in pvp:arena run tp @e[type=minecraft:item] MyGoCheckA', 600);
  rows = await snapshot('dropped gun picked up');
  check('picking dropped gun up preserves all rounds', total(rows) === 42
    && rows.some(row => row.kind === 'gun' && row.ammo === 13 && row.reserve === 29), rows, '13 magazine + 29 reserve');

  await clear();
  await equip('hotbar.0', 7, 0);
  await equip('hotbar.1', 11, 0);
  await select(1);
  await pickup('步枪', 17);
  rows = await snapshot('compatible rounds prefer the held gun');
  check('picked ammunition fills held compatible gun first', total(rows) === 35
    && gunAt(36)?.reserve === 0 && gunAt(37)?.reserve === 17, rows, 'held slot 37 reserve 17; slot 36 reserve 0');
  const lore = bots.A.inventory.slots[37]?.components?.find(value => value.type === 'lore');
  check('gun item exposes magazine and reserve in its tooltip', !!lore, lore, 'lore component');

  await clear();
  await equip('hotbar.0', 7, 23);
  await equip('hotbar.1', 7, 23);
  action(6);
  await sleep(200);
  await bots.A.clickWindow(36, 1, 2);
  await sleep(2800);
  rows = await snapshot('equal-ammo gun swap during reload');
  check('identical-ammo gun replacement cannot inherit another gun reload', total(rows) === 60
    && gunAt(36)?.ammo === 7 && gunAt(37)?.ammo === 7, rows, 'both guns remain 7/23 after interrupted reload');
}
async function firing() {
  await clear();
  await equip('hotbar.0', 30, 0);
  swing();
  await sleep(450);
  let rows = await snapshot('one click without a release packet');
  check('one isolated click consumes exactly one round', gunAt(36)?.ammo === 29, rows,
    '29 magazine; vanilla air target sends no release packet');

  await clear();
  await equip('hotbar.0', 30, 0);
  swing();
  bots.A.setQuickBarSlot(1);
  bots.A.setQuickBarSlot(0);
  await sleep(450);
  rows = await snapshot('click then switch away and back');
  check('switching slots stops prior trigger input', gunAt(36)?.ammo === 29, rows, '29 magazine; no click after returning');

  await clear();
  await equip('hotbar.0', 30, 0);
  await equip('hotbar.1', 29, 0);
  swing();
  await bots.A.clickWindow(36, 1, 2);
  await sleep(450);
  rows = await snapshot('swap a gun for an identical-ammo gun immediately after firing');
  check('same-type equal-ammo replacement does not inherit trigger state', total(rows) === 58
    && gunAt(36)?.ammo === 29 && gunAt(37)?.ammo === 29, rows, 'both guns 29; total 58');

  await clear();
  await equip('hotbar.0', 30, 0);
  swing();
  await sleep(120);
  swing();
  await sleep(450);
  rows = await snapshot('two clicks then no release packet');
  check('two spaced clicks consume only two rounds', gunAt(36)?.ammo === 28, rows, '28 magazine without synthetic release');

  await clear();
  await equip('hotbar.0', 30, 0);
  for (let index = 0; index < 6; index++) { swing(); await sleep(100); }
  action(1);
  await sleep(300);
  const stopped = gunAt(36)?.ammo;
  rows = await snapshot('repeated input fires then explicit release stops');
  check('repeated left input still fires automatic weapon', stopped < 29 && stopped >= 20, rows, '2–10 rounds fired');
  await sleep(500);
  check('released trigger does not keep consuming ammo', gunAt(36)?.ammo === stopped, inventory(), stopped);

  await clear();
  await command('A', `/item replace entity MyGoCheckA hotbar.0 with ${gunItem(5, 0, 'AWP', 71028)}`);
  swing();
  await sleep(100);
  await command('A', `/item replace entity MyGoCheckA hotbar.0 with ${gunItem(4, 0, 'AWP', 71028)}`);
  swing();
  await sleep(150);
  rows = await snapshot('same-ammo replacement during AWP cooldown');
  check('same-slot same-type replacement preserves firing cooldown', gunAt(36)?.ammo === 4, rows,
    '4 magazine; second click remains inside the 1.455 second AWP cycle');
}
async function main() {
  let error;
  try {
    connect('A'); connect('B');
    await waitFor(() => bots.A.probeSpawned && bots.B.probeSpawned, 'client login');
    await sleep(700);
    await activeMatch();
    await conservation();
    await firing();
  } catch (caught) { error = caught; emit('failure', { error: caught.stack }); }
  finally {
    for (const id of ['A', 'B']) {
      if (!bots[id]) continue;
      try { await command(id, '/hub'); bots[id].quit('SkyWars ammunition regression complete'); } catch {}
    }
    const result = { passed: !error && checks.every(row => row.pass), baseline,
      assertions: checks.length, failed: checks.filter(row => !row.pass).length, checks,
      headless: true, visualAcceptance: false, error: error?.stack };
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2) + '\n');
    emit('complete', { passed: result.passed, assertions: result.assertions, resultFile });
    await sleep(400);
    process.exit(result.passed ? 0 : 1);
  }
}
void main();
