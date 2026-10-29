'use strict';

// Why: scheduler suppression must apply to one active Party scene while an
// identically positioned Overworld chunk continues native random ticking.
// Review: only disposable port25662/mygo snapshot fixtures are mutated. No
// age writes, manual randomTick calls, production instrumentation or Gradle.
const path=require('node:path');
const {spawn}=require('node:child_process');
const readline=require('node:readline');
const {Vec3}=require('vec3');
const {PartyProbe}=require('./party2_protocol_regression.cjs');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));

class RandomScopeProbe extends PartyProbe {
  constructor() {
    super({port:25662,runDir:path.join(__dirname,'../build/mygo-party2-random-scope')});
    this.party={world:'pvp:arena',min:new Vec3(96,240,96),max:new Vec3(111,255,111)};
    this.control={world:'minecraft:overworld',min:new Vec3(0,240,0),max:new Vec3(15,255,15)};
  }
  async launch() {
    this.log('environment',{root:this.root,runDir:this.runDir,port:this.port,minecraft:'1.21.1',headless:true,rendered:false,
      description:'The existing test-only block-loot snapshot entrypoint supplies direct per-world rule setters and authoritative block counts.'});
    this.server=spawn('python3',[path.join(this.root,'tools/party2_block_loot_server.py'),'--port',String(this.port),'--run-dir',this.runDir],{cwd:this.root,stdio:['pipe','pipe','pipe']});
    for(const [stream,kind]of [[this.server.stdout,'stdout'],[this.server.stderr,'stderr']])readline.createInterface({input:stream}).on('line',line=>{
      const row={at:Date.now(),stream:kind,text:line};this.serverLines.push(row);this.log('server',row);
    });
    this.server.once('exit',(code,signal)=>{this.serverExit={code,signal};this.log('serverExit',this.serverExit);});
    await this.wait(()=>this.serverLines.some(row=>/Done \([\d.]+s\)!/.test(row.text)),'instrumented Fabric server ready',120000);
  }
  async condition(clause) {
    const token=`P2_SCOPE_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`,10);
    return this.serverLines.some(row=>row.text.includes(`] ${token}`));
  }
  async sprint(ticks) {
    const before=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} actual native server ticks; do not invoke PartyRandomTicks manually.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(before).some(row=>/Sprint completed/i.test(row.text)),'native tick sprint completes',60000);
    await sleep(40);
  }
  async startSpeed() {
    for(const id of ['A','B'])await this.console(`tag MyGoCheck${id} add p2_scope_hub`);
    await this.console('execute as @a[tag=p2_scope_hub] run hub');await this.console('tag @a remove p2_scope_hub');
    await this.wait(()=>['A','B'].every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'two clean hub entrants',15000);
    this.game='speed';this.voteError=null;const since=Date.now();
    for(const id of ['A','B']){this.bots[id].probeStartedAt=0;await this.console(`tag MyGoCheck${id} add p2_scope_join`);}
    await this.console('execute as @a[tag=p2_scope_join] run pvp join minecraftparty');await this.console('tag @a remove p2_scope_join');await this.chat('A','/pvp start');
    await this.wait(()=>{
      if(this.voteError)throw this.voteError;
      return this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮选择：.*原版编号\s+417/.test(row.text));
    },'417 selected through native two-player votes',90000);
    const selected=Date.now();for(const id of ['A','B'])this.bots[id].probeStartedAt=0;
    await this.wait(()=>['A','B'].every(id=>this.bots[id].probeStartedAt>=selected),'417 enters actual PLAY',45000);
    this.game=null;await this.console('tick rate 5');
    this.check('The active Party round is the old Speed Builders module with no manual growth helper',
      ['A','B'].every(id=>this.bots[id].probeWorld==='pvp:arena')&&this.chats.some(row=>row.id==='A'&&row.at>=since&&row.text.includes('原版编号 417')));
    this.check('The fixed Party fixture lies in the first match region',this.bots.A.probePosition.x>=0&&this.bots.A.probePosition.x<176,this.snapshot('A'));
  }
  async rate(fixture,speed) {
    await this.console(`execute in ${fixture.world} run p2randomrate ${speed}`);
    const key=fixture===this.party?'arenaRate':'overworldRate';
    await this.console(`execute in ${fixture.world} store result score ${key} p2_random_scope run p2randomrate`);
    this.check(`${fixture.world} actual world rule remains ${speed}`,await this.condition(`if score ${key} p2_random_scope matches ${speed}`));
  }
  async seed(fixture) {
    const {world,min,max}=fixture;
    this.log('fixtureNote',{description:'Fill one native 16-cubed grass section and place an opaque stone cap. Every grass cell has an opaque block above it, so its own randomTick converts it to dirt.',world,min,max,cells:4096});
    await this.console(`execute in ${world} run fill ${min.x} ${min.y} ${min.z} ${max.x} ${max.y} ${max.z} minecraft:grass_block`);
    await this.console(`execute in ${world} run fill ${min.x} ${max.y+1} ${min.z} ${max.x} ${max.y+1} ${max.z} minecraft:stone`);
  }
  async count(fixture,key) {
    const {world,min,max}=fixture,before=this.serverLines.length;
    await this.console(`execute in ${world} store result score ${key} p2_random_scope run p2randomcount ${min.x} ${min.y} ${min.z} ${max.x} ${max.y} ${max.z}`);
    const rows=this.serverLines.slice(before).filter(row=>/P2_NATIVE_DIRT_COUNT_\d+/.test(row.text));
    const count=Number(rows.at(-1)?.text.match(/P2_NATIVE_DIRT_COUNT_(\d+)/)?.[1]);
    if(!Number.isInteger(count))throw Error('The authoritative native count command did not return a count');
    this.log('nativeCount',{world,key,count});return count;
  }
  async run() {
    await this.launch();await this.connectAll('AB');await this.startSpeed();
    this.connect('C');await this.wait(()=>this.bots.C.probeSpawned,'independent Overworld player connects',30000);
    await this.console('execute as MyGoCheckC run hub');
    await this.console('gamemode survival MyGoCheckC');
    await this.console('execute in minecraft:overworld run tp MyGoCheckC 8.5 258.0 8.5');
    await this.wait(()=>this.bots.C.probeWorld==='minecraft:overworld','control client stays in Overworld',10000);
    this.check('The control client is a native non-spectator outside the Party queue',await this.condition('if entity @a[name=MyGoCheckC,gamemode=!spectator,nbt={Dimension:"minecraft:overworld"}]'));
    await this.console('scoreboard objectives add p2_random_scope dummy');
    await this.pinChunk(this.party.min);
    await this.console('execute in minecraft:overworld run forceload add 0 0');
    await this.rate(this.party,0);await this.rate(this.control,0);
    await this.seed(this.party);await this.seed(this.control);
    await this.console('execute in minecraft:overworld run tp MyGoCheckC 8.5 257.0 8.5');
    this.check('Party fixture starts with zero native dirt cells',await this.count(this.party,'partyBefore')===0);
    this.check('Overworld fixture starts with zero native dirt cells',await this.count(this.control,'overworldBefore')===0);
    await this.rate(this.party,2000);await this.rate(this.control,2000);
    await this.sprint(40);
    const party=await this.count(this.party,'partyAfter'),overworld=await this.count(this.control,'overworldAfter');
    this.check('Active Party scheduler leaves all 4096 grass cells unchanged',party===0&&await this.condition('if score partyAfter p2_random_scope matches 0'),{partyDirt:party});
    this.check('The independent Overworld scheduler natively decays opaque-covered grass',overworld>0&&await this.condition('if score overworldAfter p2_random_scope matches 1..4096'),{overworldDirt:overworld});
    await this.console('execute in pvp:arena store result score arenaFinal p2_random_scope run p2randomrate');
    await this.console('execute in minecraft:overworld store result score overworldFinal p2_random_scope run p2randomrate');
    this.check('Neither live world rule was rewritten by the scoped scheduler',await this.condition('if score arenaFinal p2_random_scope matches 2000 if score overworldFinal p2_random_scope matches 2000'));
    this.check('Only A and B remain Party participants while C stays in the independent world',
      await this.condition('if entity @a[name=MyGoCheckA,nbt={Dimension:"pvp:arena"}] if entity @a[name=MyGoCheckB,nbt={Dimension:"pvp:arena"}] if entity @a[name=MyGoCheckC,gamemode=!spectator,nbt={Dimension:"minecraft:overworld"}]'));
    await this.rate(this.party,0);await this.rate(this.control,0);
    await this.console('tick rate 20');await this.leaveAll();await sleep(5500);
    await this.console('execute in pvp:arena run fill 96 240 96 111 256 111 minecraft:air');
    await this.console('execute in minecraft:overworld run fill 0 240 0 15 256 15 minecraft:air');
    await this.console('execute in minecraft:overworld run forceload remove 0 0');
    await this.unpinChunks();
    this.check('Both participants and the independent control client return to the hub',Object.values(this.bots).every(bot=>bot.probeWorld==='minecraft:overworld'));
  }
}

async function main() {
  const probe=new RandomScopeProbe();let error;
  try {await probe.run();}
  catch(failure){error=failure;probe.log('failure',{error:failure.stack});for(const id of Object.keys(probe.bots))if(probe.bots[id].probeSpawned)probe.log('failureClient',probe.snapshot(id));}
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={RandomScopeProbe};
