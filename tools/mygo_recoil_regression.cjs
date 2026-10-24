'use strict';

// Reason: a stationary protocol shim can hide recoil/teleport regressions.
// Keep Mineflayer's ordinary physics, position replies and teleport confirmations
// intact, and check actual firing, walking and jumping on Fabric 1.21.1.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const modules = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || 'build/mygo-recoil-regression');
const port = Number(process.env.MYGO_TEST_PORT || 25575);
if (port === 25565 || !path.basename(runDir).includes('mygo')) throw new Error('Use an isolated MyGO server.');
fs.mkdirSync(runDir, {recursive: true});
const bots = {}, chats = [], packs = {}, checks = [], packets = {}, moves = {};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function emit(event, detail) {
  const row = JSON.stringify({time: new Date().toISOString(), event, ...detail});
  fs.appendFileSync(path.join(runDir, 'recoil-results.jsonl'), row + '\n');
  process.stdout.write(row + '\n');
}
function check(name, pass, actual) {
  const result = {name, pass: !!pass, actual}; checks.push(result); emit('assertion', result);
  if (!pass) throw new Error(name);
}
async function until(test, label, timeout = 20000) {
  const end = Date.now() + timeout;
  while (!test()) { if (Date.now() > end) throw new Error('Timeout: ' + label); await sleep(50); }
}
async function chat(id, text, delay = 200) { bots[id].chat(text); await sleep(delay); }
async function status(id) {
  const from = chats.length; await chat(id, '/pvp mygo status');
  const text = chats.slice(from).find(r => r.id === id && r.text.includes('MyGO round='))?.text;
  if (!text) throw new Error('Missing state: ' + id);
  const state = Object.fromEntries([...text.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(m => [m[1], m[2]]));
  for (const key of ['hp', 'ammo', 'reserve', 'cash', 'round']) state[key] = Number(state[key]);
  state.alive = state.alive === 'true';
  state.position = {...bots[id].entity.position};
  return state;
}
async function select(id, slot) { bots[id].setQuickBarSlot(slot); await sleep(100); }
async function shoot(id, count = 1) {
  for (let i = 0; i < count; i++) { bots[id].swingArm('right'); await sleep(320); }
}
function connect(id) {
  const bot = bots[id] = mineflayer.createBot({host:'127.0.0.1', port,
    username:'MyGoCheck' + id, version:'1.21.1', auth:'offline'});
  packets[id] = []; moves[id] = 0;
  // Observe only: every packet is forwarded unchanged, including position_look.
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (name === 'position' || name === 'position_look') moves[id]++;
    return write(name, packet);
  };
  bot._client.on('position', packet => { packets[id].push(packet); emit('positionPacket', {id, packet}); });
  bot.on('messagestr', text => { chats.push({id, text}); if (text.includes('阵亡')) emit('deathMessage', {id, text}); });
  bot.on('error', error => emit('clientError', {id, message:error.stack}));
  bot.on('kicked', reason => emit('kicked', {id, reason}));
  bot._client.on('add_resource_pack', async packet => {
    bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:3});
    try {
      const response = await fetch(packet.url, {signal:AbortSignal.timeout(15000)});
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const body = Buffer.from(await response.arrayBuffer());
      if (crypto.createHash('sha1').update(body).digest('hex') !== packet.hash) throw new Error('Pack SHA1 mismatch');
      packs[id] = true;
      bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:4});
      bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:0});
    } catch (error) { emit('packError', {id, error:error.message}); }
  });
}

