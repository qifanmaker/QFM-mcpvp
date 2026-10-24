'use strict';

// Reason: the former layout confused A short/B tunnels with the two spawns,
// and invisible bounds killed players on walkable architecture. Verify the
// corrected tactical landmarks, normal movement, safe recovery and both sites
// with ten ordinary clients. Position replies and physics are never suppressed.
// CT mid's open courtyard is not CT spawn: the actual CT starting area is
// farther toward A, beneath the catwalk, as checked against Valve's overview.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const modules = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const {Vec3} = require(path.join(modules, 'vec3'));
const root = path.resolve(__dirname, '..');
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || 'build/mygo-map-safety');
const port = Number(process.env.MYGO_TEST_PORT || 25585);
if (port === 25565 || !path.basename(runDir).includes('mygo')) throw new Error('Use an isolated MyGO server.');
const blueprintFile = path.join(root, 'src/main/resources/pvp/mygo/maps/dust2.json.gz');
const blueprintBytes = fs.readFileSync(blueprintFile);
const blueprint = JSON.parse(zlib.gunzipSync(blueprintBytes));
const ids = [...'ABCDEFGHIJ'];
const sourceOrigin = [52,13,42];
const lobby = {}, abilities = {};
const bots = {}, chats = [], packs = {}, checks = [], moves = {}, initialSpawns = {};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
fs.mkdirSync(runDir, {recursive:true});
let center;

