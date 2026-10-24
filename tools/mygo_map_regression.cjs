'use strict';

// Reason: a real imported map needs ten safe, distinct starts, side-aware buy
// areas and height-aware C4 handling. Keep ordinary Mineflayer physics and all
// movement/teleport replies intact; compare the deployed blocks to the same
// authored-map blueprint used by the server instead of assuming a flat arena.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const modules = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const {Vec3} = require(path.join(modules, 'vec3'));
const root = path.resolve(__dirname, '..');
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || 'build/mygo-map-regression');
const port = Number(process.env.MYGO_TEST_PORT || 25575);
if (port === 25565 || !path.basename(runDir).includes('mygo')) throw new Error('Use an isolated MyGO server.');
const blueprintFile = path.join(root, 'src/main/resources/pvp/mygo/maps/dust2.json.gz');
const blueprintBytes = fs.readFileSync(blueprintFile);
const blueprint = JSON.parse(zlib.gunzipSync(blueprintBytes));
const ids = [...'ABCDEFGHIJ'];
const bots = {}, chats = [], packs = {}, checks = [], moves = {}, initialSpawns = {};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
fs.mkdirSync(runDir, {recursive:true});
let center;

function emit(event, detail = {}) {
  const row = JSON.stringify({time:new Date().toISOString(), event, ...detail});
  fs.appendFileSync(path.join(runDir, 'map-results.jsonl'), row + '\n');
  process.stdout.write(row + '\n');
}
function check(name, pass, actual) {
  const result = {name, pass:!!pass, actual}; checks.push(result); emit('assertion', result);
  if (!pass) throw new Error(name);
}
async function until(predicate, label, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (!await predicate()) {
    if (Date.now() > deadline) throw new Error('Timeout: ' + label);
    await sleep(80);
  }
}
async function chat(id, text, delay = 100) { bots[id].chat(text); await sleep(delay); }
function position(id) {
  const p = bots[id].entity.position;
  return {x:p.x, y:p.y, z:p.z};
}
function compact(values) { return {x:values[0], y:values[1], z:values[2]}; }
function world(values) {
  const p = Array.isArray(values) ? compact(values) : values;
  return {x:center.x + p.x, y:center.y + p.y, z:center.z + p.z};
}
function local(values) { return [values.x - center.x, values.y - center.y, values.z - center.z]; }
function contains(bounds, values) {
  return values.every((value, i) => value >= bounds.min[i] && value <= bounds.max[i]);
}
function atBlock(actual, expected, tolerance = .08) {
  return Math.abs(actual.x - expected.x - .5) <= tolerance
    && Math.abs(actual.y - expected.y) <= tolerance
    && Math.abs(actual.z - expected.z - .5) <= tolerance;
}
function blockAt(id, p) { return bots[id].blockAt(new Vec3(p.x, p.y, p.z)); }
function notchYaw(id) {
  const degrees = (Math.PI - bots[id].entity.yaw) * 180 / Math.PI;
  return ((degrees + 180) % 360 + 360) % 360 - 180;
}
function yawDistance(a, b) { return Math.abs(((a - b + 180) % 360 + 360) % 360 - 180); }
function uuid(id) {
  return String(bots[id].player?.uuid || bots[id].entity?.uuid || bots[id]._client.uuid).toLowerCase();
}
async function status(id) {
  const from = chats.length;
  await chat(id, '/pvp mygo status', 80);
  let message;
  await until(() => {
    message = chats.slice(from).find(row => row.id === id && row.text.includes('MyGO round='))?.text;
    return !!message;
  }, id + ' status reply', 4000);
  const state = Object.fromEntries([...message.matchAll(/(?:^| )([A-Za-z]+)=([^ ]+)/g)].map(m => [m[1], m[2]]));
  for (const key of ['round', 'remainingTicks', 'team', 'hp', 'cash', 'ammo', 'reserve']) state[key] = Number(state[key]);
  for (const key of ['alive', 'scoped', 'awZoom', 'bombPlanted', 'vest', 'helmet']) state[key] = state[key] === 'true';
  // Parse only the named compact field; BlockPos values elsewhere in the reply
  // (spawn, bomb and sites) must never be mistaken for this arena's translation.
  const range = message.match(/(?:^| )range=(-?\d+),(-?\d+),(-?\d+)(?: |$)/);
  if (!range) throw new Error('Missing explicit range coordinate in status: ' + message);
  state.range = {x:Number(range[1]), y:Number(range[2]), z:Number(range[3])};
  const siteText = message.match(/(?:^| )sites=(.*)$/)?.[1];
  state.sites = [...(siteText || '').matchAll(/BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}/g)]
    .map(m => ({x:Number(m[1]), y:Number(m[2]), z:Number(m[3])}));
  state.position = position(id);
  return state;
}
async function states() { return Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)]))); }
async function waitPhase(id, phase, round, timeout = 25000) {
  let result;
  await until(async () => {
    result = await status(id);
    return result.phase === phase && (round === undefined || result.round === round);
  }, `${id} round ${round ?? '*'} ${phase}`, timeout);
  return result;
}
async function select(id, slot) { bots[id].setQuickBarSlot(slot); await sleep(100); }
async function teleport(id, p, delay = 150) {
  await chat('A', `/tp MyGoCheck${id} ${p.x + .5} ${p.y} ${p.z + .5}`, delay);
  await until(() => atBlock(position(id), p, .15), id + ' operator teleport', 5000);
  await until(() => !!blockAt(id, {x:p.x, y:p.y - 1, z:p.z}), id + ' destination chunk', 5000);
}
function connect(id) {
  const bot = bots[id] = mineflayer.createBot({host:'127.0.0.1', port,
    username:'MyGoCheck' + id, version:'1.21.1', auth:'offline'});
  moves[id] = 0;
  // Observe writes; no position shim, physics override or synthetic TP ack.
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (name === 'position' || name === 'position_look') moves[id]++;
    return write(name, packet);
  };
  bot.on('messagestr', text => {
    chats.push({id, text});
    if (text.includes('阵亡') || text.includes('半场')) emit('gameMessage', {id, text});
  });
  bot.on('error', error => emit('clientError', {id, message:error.stack}));
  bot.on('kicked', reason => emit('kicked', {id, reason}));
  bot._client.on('add_resource_pack', async packet => {
    bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:3});
    try {
      const response = await fetch(packet.url, {signal:AbortSignal.timeout(15000)});
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const bytes = Buffer.from(await response.arrayBuffer());
      const sha1 = crypto.createHash('sha1').update(bytes).digest('hex');
      if (sha1 !== packet.hash) throw new Error('Resource pack SHA1 mismatch');
      packs[id] = {sha1, size:bytes.length};
      bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:4});
      bot._client.write('resource_pack_receive', {uuid:packet.uuid, result:0});
    } catch (error) { emit('packError', {id, message:error.message}); }
  });
}