(async () => {
  for (const id of 'ABCD') connect(id);
  await until(() => Object.values(bots).every(b => b.entity && b.inventory), 'four clients');
  await sleep(2500);
  for (const id of 'ABCD') await chat(id, '/pvp join mygo');
  await until(() => Object.values(packs).filter(Boolean).length === 4, 'resource packs');
  for (const id of 'ABCD') await chat(id, '/pvp join mygo');
  await chat('A', '/pvp start');
  await until(() => Object.values(bots).every(b => b.inventory.items().some(i => i.name === 'iron_pickaxe')), 'loadouts');
  await chat('A', '/pvp mygo buy mp5');
  await chat('B', '/pvp mygo buy s686');
  await select('A', 0); bots.A.swingArm('right'); bots.A.activateItem(); await sleep(150);
  const frozen = await status('A');
  check('buy phase blocks firing and scoping', frozen.phase === 'BUY' && frozen.ammo === 30
    && bots.A.inventory.slots[5]?.name !== 'carved_pumpkin', frozen);
  while ((await status('A')).phase !== 'LIVE') await sleep(300);
  for (const id of 'ABCD') await bots[id].look(0, 1.1, true);
  await select('A', 0); await select('B', 0);
  bots.A.activateItem(); await sleep(100);
  bots.A.activateItem(); await sleep(200);
  bots.A.activateItem(); await sleep(100);
  const aimedOnly = await status('A');
  check('right click and its held repeats scope without firing or flickering', aimedOnly.ammo === 30
    && bots.A.inventory.slots[5]?.name === 'carved_pumpkin', aimedOnly);
  await sleep(350); bots.A.activateItem(); await sleep(150);
  check('second right click closes the scope', bots.A.inventory.slots[5]?.name !== 'carved_pumpkin', bots.A.inventory.slots[5]?.name);
  bots.A.setControlState('sneak', true); await sleep(350);
  check('sneaking alone no longer opens scope', bots.A.inventory.slots[5]?.name !== 'carved_pumpkin', bots.A.inventory.slots[5]?.name);
  bots.A.setControlState('sneak', false);
  bots.A._client.write('arm_animation', {hand:1}); await sleep(150);
  check('offhand swing does not fire', (await status('A')).ammo === 30, await status('A'));
  // Real maps have stairs and walls around their spawns. Use the audited flat
  // range for movement/gun hit probes instead of assuming any spawn has a lane.
  const anchor = (await status('A')).range.split(',').map(Number);
  await chat('A', '/tp MyGoCheckA ' + (anchor[0]+.5) + ' ' + anchor[1] + ' ' + (anchor[2]+.5), 500);
  const initial = await status('A'), before = packets.A.length;
  await shoot('A');
  const after = await status('A');
  check('first left click preserves shooter HP and in-arena position', after.alive && after.hp === 100 && after.ammo === 28
    && Math.abs(after.position.x - initial.position.x) < .1 && Math.abs(after.position.y - initial.position.y) < .1
    && Math.abs(after.position.z - initial.position.z) < .1, {initial, after});
  check('burst delivers recoil rotation without position displacement', packets.A.length >= before + 2,
    packets.A.slice(before));
  check('recoil uses zero relative coordinates and cannot acknowledge a world teleport',
    packets.A.slice(before).every(p => p.x === 0 && p.y === 0 && p.z === 0 && p.teleportId < 0
      && p.flags.x && p.flags.y && p.flags.z && p.flags.yaw && p.flags.pitch), packets.A.slice(before));
  await shoot('B');
  check('shotgun left click preserves shooter HP', (await status('B')).alive && (await status('B')).hp === 100, await status('B'));
  for (const id of 'ABCD') {
    await select(id, 1); await shoot(id, 4);
    const state = await status(id);
    check(id + ' scoped-free pistol firing stays alive', state.alive && state.hp === 100 && state.ammo === 8, state);
    bots[id].activateItem(); await sleep(400); await shoot(id, 2);
    const aimed = await status(id);
    check(id + ' pumpkin scoped firing stays alive', aimed.alive && aimed.hp === 100 && aimed.ammo === 6
      && bots[id].inventory.slots[5]?.name === 'carved_pumpkin', aimed);
    bots[id].activateItem(); await sleep(160);
  }
  await select('A', 0); await bots.A.look(0, .8, true);
  const walkStart = {...bots.A.entity.position}, movesBefore = moves.A;
  bots.A.setControlState('forward', true); await shoot('A', 4); bots.A.setControlState('forward', false);
  const walking = await status('A');
  check('walking while firing keeps ordinary movement replies and full HP', walking.alive && walking.hp === 100
    && moves.A > movesBefore && Math.abs(walking.position.z - walkStart.z) > .2, {walkStart, walking, moves:moves.A - movesBefore});
  bots.A.setControlState('jump', true); await shoot('A'); bots.A.setControlState('jump', false); await sleep(600);
  const jumping = await status('A');
  check('jumping while firing stays alive and lands on map', jumping.alive && jumping.hp === 100
    && Math.abs(jumping.position.y - initial.position.y) < .1, jumping);
  bots.A._client.write('block_dig', {status:6,location:{x:0,y:0,z:0},face:0,sequence:0}); await sleep(2400);
  check('reload still completes after repeated recoil', (await status('A')).ammo === 30, await status('A'));
  // Return to the center of the audited lane before the close-range hit test;
  // walking north ended beside a real Dust II wall, behind which the old
  // "enemy 2.5 blocks further north" setup correctly hit architecture instead.
  const destination = {x:anchor[0]+1.5,y:anchor[1],z:anchor[2]+.5};
  await chat('A', '/tp MyGoCheckA ' + destination.x + ' ' + destination.y + ' ' + destination.z, 500);
  await shoot('A');
  const teleported = await status('A');
  check('ordinary server teleport remains valid after recoil confirmations', teleported.alive && teleported.hp === 100
    && Math.abs(teleported.position.x - destination.x) < .1 && Math.abs(teleported.position.z - destination.z) < .1, teleported);
  const floor = bots.A.entity.position.floored().offset(0, -1, 0), blockName = bots.A.blockAt(floor)?.name;
  bots.A._client.write('block_dig', {status:0,location:floor,face:1,sequence:0}); bots.A.swingArm('right'); await sleep(350);
  check('left click block fires one burst and keeps terrain intact', (await status('A')).ammo === 26
    && bots.A.blockAt(floor)?.name === blockName, {state:await status('A'),blockName});
  const teams = Object.fromEntries(await Promise.all([...('ABCD')].map(async id => [id,await status(id)])));
  const enemy = [...('BCD')].find(id => teams[id].team !== teams.A.team);
  await chat('A', '/tp MyGoCheck' + enemy + ' ' + destination.x + ' ' + destination.y + ' ' + (destination.z - 2.5), 500);
  await bots.A.lookAt(bots[enemy].entity.position.offset(0,1,0),true);
  const entity = bots.A.entities[bots[enemy].entity.id];
  bots.A.activateEntity(entity); bots.A.activateItem(); await sleep(350);
  check('right click entity and item fallback scope without firing', (await status('A')).ammo === 26
    && bots.A.inventory.slots[5]?.name === 'carved_pumpkin', await status('A'));
  const enemyBefore = await status(enemy);
  bots.A.attack(entity); await sleep(350);
  const enemyAfter = await status(enemy);
  check('left click entity fires one burst without extra tool melee damage', (await status('A')).ammo === 24
    && Math.abs(enemyBefore.hp - enemyAfter.hp - 50) < .01, {enemyBefore,enemyAfter});
  await select('A', 2);
  check('switching to knife closes scope', bots.A.inventory.slots[5]?.name !== 'carved_pumpkin', bots.A.inventory.slots[5]?.name);
  bots.A.attack(entity); await sleep(250);
  check('knife retains its left click melee damage', Math.abs(enemyAfter.hp - (await status(enemy)).hp - 40) < .01, await status(enemy));
  // The source download has a bare-stone exterior inside the rectangular
  // building bounds. Verify the new low-ground exclusion, not just a far TP.
  const map = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.resolve(__dirname,'../src/main/resources/pvp/mygo/maps/dust2.json.gz'))));
  const exterior = map.outsideGround.columns[0];
  const origin = anchor.map((v,i) => v - map.testAnchors.range[i]);
  const outsidePoint = [exterior[0],map.outsideGround.maxY,exterior[1]];
  check('exterior probe lies inside the map rectangle',outsidePoint.every((v,i) => v >= map.playBounds.min[i] && v <= map.playBounds.max[i]),outsidePoint);
  await chat('A', '/tp MyGoCheckA ' + (origin[0]+outsidePoint[0]+.5) + ' ' + (origin[1]+outsidePoint[1]) + ' ' + (origin[2]+outsidePoint[2]+.5),500);
  const outside = await status('A');
  check('bare-stone exterior inside the rectangle still eliminates the player', !outside.alive && outside.hp === 0
    && chats.some(r => r.text.includes('MyGoCheckA 阵亡（离开战场）')), outside);
  for (const id of 'ABCD') await chat(id, '/hub');
  await sleep(500);
  check('exit restores lobby inventory and removes scope', Object.values(bots).every(b => !b.inventory.items().some(i => i.name === 'iron_pickaxe' || i.name === 'carved_pumpkin')),
    Object.fromEntries(Object.entries(bots).map(([id,b]) => [id,b.inventory.items().map(i => i.name)])));
})().catch(error => {
  emit('failure', {message:error.stack}); process.exitCode = 1;
}).finally(async () => {
  emit('complete', {passed:checks.filter(c => c.pass).length, failed:checks.filter(c => !c.pass).length,
    checks, normalPhysics:true, movementRepliesSuppressed:false, movementPackets:moves});
  for (const bot of Object.values(bots)) bot.quit();
  await sleep(500);
  process.exit(process.exitCode || 0);
});
