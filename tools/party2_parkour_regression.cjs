'use strict';

// Reason: Cave Parkour's full cave is insufficient without its source branch
// graph, lamp-gated checkpoints and personal retry paths. Pre-review: isolate
// 25602, use real menu/stick/drop packets, and label position/damage/time fixtures.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const who = id => `MyGoCheck${id}`;
const key = p => p.join(',');
const xyz = key => key.split(',').map(Number);
const strip = state => state.replace(/^minecraft:/, '').replace(/\[.*$/, '');

class ParkourProbe extends PartyProbe {
  constructor() {
    super({ port: 25602, runDir: path.join(__dirname, '../build/mygo-party2-parkour') });
    this.rules = JSON.parse(fs.readFileSync(path.join(this.root, 'src/main/resources/pvp/party/cave_parkour_routes.json'), 'utf8'));
  }

  source(x, y, z) { return new Vec3(x+this.offset.x, y+this.offset.y, z+this.offset.z); }
  position(id) { return new Vec3(this.bots[id].probePosition.x, this.bots[id].probePosition.y, this.bots[id].probePosition.z); }
  progressSince(id, stage, since) {
    return this.chats.some(row => row.id===id && row.at>=since && row.text===`检查点 ${stage}/15`);
  }
  async condition(condition) {
    const token = `P2_PARKOUR_CONDITION_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(token));
  }
  async sprint(ticks) {
    const since = this.serverLines.length;
    this.log('fixtureNote', { description:`Advance ${ticks} ticks on the isolated server; wait for actual Sprint completed.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(since).some(row=>/Sprint completed/i.test(row.text)), 'sprint completion', 90000);
  }
  async checkpoint(id, checkpoint) {
    const since = Date.now();
    const [x,y,z] = checkpoint.anchor;
    await this.teleport(id, this.source(x+0.5,y,z+0.5));
    await this.wait(()=>this.progressSince(id,checkpoint.stage,since), `${id} reaches source checkpoint ${checkpoint.stage}`, 3000);
    this.check(`Source checkpoint ${checkpoint.stage} at ${checkpoint.anchor} requires and records its lamp`, true, { id, source:checkpoint.anchor });
  }

  routeCandidates() {
    const positions = new Set();
    const each = (operation, visit) => {
      if (operation.op==='set') return visit(operation.pos, operation.pos);
      const low = operation.from.map((n,i)=>Math.min(n,operation.to[i]));
      const high = operation.from.map((n,i)=>Math.max(n,operation.to[i]));
      for(let x=low[0];x<=high[0];x++) for(let y=low[1];y<=high[1];y++) for(let z=low[2];z<=high[2];z++) {
        const source = [x,y,z];
        const target = operation.op==='clone' ? source.map((n,i)=>operation.destination[i]+n-low[i]) : source;
        visit(target,source);
      }
    };
    for (const branches of Object.values(this.rules.branches)) for (const branch of branches) {
      for (const operation of branch.operations) each(operation,target=>positions.add(key(target)));
    }
    const read = (name, selected) => {
      const geometry = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(this.root,`src/main/resources/pvp/party/geometry/${name}.json.gz`))));
      const result = new Map();
      for (const cell of geometry.blocks) {
        const position = cell.slice(0,3).map((n,i)=>n+geometry.bounds.min[i]);
        const encoded = key(position);
        if (!selected || selected.has(encoded)) result.set(encoded,strip(geometry.palette[cell[3]].Name));
      }
      return result;
    };
    const baseline = read('cave_parkour',positions), templates = read('cave_parkour_templates');
    for (const point of positions) if (!baseline.has(point)) baseline.set(point,'air');
    const candidates = [];
    const originalSigns = new Map(this.rules.signs.map(sign=>[key(sign.pos),sign]));
    const templateSigns = new Map(this.rules.templateSigns.map(sign=>[key(sign.pos),sign]));
    const follow = (node, cells, route, signs) => {
      for (const branch of this.rules.branches[node]) {
        const result = new Map(cells), currentSigns = new Map(signs);
        for (const operation of branch.operations) each(operation,(target,source)=> {
          const point=key(target);
          if (operation.op==='clone') {
            result.set(point,templates.get(key(source)) || 'air');
            if (templateSigns.has(key(source))) currentSigns.set(point,templateSigns.get(key(source)));
            else currentSigns.delete(point);
          } else if (!operation.replace || result.get(point)===strip(operation.replace)) {
            result.set(point,strip(operation.state)); currentSigns.delete(point);
          }
        });
        const selected = [...route,branch.triggerX];
        if (branch.nextPath) follow(branch.nextPath,result,selected,currentSigns);
        else candidates.push({cells:result,route:selected,signs:currentSigns});
      }
    };
    follow('path1',baseline,[],originalSigns);
    return {positions,candidates};
  }

  async verifyRoute() {
    const {positions,candidates} = this.routeCandidates();
    this.check('Original 11-node graph has all 126 complete random routes', candidates.length===126, candidates.length);
    for (const point of positions) {
      const source = xyz(point); await this.pinChunk(this.source(...source));
    }
    const prefix = `P2_PARKOUR_ROUTE_${++this.sequence}_`;
    for (let i=0;i<candidates.length;i++) {
      const checks = [...candidates[i].cells].map(([point,state])=> {
        const p=this.source(...xyz(point)); return `if block ${p.x} ${p.y} ${p.z} minecraft:${state}`;
      });
      this.rawConsole(`execute in pvp:arena ${checks.join(' ')} run say ${prefix}${i}`);
    }
    await this.console('say P2_PARKOUR_ROUTES_QUERIED');
    const matches = candidates.filter((_,i)=>this.serverLines.some(row=>row.text.includes(`${prefix}${i}`)
      && new RegExp(`${prefix}${i}(?:\\s|$)`).test(row.text)));
    this.check('All 246 runtime branch cells match a complete original route', matches.length>0,
      {matchingRoutes:matches.map(match=>match.route), checkedCells:positions.size});
    this.selectedRoute = matches[0];
  }

  async verifySigns() {
    let arrows = 0;
    for (const [point,sign] of this.selectedRoute.signs) {
      const position=this.source(...xyz(point)); await this.pinChunk(position);
      const color=`{front_text:{color:"${sign.frontColor}",has_glowing_text:${sign.frontGlowing?1:0}b},back_text:{color:"${sign.backColor}",has_glowing_text:${sign.backGlowing?1:0}b}}`;
      this.check(`Original sign color/glow restored at ${point}`,await this.condition(`in pvp:arena if data block ${position.x} ${position.y} ${position.z} ${color}`));
      for (const side of ['front','back']) {
        for (let line=0;line<4;line++) {
          if (!sign[side][line]) continue;
          const start=this.serverLines.length;
          await this.console(`execute in pvp:arena run data get block ${position.x} ${position.y} ${position.z} ${side}_text.messages[${line}]`);
          this.check(`Original direction arrow retained at ${point} ${side} line${line}`,this.serverLines.slice(start).some(row=>row.text.includes(sign[side][line])),sign[side][line]);
          arrows++;
        }
      }
    }
    this.check('Source and route-cloned wayfinding arrows are restored',arrows>=this.selectedRoute.signs.size && this.selectedRoute.signs.size>0,{arrows,signs:this.selectedRoute.signs.size});
  }

  async run() {
    const since=await this.start('parkour');
    const start=this.position('A'); this.offset=start.minus(new Vec3(204.5,-9,-170.5));
    this.check('All participants start on the original shared gold platform', IDS.every(id=>this.position(id).distanceTo(start)<0.01
      && this.bots[id].game.gameMode==='adventure'), IDS.map(id=>this.snapshot(id)));
    this.check('Source reset tools are equipped', IDS.every(id=>this.bots[id].inventory.items().some(item=>item.name==='carrot_on_a_stick')), IDS.map(id=>this.snapshot(id)));
    for (const id of IDS) this.check(`${id} receives source leather boots in the armor slot`,
      await this.condition(`if entity @a[name=${who(id)},nbt={Inventory:[{Slot:100b,id:"minecraft:leather_boots"}]}]`));
    await this.verifyRoute();
    await this.verifySigns();
    const cleanPoint=this.source(204,-10,-171); await this.pinChunk(cleanPoint);
    this.check('Original start gold block exists before teardown', await this.serverBlockIs(cleanPoint,'gold_block'), cleanPoint);
    this.check('Parkour invisibility and weakness are scoped to participants', await this.condition(`if entity @a[name=${who('A')},nbt={active_effects:[{id:"minecraft:invisibility"},{id:"minecraft:weakness"}]}]`));

    this.log('fixtureNote',{description:'Teleport fixtures isolate original checkpoint radius/underfoot checks; this does not claim a rendered full-course traversal.'});
    let at=Date.now();
    await this.teleport('A',this.source(201.99,-9,-138.5)); await sleep(250);
    this.check('Lamp underfoot outside radius1 does not advance',!this.progressSince('A',1,at));
    at=Date.now();
    await this.teleport('A',this.source(200.9,-9,-139)); await sleep(250);
    this.check('Inside radius1 without a lamp does not advance',!this.progressSince('A',1,at));
    await this.checkpoint('A',this.rules.checkpoints[0]);
    const saved=this.source(201.5,-9,-138.5);
    await this.teleport('A',start);
    const bot=this.bots.A;
    await bot.equip(bot.inventory.items().find(item=>item.name==='carrot_on_a_stick'),'hand');
    this.log('realResetStickActivate',{id:'A'}); bot.activateItem(); await sleep(100); bot.deactivateItem();
    await this.wait(()=>this.position('A').distanceTo(saved)<0.05,'real right-click reset returns to checkpoint');
    this.check('Real reset stick restores saved checkpoint',true,this.snapshot('A'));
    await this.teleport('A',start);
    this.log('realResetStickOffhand',{id:'A'});
    bot._client.write('block_dig',{status:6,location:{x:0,y:0,z:0},face:0,sequence:0}); await sleep(150);
    bot.activateItem(true); await sleep(100); bot.deactivateItem();
    await this.wait(()=>this.position('A').distanceTo(saved)<0.05,'real offhand right-click reset');
    this.check('Offhand reset returns tool to hotbar without duplicating it',await this.condition(`if entity @a[name=${who('A')},nbt={Inventory:[{Slot:0b,id:"minecraft:carrot_on_a_stick"}]}] unless entity @a[name=${who('A')},nbt={Inventory:[{Slot:-106b,id:"minecraft:carrot_on_a_stick"}]}]`));
    await this.teleport('A',start);
    this.log('realResetStickDrop',{id:'A'}); await bot.tossStack(bot.inventory.items().find(item=>item.name==='carrot_on_a_stick'));
    await this.wait(()=>this.position('A').distanceTo(saved)<0.05 && bot.inventory.items().some(item=>item.name==='carrot_on_a_stick'),
      'real drop returns to checkpoint and replaces consumed reset item');
    this.check('Real dropped reset tool returns only its thrower and is replaced',this.position('B').distanceTo(start)<0.05,this.snapshot('A'));
    await this.teleport('A',start);
    this.log('fixtureNote',{description:'Lethal native fall damage exercises personal retry, preserving prior checkpoint.'});
    await this.console(`damage ${who('A')} 1000 minecraft:fall`);
    await this.wait(()=>this.position('A').distanceTo(saved)<0.05,'lethal fall returns to saved checkpoint');
    this.check('Lethal fall retries rather than eliminating the player',this.bots.A.game.gameMode==='adventure' && this.bots.A.health===20,this.snapshot('A'));

    for (let stage=2;stage<=14;stage++) {
      const checkpoints=this.rules.checkpoints.filter(checkpoint=>checkpoint.stage===stage);
      for(let i=0;i<checkpoints.length;i++) await this.checkpoint(IDS[i],checkpoints[i]);
    }
    for(const id of ['B','C','D']) await this.checkpoint(id,this.rules.checkpoints.find(checkpoint=>checkpoint.stage===14));
    const finish=this.source(259.5,12,-273.5);
    await this.console(`tag ${who('A')} add p2_parkour_owned_tie`);
    await this.console(`tag ${who('B')} add p2_parkour_owned_tie`);
    this.log('fixtureNote',{description:'One batched teleport sends A/B to the original beacon together, testing same-tick tied finishes.'});
    await this.console(`execute in pvp:arena as @a[tag=p2_parkour_owned_tie] run tp @s ${finish.x} ${finish.y} ${finish.z}`);
    await this.wait(()=>['A','B'].every(id=>this.bots[id].game.gameMode==='spectator'),'A/B source beacon complete into spectator');
    this.check('Two same-tick finishers spectate while others continue',this.bots.C.game.gameMode==='adventure' && this.bots.D.game.gameMode==='adventure');
    await sleep(200); await this.teleport('C',finish);
    await this.wait(()=>this.chats.some(row=>row.id==='A' && row.at>=since && row.text.includes('第 1/5 轮排名')),'N-1 finishes settle round');
    const rows=this.chats.filter(row=>row.id==='A' && row.at>=since && /第\s+\d+\s+名\s+MyGoCheck/.test(row.text));
    for (const [id,place] of Object.entries({A:1,B:1,C:3,D:4})) {
      this.check(`Final source progress/recency converts ${id} to tournament rank${place}`, rows.some(row=>new RegExp(`第\\s+${place}\\s+名\\s+${who(id)}(?:\\s|$)`).test(row.text)),rows);
    }
    this.check('First round does not produce final five-round standings or feedback',!this.chats.some(row=>row.at>=since
      && (row.text.includes('Party 2 五轮总排名') || row.text.includes('qfmaker.com/blackboard/mcserverfb'))));
    await this.waitServerBlock(cleanPoint,'air',50000);
    this.check('Pinned original gold platform is cleared before next round',true,cleanPoint);
    for(const id of IDS) {
      this.check(`${id} loses parkour invisibility/weakness after the round`,await this.condition(`unless entity @a[name=${who(id)},nbt={active_effects:[{id:"minecraft:invisibility"}]}] unless entity @a[name=${who(id)},nbt={active_effects:[{id:"minecraft:weakness"}]}]`));
    }
    await this.leaveAll();
  }
}

(async()=> {
  const probe=new ParkourProbe(); let error;
  try { await probe.launch(); await probe.connectAll(); await probe.run(); }
  catch(failure) {error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error); if(!report.ok)process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