function configuredSpawn(side, actual) {
  return blueprint.spawns[side].find(spawn => atBlock(actual, world(spawn.pos)));
}
async function validateSpawns(label, expectedSides) {
  const snapshot = await states();
  const positions = new Set();
  for (const id of ids) {
    const state = snapshot[id], p = position(id), spawn = configuredSpawn(state.side, p);
    check(`${label} ${id} occupies a configured ${state.side} spawn`, !!spawn && state.alive && state.hp === 100
      && state.phase === 'BUY' && (!expectedSides || state.side === expectedSides[id]), state);
    const key = `${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`;
    positions.add(key);
    check(`${label} ${id} faces its authored exit and starts inside its buy area`,
      yawDistance(notchYaw(id), spawn.yaw) < 1 && contains(blueprint.buyZones[state.side], local({x:Math.floor(p.x), y:Math.floor(p.y), z:Math.floor(p.z)})),
      {side:state.side, yaw:notchYaw(id), expectedYaw:spawn.yaw, pos:p});
    const feet = new Vec3(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
    await until(() => !!bots[id].blockAt(feet.offset(0, -1, 0)), id + ' spawn terrain', 5000);
    const support = bots[id].blockAt(feet.offset(0,-1,0));
    const feetBlock = bots[id].blockAt(feet), headBlock = bots[id].blockAt(feet.offset(0,1,0));
    check(`${label} ${id} has a solid floor and clear body space`, support.boundingBox === 'block'
      && feetBlock?.boundingBox === 'empty' && headBlock?.boundingBox === 'empty'
      && ![feetBlock?.name,headBlock?.name].some(name => name === 'water' || name === 'lava'),
      {floor:support.name, feet:feetBlock?.name, head:headBlock?.name});
    if (label === 'opening') initialSpawns[id] = {side:state.side, team:state.team, pos:world(spawn.pos), yaw:spawn.yaw};
  }
  check(label + ' all ten spawn slots are distinct and teams have five players', positions.size === 10
    && ids.filter(id => snapshot[id].side === 'T').length === 5 && ids.filter(id => snapshot[id].side === 'CT').length === 5,
    {unique:positions.size, sides:Object.fromEntries(ids.map(id => [id,snapshot[id].side]))});
  return snapshot;
}

async function verifyImportedTerrain(id, anchor, label) {
  await teleport(id, world(anchor));
  const samples = blueprint.blocks.filter(([x,y,z]) => Math.abs(x - anchor[0]) <= 10
    && Math.abs(z - anchor[2]) <= 10 && y >= anchor[1] - 3 && y <= anchor[1] + 8);
  // Include multiple palette entries and positions around each landmark rather
  // than testing only the flat supporting block below the operator teleport.
  const chosen = [];
  const seen = new Set();
  for (const cell of samples) {
    if (!seen.has(cell[3])) { chosen.push(cell); seen.add(cell[3]); }
    if (chosen.length === 12) break;
  }
  if (chosen.length < 6) chosen.push(...samples.filter((_, i) => i % Math.max(1, Math.floor(samples.length / 6)) === 0).slice(0, 6));
  const actual = [];
  for (const [x,y,z,palette] of chosen) {
    const p = world([x,y,z]), expected = blueprint.palette[palette].Name.replace(/^minecraft:/, '');
    await until(() => !!blockAt(id,p), label + ' terrain chunk', 5000);
    actual.push({pos:p, expected, actual:blockAt(id,p).name});
  }
  check(label + ' deployed landmark blocks match the original-map blueprint', actual.length >= 6
    && actual.every(sample => sample.expected === sample.actual), actual);
}

async function dropAndRecover(anchor, label, allStates) {
  const t = ids.filter(id => allStates[id].side === 'T');
  const state = await status(t[0]);
  const carrier = t.find(id => uuid(id) === state.bombCarrier.toLowerCase());
  if (!carrier) throw new Error('Cannot identify C4 carrier: ' + state.bombCarrier);
  const receiver = t.find(id => id !== carrier);
  await teleport(carrier, world(anchor)); await select(carrier, 5);
  check(label + ' C4 carrier can stand at the authored height', (await status(carrier)).alive
    && bots[carrier].inventory.slots[41]?.name === 'redstone', {state:await status(carrier), anchor});
  const before = new Set(Object.keys(bots[carrier].entities));
  bots[carrier]._client.write('block_dig', {status:4, location:{x:0,y:0,z:0}, face:0, sequence:0});
  let marker;
  await until(() => {
    marker = Object.values(bots[carrier].entities).find(entity => !before.has(String(entity.id))
      && entity.name === 'armor_stand' && Math.abs(entity.position.x - center.x - anchor[0] - .5) < .1
      && Math.abs(entity.position.z - center.z - anchor[2] - .5) < .1);
    return !!marker && !bots[carrier].inventory.slots[41];
  }, label + ' C4 ground marker', 800);
  check(label + ' dropped C4 stays above its actual supporting floor',
    Math.abs(marker.position.y - center.y - Math.ceil(anchor[1]) - .2) < .05
      && !bots[carrier].inventory.slots[41], {anchor:world(anchor), marker:{...marker.position}});
  const home = blueprint.spawns.T.find(spawn => !atBlock(position(receiver), world(spawn.pos))) || blueprint.spawns.T[0];
  await teleport(carrier, world(home.pos));
  await teleport(receiver, world(anchor));
  await until(() => bots[receiver].inventory.slots[41]?.name === 'redstone', label + ' C4 pickup', 4000);
  const picked = await status(receiver);
  check(label + ' another attacker recovers the C4 without digging or teleporting it to flat Y',
    picked.alive && picked.hp === 100 && picked.bombCarrier.toLowerCase() === uuid(receiver), picked);
  return receiver;
}

async function plantAndDefuse(index, round) {
  const live = await waitPhase('A', 'LIVE', round, 22000);
  const snapshot = await states();
  const carrier = ids.find(id => uuid(id) === live.bombCarrier.toLowerCase());
  const defender = ids.find(id => snapshot[id].side === 'CT');
  const site = blueprint.sites[index];
  if (!carrier) throw new Error('Cannot find live C4 carrier');
  await verifyImportedTerrain(carrier, site.pos, 'site ' + site.name);
  await select(carrier, 5); bots[carrier].setControlState('sneak',true); await sleep(180); bots[carrier].activateItem();
  let planted;
  await until(async () => { planted = await status(carrier); return planted.bombPlanted; }, 'site ' + site.name + ' planting', 6500);
  bots[carrier].setControlState('sneak',false);
  check('site ' + site.name + ' accepts a real three-second C4 plant', planted.phase === 'LIVE'
    && planted.alive && planted.remainingTicks > 650 && planted.remainingTicks <= 800,
    {site:world(site.pos), planted});
  await teleport(carrier, world(blueprint.spawns.T[0].pos));
  await teleport(defender, world(site.pos)); await select(defender, 5);
  bots[defender].setControlState('sneak',true); await sleep(180); bots[defender].activateItem();
  const defused = await waitPhase(defender, 'POST', round + 1, 11500);
  bots[defender].setControlState('sneak',false);
  const expectedScore = live.score.split(':').map(Number);
  expectedScore[snapshot[defender].team]++;
  check('site ' + site.name + ' permits a real C4 defuse and one round settlement',
    defused.alive && defused.score === expectedScore.join(':')
      && chats.some(row => row.id === defender && row.text.includes('C4 已拆除')),
    {before:live.score, expectedScore:expectedScore.join(':'), defused});
}

async function main() {
  emit('mapFixture', {id:blueprint.id, name:blueprint.name, cells:blueprint.blocks.length,
    blueprintSha256:crypto.createHash('sha256').update(blueprintBytes).digest('hex')});
  check('fixture contains a substantial authored map and two bomb sites', blueprint.blocks.length > 10000
    && blueprint.sites.length === 2 && blueprint.spawns.T.length === 5 && blueprint.spawns.CT.length === 5,
    {blocks:blueprint.blocks.length, sites:blueprint.sites.map(site => site.name)});
  for (const id of ids) connect(id);
  await until(() => ids.every(id => bots[id].entity && bots[id].inventory), 'ten ordinary clients', 30000);
  await sleep(2500);
  // The isolated launcher pre-ops A-F. Grant only the four additional named
  // fixture accounts access to the same read-only diagnostics.
  for (const id of 'GHIJ') await chat('A','/op MyGoCheck' + id,100);
  for (const id of ids) await chat(id,'/pvp join mygo',100);
  await until(() => ids.every(id => packs[id]), 'all ten verified resource packs', 30000);
  for (const id of ids) await chat(id,'/pvp join mygo',100);
  await chat('A','/pvp start');
  await until(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')),
    'imported map generation and ten loadouts', 120000);
  const first = await status('A');
  center = {x:first.range.x - blueprint.testAnchors.range[0], y:first.range.y - blueprint.testAnchors.range[1],
    z:first.range.z - blueprint.testAnchors.range[2]};
  check('status identifies the imported map and both configured world-space sites', first.map === blueprint.id
    && first.sites.length === 2 && first.sites.every((site,i) => Object.keys(site).every(key => site[key] === world(blueprint.sites[i].pos)[key])),
    {center, map:first.map, sites:first.sites});
  const opening = await validateSpawns('opening');
  const freezeStart = chats.length;
  for (const id of ids) {
    const target = blueprint.sites.find(site => !contains(blueprint.buyZones[opening[id].side], site.pos));
    if (!target) throw new Error('No configured site outside ' + opening[id].side + ' buy zone');
    const p = world(target.pos);
    await chat('A',`/tp MyGoCheck${id} ${p.x + .5} ${p.y} ${p.z + .5}`,15);
  }
  await sleep(450);
  check('buy freeze returns every player to their own numbered spawn', ids.every(id => atBlock(position(id),initialSpawns[id].pos))
    && (await status('A')).phase === 'BUY' && !chats.slice(freezeStart).some(row => row.text.includes('离开战场')),
    Object.fromEntries(ids.map(id => [id,position(id)])));
  const groups = [ids.filter(id => opening[id].team === 0), ids.filter(id => opening[id].team === 1)];
  await chat(groups[0][0],'/pvp mygo buy mp5');
  check('a configured buy zone accepts a real weapon purchase', (await status(groups[0][0])).cash === 350
    && bots[groups[0][0]].inventory.slots[36]?.name === 'iron_pickaxe', await status(groups[0][0]));
  await waitPhase('A','LIVE',1,18000);
  for (const id of ids) {
    await select(id,1); await bots[id].look(0,Math.PI / 2,true);
    const before = await status(id);
    bots[id].activateItem(); await sleep(200); bots[id].swingArm('right'); await sleep(350);
    const after = await status(id);
    check(id + ' can scope and fire at its real spawn without leaving the battlefield', after.alive && after.hp === 100
      && after.scoped && after.ammo === before.ammo - 1 && atBlock(position(id),initialSpawns[id].pos)
      && bots[id].inventory.slots[5]?.name === 'carved_pumpkin', {before,after});
    await sleep(200); bots[id].activateItem();
  }
  await verifyImportedTerrain('A',blueprint.testAnchors.range,'range landmark');
  for (let round = 1; round <= 6; round++) {
    if (round > 1) await waitPhase('A','BUY',round,12000);
    if (round === 6) {
      await chat(groups[1][0],'/pvp mygo buy mp5');
      check('a surviving primary is present immediately before halftime', bots[groups[1][0]].inventory.slots[36]?.name === 'iron_pickaxe',
        await status(groups[1][0]));
    }
    await waitPhase('A','LIVE',round,22000);
    // Three wins for each persistent team leave room to exercise both sites
    // after halftime. These OP teleports test genuine bounds elimination.
    const winner = round <= 3 ? 0 : 1, losers = groups[1-winner];
    const outside = world([blueprint.playBounds.max[0] + 12, blueprint.spawns.T[0].pos[1], blueprint.spawns.T[0].pos[2]]);
    for (const id of losers) await chat('A',`/tp MyGoCheck${id} ${outside.x + .5} ${outside.y} ${outside.z + .5}`,20);
    const post = await waitPhase(groups[winner][0],'POST',round + 1,7000);
    const expectedScore = round <= 3 ? `${round}:0` : `3:${round - 3}`;
    const result = await states();
    check(`round ${round} bounds eliminations settle once and preserve the winning team`, post.score === expectedScore
      && losers.every(id => !result[id].alive && result[id].hp === 0)
      && groups[winner].every(id => result[id].alive && result[id].hp === 100),
      {score:post.score, expectedScore, alive:Object.fromEntries(ids.map(id => [id,result[id].alive]))});
  }
  await waitPhase('A','BUY',7,12000);
  const oppositeSides = Object.fromEntries(ids.map(id => [id,opening[id].side === 'T' ? 'CT' : 'T']));
  const half = await validateSpawns('halftime',oppositeSides);
  check('halftime resets all ten balances and equipment while preserving 3:3 score',
    ids.every(id => half[id].round === 7 && half[id].cash === 1600 && half[id].score === '3:3'
      && !half[id].vest && !half[id].helmet && !half[id].scoped && !bots[id].inventory.slots[36]),half);
  const defender = ids.find(id => half[id].side === 'CT');
  await chat(defender,'/pvp mygo buy defuser');
  check('new CT spawn permits a defuse-kit purchase after the swap', bots[defender].inventory.slots[41]?.name === 'shears'
    && (await status(defender)).cash === 1200,await status(defender));
  await waitPhase('A','LIVE',7,22000);
  await dropAndRecover(blueprint.testAnchors.range,'lower floor',half);
  const anchors = [blueprint.testAnchors.raised, ...blueprint.sites.map(site => site.pos),
    ...blueprint.spawns.T.map(spawn => spawn.pos), ...blueprint.spawns.CT.map(spawn => spawn.pos)].filter(Boolean);
  const raised = anchors.find(anchor => anchor[1] !== blueprint.testAnchors.range[1]);
  check('the imported map exposes a safe second floor height for C4 verification',!!raised,
    {lower:blueprint.testAnchors.range, second:raised});
  await dropAndRecover(raised,'raised floor',half);
  // Regression from the authored map: bottom slab at (13,6,30), with a
  // top-slab ceiling at y8. A player fits at y6.5; a two-clear-block check for
  // the non-colliding item marker wrongly sent the C4 back to T spawn.
  await dropAndRecover([13,6.5,30],'low-ceiling half slab',half);
  await plantAndDefuse(0,7);
  await waitPhase('A','BUY',8,12000);
  if (bots[defender].inventory.slots[41]?.name !== 'shears') await chat(defender,'/pvp mygo buy defuser');
  await plantAndDefuse(1,8);
  const deathMessages = chats.filter(row => row.text.includes('离开战场'));
  check('only the thirty deliberate out-of-bounds eliminations occur',
    deathMessages.filter(row => row.id === 'A').length === 30,
    {broadcastMessages:deathMessages.length, messagesSeenByA:deathMessages.filter(row => row.id === 'A').length});
  for (const id of ids) await chat(id,'/hub',80);
  await sleep(500);
  check('all ten players leave with their temporary guns and scopes removed',ids.every(id =>
    !bots[id].inventory.items().some(item => item.name === 'iron_pickaxe' || item.name === 'carved_pumpkin')),
    Object.fromEntries(ids.map(id => [id,bots[id].inventory.items().map(item => item.name)])));
}

Promise.race([main(),sleep(12 * 60 * 1000).then(() => { throw new Error('Global 12-minute map regression timeout'); })])
  .catch(error => { emit('failure',{message:error.stack}); process.exitCode = 1; })
  .finally(async () => {
    emit('complete',{passed:checks.filter(check => check.pass).length,failed:checks.filter(check => !check.pass).length,
      checks,normalPhysics:true,movementRepliesSuppressed:false,movementPackets:moves,renderingVerified:false});
    for (const bot of Object.values(bots)) { bot.clearControlStates(); bot.quit(); }
    await sleep(600); process.exit(process.exitCode || 0);
  });