function emit(event, detail = {}) {
  const row = JSON.stringify({time:new Date().toISOString(), event, ...detail});
  fs.appendFileSync(path.join(runDir, 'safety-results.jsonl'), row + '\n');
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
  for (const key of ['round', 'remainingTicks', 'team', 'hp', 'cash', 'ammo', 'reserve', 'armor', 'scopeLevel']) state[key] = Number(state[key]);
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
  bot._client.on('remove_resource_pack', packet => { delete packs[id]; emit('packRemoved',{id,packet}); });
  bot._client.on('abilities', packet => { abilities[id] = packet; });
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

function fromSource(values) { return values.map((value, i) => value - sourceOrigin[i]); }
function samePosition(a,b) { return a.every((value,i) => value === b[i]); }
async function healthy(id,label) {
  const state=await status(id);
  check(label,state.alive && state.hp === 100,{state,position:position(id)});
  return state;
}
async function walk(id,target,label,expectedCell) {
  const start=position(id),trail=[];
  const record=()=>trail.push(position(id)); bots[id].on('physicsTick',record);
  try {
    await bots[id].lookAt(new Vec3(target.x+.5,target.y+1.62,target.z+.5),true);
    await sleep(120);bots[id].setControlState('forward',true);
    await until(()=>Math.hypot(position(id).x-start.x,position(id).z-start.z)>=.9,label+' movement',4000);
    bots[id].setControlState('forward',false);await sleep(250);
  } finally { bots[id].clearControlStates();bots[id].removeListener('physicsTick',record); }
  const state=await healthy(id,label+' remains alive at 100 HP');
  check(label+' uses actual client physics and movement packets',trail.length>=3
    && Math.hypot(position(id).x-start.x,position(id).z-start.z)>.8,{start,end:position(id),packets:moves[id],trail});
  if(expectedCell)check(label+' crosses the formerly excluded block column',trail.some(p=>
    Math.floor(p.x-center.x)===expectedCell[0] && Math.floor(p.z-center.z)===expectedCell[2]),{expectedCell,trail});
  return state;
}
async function waitPlanted(id,timeout=6500) {
  let result;await until(async()=>{result=await status(id);return result.bombPlanted;},'C4 installation',timeout);return result;
}
async function siteRound(index,round) {
  const live=await waitPhase('A','LIVE',round,22000),before=await states();
  const carrier=ids.find(id=>uuid(id)===live.bombCarrier.toLowerCase()),defender=ids.find(id=>before[id].side==='CT');
  if(!carrier)throw new Error('No C4 carrier');
  const site=blueprint.sites[index],anchor=world(site.pos);
  await teleport(carrier,anchor);await select(carrier,5);await sleep(150);
  const foot=blockAt(carrier,{...anchor,y:anchor.y-1});
  check('true '+site.name+' anchor has authored support and two clear body cells',foot?.name===(index===0?'red_concrete':'sandstone_slab')
    && blockAt(carrier,anchor)?.boundingBox==='empty' && blockAt(carrier,{...anchor,y:anchor.y+1})?.boundingBox==='empty',
    {site,foot:foot?.name,properties:foot?.getProperties(),position:position(carrier)});
  if(index===1)check('B anchor uses the full upper face of a top slab',foot.getProperties().type==='top',foot.getProperties());
  const cash=(await status(carrier)).cash;
  bots[carrier].setControlState('sneak',true);await sleep(180);bots[carrier].activateItem();
  const planted=await waitPlanted(carrier);bots[carrier].setControlState('sneak',false);
  check('true '+site.name+' accepts C4 and pays the planter once',planted.phase==='LIVE' && planted.alive && planted.cash===cash+300
    && planted.remainingTicks<=800 && planted.remainingTicks>740,{site,planted});
  await teleport(carrier,world(blueprint.spawns.T[0].pos));
  await teleport(defender,anchor);await select(defender,5);bots[defender].setControlState('sneak',true);
  await sleep(180);bots[defender].activateItem();
  const post=await waitPhase(defender,'POST',round,12500);bots[defender].setControlState('sneak',false);
  const score=live.score.split(':').map(Number);score[before[defender].team]++;
  check('true '+site.name+' permits defuse and settles the current round exactly once',post.round===round && post.score===score.join(':')
    && post.alive,{site,before:live.score,post});
}
async function buttonDecoration(id,label,expectedCenter) {
  const button=blueprint.blocks.filter(cell=>blueprint.palette[cell[3]].Name.endsWith('_button'))
    .sort((a,b)=>Math.hypot(a[0]-blueprint.sites[1].pos[0],a[2]-blueprint.sites[1].pos[2])
      -Math.hypot(b[0]-blueprint.sites[1].pos[0],b[2]-blueprint.sites[1].pos[2]))[0];
  if(!button)throw new Error('Map fixture has no button decoration');
  const p=world(button.slice(0,3));await teleport(id,world(blueprint.sites[1].pos));
  await until(()=>!!blockAt(id,p),'decorative button chunk',5000);
  const displays=Object.values(bots[id].entities).filter(entity=>entity.name==='block_display'
    && entity.position.distanceTo(new Vec3(p.x,p.y,p.z))<.1);
  check(label,blockAt(id,p).name==='air' && displays.length===1
    && (!expectedCenter || ['x','y','z'].every(axis=>center[axis]===expectedCenter[axis])),
    {center,expectedCenter,position:p,block:blockAt(id,p).name,displays:displays.map(entity=>({id:entity.id,position:entity.position}))});
  return {position:p,id:displays[0].id,uuid:displays[0].uuid};
}
async function returnToLobby(label) {
  for(const id of ids)await chat(id,'/hub',80);
  await until(()=>ids.every(id=>bots[id].inventory.slots[5]?.name==='golden_helmet'
    && bots[id].inventory.items().some(item=>item.name==='apple'&&item.count===3)
    && !bots[id].inventory.items().some(item=>item.name==='iron_pickaxe') && !packs[id]),'all ten lobby inventories and packs restored',15000);
  check(label,true,Object.fromEntries(ids.map(id=>[id,bots[id].inventory.items().map(item=>({name:item.name,count:item.count}))])));
}
async function main() {
  emit('mapFixture',{map:blueprint.id,cells:blueprint.blocks.length,blueprintSha256:crypto.createHash('sha256').update(blueprintBytes).digest('hex')});
  const expectedT=[24,27,30,33,36].map(z=>fromSource([-2,18,z]));
  const expectedCt=[54,57,60,63,66].map(z=>fromSource([95,11,z]));
  check('spawns are the real T platform and roofed CT spawn instead of A short/B tunnels/CT mid',
    blueprint.spawns.T.every((spawn,i)=>samePosition(spawn.pos,expectedT[i]))
    && blueprint.spawns.CT.every((spawn,i)=>samePosition(spawn.pos,expectedCt[i])),blueprint.spawns);
  check('A and B anchors follow the actual painted platform and front of B boxes',samePosition(blueprint.sites[0].pos,fromSource([103,18,88]))
    && samePosition(blueprint.sites[1].pos,fromSource([112,15,-2])),blueprint.sites);
  for(const id of ids)connect(id);
  await until(()=>ids.every(id=>bots[id].entity && bots[id].inventory),'ten ordinary clients',30000);await sleep(1500);
  for(const id of 'GHIJ')await chat('A','/op MyGoCheck'+id,40);
  for(const id of ids){
    await chat('A',`/item replace entity MyGoCheck${id} armor.head with minecraft:golden_helmet`,35);
    await chat('A',`/item replace entity MyGoCheck${id} hotbar.7 with minecraft:apple 3`,35);
  }
  for(const id of ids)await chat(id,'/pvp join mygo',60);
  await until(()=>ids.every(id=>packs[id] && chats.some(row=>row.id===id && row.text.includes('MyGO 资源包加载成功'))),
    'all ten verified resource packs acknowledged by server',30000);
  for(const id of ids)await chat(id,'/pvp join mygo',50);
  await chat('A','/pvp start',50);
  await until(()=>ids.every(id=>bots[id].inventory.items().some(item=>item.name==='iron_pickaxe')),'ten match loadouts',120000);
  const first=await status('A');
  center={x:first.range.x-blueprint.testAnchors.range[0],y:first.range.y-blueprint.testAnchors.range[1],z:first.range.z-blueprint.testAnchors.range[2]};
  for(const id of ids)lobby[id]='minecraft:overworld';
  const opening=await validateSpawns('opening');
  const ts=ids.filter(id=>opening[id].side==='T'),cts=ids.filter(id=>opening[id].side==='CT');
  const carrier=ids.find(id=>uuid(id)===first.bombCarrier.toLowerCase()),walker=ts.find(id=>id!==carrier),ctWalker=cts[0];
  for(const id of ids){const p=world(blueprint.sites[0].pos);await chat('A',`/tp MyGoCheck${id} ${p.x+.5} ${p.y} ${p.z+.5}`,15);}
  await until(()=>ids.every(id=>atBlock(position(id),initialSpawns[id].pos,.15)),'freeze returns all ten numbered spawn slots',3000);
  check('freeze still returns each player to the correct new buy-zone spawn',(await status('A')).phase==='BUY',
    Object.fromEntries(ids.map(id=>[id,position(id)])));
  await chat(ctWalker,'/pvp mygo buy kit',50);
  check('real CT buy zone permits a $400 kit purchase',(await status(ctWalker)).cash===400
    && bots[ctWalker].inventory.items().some(item=>item.name==='shears'),await status(ctWalker));
  await waitPhase('A','LIVE',1,18000);
  const tp=position(walker);await walk(walker,{x:tp.x+3,y:tp.y,z:tp.z-.5},'T platform natural walking');
  const cp=position(ctWalker);await walk(ctWalker,{x:cp.x-.5,y:cp.y,z:cp.z+3},'CT spawn underpass natural walking');
  await teleport(walker,world([36,1,23]));
  await walk(walker,world([37.5,1,23]),'old lethal low stone route',[37,1,23]);
  await teleport(walker,world([73,32,-12]));await sleep(350);
  const roofStart=position(walker),heights=[];const record=()=>heights.push(position(walker).y);
  bots[walker].on('physicsTick',record);bots[walker].setControlState('jump',true);await sleep(800);
  bots[walker].setControlState('jump',false);await sleep(650);bots[walker].removeListener('physicsTick',record);
  await healthy(walker,'highest authored roof stays playable during a normal jump');
  check('roof test performs a real jump above the old maximum height',heights.length>5 && Math.max(...heights)>roofStart.y+.5,
    {roofStart,maxHeight:Math.max(...heights),end:position(walker)});
  for(const y of [-80,-256]){
    await chat('A',`/execute in pvp:arena run tp MyGoCheck${walker} ${position(walker).x} ${y} ${position(walker).z}`,60);
    await until(()=>position(walker).y>center.y+blueprint.playBounds.min[1],'void rescue '+y,3500);
    const rescued=await healthy(walker,'OP void teleport to Y '+y+' safely returns without elimination');
    check('void rescue '+y+' keeps the player inside the same match',rescued.round===1 && rescued.phase==='LIVE'
      && bots[walker].game.gameMode==='adventure',{state:rescued,position:position(walker)});
  }
  await teleport(carrier,world(fromSource([21,18,11])));await select(carrier,5);
  const oldCash=(await status(carrier)).cash;bots[carrier].setControlState('sneak',true);await sleep(180);bots[carrier].activateItem();await sleep(3600);
  bots[carrier].setControlState('sneak',false);const rejected=await status(carrier);
  check('former garden A marker is no longer an installable bomb zone',!rejected.bombPlanted && rejected.cash===oldCash && rejected.alive,rejected);
  await siteRound(0,1);await waitPhase('A','BUY',2,10000);
  if(!bots[ctWalker].inventory.items().some(item=>item.name==='shears'))await chat(ctWalker,'/pvp mygo buy kit',50);
  await siteRound(1,2);
  const originalButton=await buttonDecoration(ctWalker,'inert button decoration retains a display while its interactive block is air');
  // Keep this match alive while every player leaves the B chunks. A changed
  // entity ID with the same UUID proves a genuine server-side NBT reload.
  await waitPhase('A','LIVE',3,25000);
  // The server keeps extra rings beyond the client's four-chunk view. The
  // southwest authored stone ground is seven chunks away on both axes.
  for(const [i,id] of ids.entries())await teleport(id,world([-76,1,62+i]),50);
  await until(()=>!blockAt(ctWalker,originalButton.position),'B chunk leaves normal client view',10000);
  await chat('A','/save-all flush',300);await sleep(30000);
  const reloadedButton=await buttonDecoration(ctWalker,'active match retains exactly one button after leaving and reloading its chunk');
  check('button chunk actually reloads its saved entity and preserves its active ownership',
    originalButton.id!==reloadedButton.id && originalButton.uuid && originalButton.uuid===reloadedButton.uuid,
    {before:originalButton,after:reloadedButton});
  check('walking roof and void scenarios cause no battlefield or environmental eliminations',!chats.some(row=>row.id==='A'&&/MyGoCheck[A-J] 阵亡/.test(row.text)),
    chats.filter(row=>row.id==='A'&&/MyGoCheck[A-J] 阵亡/.test(row.text)));
  await returnToLobby('all ten clients restore their original lobby inventory and remove MyGO pack');
  // A display can unload and reload as a different entity object. Reuse the
  // released arena to catch decorations left in chunk NBT after the first match.
  const previousCenter={...center};await sleep(12000);const joinedAt=chats.length;
  for(const id of ids)await chat(id,'/pvp join mygo',60);
  await until(()=>ids.every(id=>packs[id] && chats.slice(joinedAt).some(row=>row.id===id
    && row.text.includes('MyGO 资源包加载成功'))),'second-match resource pack acknowledgements',30000);
  for(const id of ids)await chat(id,'/pvp join mygo',50);
  await chat('A','/pvp start',50);
  await until(()=>ids.every(id=>bots[id].inventory.items().some(item=>item.name==='iron_pickaxe')),'second-match loadouts',120000);
  const second=await waitPhase('A','LIVE',1,20000);
  center={x:second.range.x-blueprint.testAnchors.range[0],y:second.range.y-blueprint.testAnchors.range[1],z:second.range.z-blueprint.testAnchors.range[2]};
  const secondStates=await states(),secondCt=ids.find(id=>secondStates[id].side==='CT');
  await buttonDecoration(secondCt,'reused arena has exactly one button display after chunk reload and match cleanup',previousCenter);
  await returnToLobby('second match also restores all ten inventories and removes its resource pack');
}
Promise.race([main(),sleep(5*60*1000).then(()=>{throw new Error('Global five-minute safety test timeout');})])
  .catch(error=>{emit('failure',{message:error.stack});process.exitCode=1;})
  .finally(async()=>{
    emit('mapSafetyComplete',{passed:checks.filter(check=>check.pass).length,failed:checks.filter(check=>!check.pass).length,
      checks,normalPhysics:true,movementRepliesSuppressed:false,movementPackets:moves,renderingVerified:false});
    // Failed assertions must also leave through the ordinary restoration path.
    for(const bot of Object.values(bots)){bot.clearControlStates();if(bot.entity)bot.chat('/hub');}
    await sleep(500);
    for(const bot of Object.values(bots))bot.quit();
    await sleep(700);process.exit(process.exitCode||0);
  });
