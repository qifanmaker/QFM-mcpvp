'use strict';

// Reason: 420 scores native Adventure mining, not item sales or direct crop-age
// increments. Review: this owns only port25654/build/mygo-party2-farming-frenzy.
// Maturity and player-position fixtures are labeled; harvests use real packets.
// This is headless protocol evidence, not a rendered-client acceptance claim.
const path = require('node:path');
const fs = require('node:fs');
const zlib = require('node:zlib');
const {Vec3} = require('vec3');
const {PartyProbe} = require('./party2_protocol_regression.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const name = id => `MyGoCheck${id}`;
const CROPS = [
  ['wheat', 'wheat', 7, 2, [-160,36,-269]],
  ['melon', 'melon', null, 6, [-160,36,-272]],
  ['beetroots', 'beetroot', 3, 1, [-183,38,-275]],
  ['potatoes', 'potato', 7, 2, [-172,37,-274]],
  ['pumpkin', 'pumpkin', null, 6, [-170,37,-279]],
  ['carrots', 'carrot', 7, 2, [-166,37,-270]],
  ['nether_wart', 'nether_wart', 3, 1, [-150,36,-270]],
  ['cocoa', 'cocoa_beans', 2, 4, [-157,36,-258]]
];
const SEASONS = ['小麦','西瓜','甜菜','马铃薯','南瓜','胡萝卜','下界疣','可可豆'];

class FarmingFrenzyProbe extends PartyProbe {
  constructor() {
    super({port:25654,runDir:path.join(__dirname,'../build/mygo-party2-farming-frenzy')});
    this.seasons=[]; this.season=0; this.expected=0;
  }
  connect(id) {
    const bot=super.connect(id);
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');
      if(id==='A') {
        const season=text.match(/^(.+)季节！接下来/);
        if(season) {this.season=SEASONS.indexOf(season[1])+1;this.seasons.push({crop:this.season,at:Date.now()});}
      }
    });
    return bot;
  }
  async condition(clause) {
    const token=`P2_FARM_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`,10);
    return this.serverLines.some(row=>row.text.includes(`] ${token}`));
  }
  async sprint(ticks) {
    const before=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} actual native server ticks.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(before).some(row=>/Sprint completed/i.test(row.text)),'native tick sprint',60000);
    await sleep(30);
  }
  async set(position,state) {
    this.log('fixtureNote',{description:'A source-position block-state fixture isolates native tool acceptance; no mined statistics or points are assigned.',position,state});
    await this.console(`execute in pvp:arena run setblock ${position.x} ${position.y} ${position.z} minecraft:${state}`);
    await this.wait(()=>!!this.block('A',position),'fixture chunk available',2500);
  }
  async startFarm(ids='AB') {
    this.game='farming';this.voteError=null;this.seasons=[];this.season=0;this.expected=0;
    for(const id of ids)await this.console(`tag ${name(id)} add p2_farm_hub`);
    await this.console('execute as @a[tag=p2_farm_hub] run hub');await this.console('tag @a remove p2_farm_hub');
    await this.wait(()=>[...ids].every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'clean hub baseline',15000);
    this.hubInventories=Object.fromEntries([...ids].map(id=>[id,this.bots[id].inventory.items().map(item=>[item.name,item.count]).sort()]));
    const since=Date.now();
    for(const id of ids) {this.bots[id].probeStartedAt=0;await this.console(`tag ${name(id)} add p2_farm_join`);}
    await this.console('execute as @a[tag=p2_farm_join] run pvp join minecraftparty');
    await this.console('tag @a remove p2_farm_join');
    if(ids.length<4)await this.chat(ids[0],'/pvp start');
    await this.wait(()=>{
      if(this.voteError)throw this.voteError;
      return this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮选择：.*原版编号\s+420/.test(row.text));
    },'420 selected through actual votes',90000);
    const selected=Date.now();for(const id of ids)this.bots[id].probeStartedAt=0;
    const slow=packet=>{if(JSON.stringify(packet).includes('开始'))this.rawConsole('tick rate 5');};
    this.bots.A._client.on('set_title_text',slow);
    await this.wait(()=>[...ids].every(id=>this.bots[id].probeStartedAt>=selected),'420 starts native PLAY',45000);
    this.bots.A._client.removeListener('set_title_text',slow);this.game=null;
    await this.wait(()=>[...ids].every(id=>this.bots[id].inventory.items().some(item=>item.name==='iron_hoe')),'native loyal hoes',2500);
    const start=this.bots.A.probePosition;
    this.source=(x,y,z)=>new Vec3(x+start.x+152.5,y+start.y-36,z+start.z+275.5);
    this.check('All players share the exact original farm start',[...ids].every(id=>this.bots[id].probePosition.distanceTo(start)<.01),[...ids].map(id=>this.snapshot(id)));
    for(let x=-191;x<=-142;x+=8)for(let z=-290;z<=-251;z+=8)await this.pinChunk(this.source(x,36,z));
    return since;
  }
  async mine(position,ticks=2) {
    await this.teleport('A',position.offset(.5,1,.5));
    const bot=this.bots.A,hoe=bot.inventory.items().find(item=>item.name==='iron_hoe');
    await bot.equip(hoe,'hand');
    await this.wait(()=>!!this.block('A',position),'native harvest block loaded',3000);
    const block=this.block('A',position);
    this.log('nativeMine',{position,block:block.name,season:this.season});
    const digging=bot.dig(block,true);
    await sleep(25);await this.sprint(ticks);await digging;
    await this.sprint(2);
  }
  async run() {
    const since=await this.startFarm();
    const farm=JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(this.root,'src/main/resources/pvp/party/geometry/farming_frenzy.json.gz'))));
    this.check('Source geometry includes all 9606 blocks and the complete ridge boundary',farm.blocks.length===9606&&farm.size.join(',')==='50,43,40');
    this.check('The source high ridge barrier exists above the visible terrain',await this.serverBlockIs(this.source(-190,69,-255),'barrier'));
    this.check('The original barn ladder remains climbable',await this.serverBlockIs(this.source(-153,37,-257),'ladder'));
    this.check('The native iron hoe carries adventure maturity predicates',await this.condition(`if data entity ${name('A')} Inventory[{id:"minecraft:iron_hoe"}].components."minecraft:can_break"`));
    for(let crop=0;crop<CROPS.length;crop++) {
      await this.console(`scoreboard objectives add p2farm${crop} minecraft.mined:minecraft.${CROPS[crop][0]}`);
      await this.console(`scoreboard players set ${name('A')} p2farm${crop} 0`);
    }
    const immature=this.source(-160,36,-269);
    await this.teleport('A',immature.offset(.5,1,.5));
    await this.set(immature,'wheat[age=6]');
    this.bots.A.setQuickBarSlot(0);
    this.bots.A._client.write('block_dig',{status:0,location:immature,face:1,sequence:++this.sequence});
    this.bots.A._client.write('block_dig',{status:2,location:immature,face:1,sequence:++this.sequence});
    await this.sprint(1);
    this.check('The native hoe rejects immature wheat without earning MINED credit',await this.condition(`if score ${name('A')} p2farm0 matches 0`));

    for(let crop=0;crop<CROPS.length;crop++) {
      const [block,gift,age,points,source]=CROPS[crop],position=this.source(...source);
      await this.teleport('A',position.offset(.5,1,.5));
      await this.set(position,block+(age===null?'':`[age=${age}${block==='cocoa'?',facing=west':''}]`));
      await this.mine(position,age===null?20:2);
      const received=this.bots.A.inventory.items().filter(item=>item.name===gift).reduce((total,item)=>total+item.count,0);
      this.expected+=points*received;
      this.check(`${block}: real native MINED statistic advances once`,await this.condition(`if score ${name('A')} p2farm${crop} matches 1`));
      this.check(`${block}: source manual inventory reward is the actual crop item`,received===1||received===2,{received});
    }
    if(this.season===0)await this.sprint(200);
    const doubledCrop=this.season-1,[seasonBlock,seasonGift,seasonAge,seasonPoints,seasonSource]=CROPS[doubledCrop];
    const beforeGift=this.bots.A.inventory.items().filter(item=>item.name===seasonGift).reduce((total,item)=>total+item.count,0);
    const seasonal=this.source(...seasonSource);
    await this.teleport('A',seasonal.offset(.5,1,.5));
    await this.set(seasonal,seasonBlock+(seasonAge===null?'':`[age=${seasonAge}${seasonBlock==='cocoa'?',facing=west':''}]`));
    await this.mine(seasonal,seasonAge===null?20:2);
    const doubledGift=this.bots.A.inventory.items().filter(item=>item.name===seasonGift).reduce((total,item)=>total+item.count,0)-beforeGift;
    this.check('The actually selected native season doubles the next harvest inventory yield',doubledGift===2,{crop:seasonBlock,doubledGift,season:this.season});
    this.expected+=seasonPoints*doubledGift;
    this.check('The 59 source replant operations restore harvested wheat',await this.serverBlockIs(this.source(-160,36,-269),'wheat'));
    this.check('Manual harvest does not emit native seed drop entities',!await this.condition('in pvp:arena if entity @e[type=item,nbt={Item:{id:"minecraft:wheat_seeds"}}]'));

    const floor=this.source(-160,35,-269);
    await this.teleport('A',floor.offset(.5,1,.5));await this.set(floor,'dirt');await this.sprint(1);
    this.check('The original last all-tick repair restores dirt beneath a farmer',await this.serverBlockIs(floor,'farmland'));
    this.check('Trampled floor repair earns no extra mined wheat',await this.condition(`if score ${name('A')} p2farm0 matches ${doubledCrop===0?2:1}`));
    const gate=this.source(-152,36,-269);await this.teleport('A',gate.offset(-1,0,.5));await this.click('A',gate);await this.sprint(1);
    this.check('The source barn fence gate accepts native right-click interaction',await this.serverBlockIs(gate,'oak_fence_gate[open=true]'));
    const stray=this.source(-188,65,-280);
    this.log('fixtureNote',{description:'Twenty age-zero nether warts on native soul sand give a broad sampling witness without changing growth ages or random-tick speed.'});
    await this.console(`execute in pvp:arena run fill ${stray.x} ${stray.y-1} ${stray.z} ${stray.x+9} ${stray.y-1} ${stray.z+1} minecraft:soul_sand`);
    await this.console(`execute in pvp:arena run fill ${stray.x} ${stray.y} ${stray.z} ${stray.x+9} ${stray.y} ${stray.z+1} minecraft:nether_wart[age=0]`);
    await this.teleport('A',stray.offset(4.5,1,.5));
    await this.sprint(140);
    let grown;
    for(let x=0;x<10;x++)for(let z=0;z<2;z++) {
      const p=stray.offset(x,0,z),block=this.block('A',p);
      if(block?.name==='nether_wart'&&block.getProperties().age>0)grown=p;
    }
    this.check('PLAY uses native random crop growth after age-zero setup',!!grown&&await this.serverBlockIs(grown,'nether_wart')&&!await this.serverBlockIs(grown,'nether_wart[age=0]'));
    await this.console('tick rate 20');
    for(let batch=0;batch<9&&!this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text));batch++)await this.sprint(200);
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'1799-tick source harvest contest finishes',10000);
    this.check('Exactly eight independent seasons occur',this.seasons.length===8,this.seasons);
    this.check('Native crop material points reach the tournament ranking',this.chats.some(row=>row.id==='A'&&row.at>=since&&row.text.includes(`${name('A')} ${this.expected} 采收积分`)),{expected:this.expected,rows:this.chats.filter(row=>row.id==='A'&&row.at>=since&&row.text.includes('本轮'))});
    this.check('Harvested player beats the idle player without elimination',this.chats.some(row=>row.id==='A'&&row.at>=since&&new RegExp(`第 1 名 ${name('A')}`).test(row.text)));
    await this.wait(()=>!!this.bots.A.currentWindow,'next outer-round vote after cleanup',30000);
    this.check('The five-round catalog removes played Farming Frenzy',!this.menuChoices(this.bots.A.currentWindow).some(choice=>choice.source===420));
    this.check('Native growing cells outside the static non-air map clear with the round',await this.serverBlockIs(stray,'air'));
    this.check('High original barrier cells clear with the farm',await this.serverBlockIs(this.source(-190,69,-255),'air'));
    this.check('The loyal hoe is removed at round completion',!this.bots.A.inventory.items().some(item=>item.name==='iron_hoe'));
    await this.leaveAll();await this.unpinChunks();
    this.check('All clients return to the hub',Object.values(this.bots).every(bot=>bot.probeWorld==='minecraft:overworld'));
    this.check('Each client receives its original hub inventory',Object.keys(this.hubInventories).every(id=>JSON.stringify(this.bots[id].inventory.items().map(item=>[item.name,item.count]).sort())===JSON.stringify(this.hubInventories[id])));
  }
}

async function main() {
  const p=new FarmingFrenzyProbe();let error;
  try {await p.launch();await p.connectAll('AB');await p.run();}
  catch(failure) {error=failure;p.log('failure',{error:failure.stack});for(const id of Object.keys(p.bots))if(p.bots[id].probeSpawned)p.log('failureClient',p.snapshot(id));}
  const report=await p.close(error);if(!report.ok)process.exitCode=1;
}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={FarmingFrenzyProbe};
