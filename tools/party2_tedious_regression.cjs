'use strict';

// Reason: 411's eight tasks must remain actual native crafting, trades, pearls,
// bell statistics, note states and combat. Review: own Fabric25630 only; every
// console position/time/health fixture is labeled and never replaces the
// native interaction whose behavior is asserted. No rendered-client claim.
const path=require('node:path');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const IDS=['A','B','C','D'];
const name=id=>`MyGoCheck${id}`;
const pos=bot=>new Vec3(bot.probePosition.x,bot.probePosition.y,bot.probePosition.z);
const SPAWNS=[[34.5,-12,-340.5],[34.5,-12,-302.5],[53.5,-12,-321.5],[15.5,-12,-321.5]];
const BELLS=[[34,-9,-327],[34,-9,-317],[39,-9,-322],[29,-9,-322]];
const TABLES=[[34,-12,-324],[34,-12,-320],[36,-12,-322],[32,-12,-322]];
const TREES=[[32,-12,-334],[32,-12,-314],[42,-12,-324],[22,-12,-324]];
const NOTES=[[33,-12,-344],[33,-12,-300],[56,-12,-323],[12,-12,-323]];
const TRADE_CHAIN=['lapis_lazuli','copper_ingot','iron_ingot','gold_ingot','diamond','netherite_ingot','emerald','amethyst_shard','nether_star'];
GAMES.tedious={source:411,players:4};

class TediousProbe extends PartyProbe {
  constructor(options={}) {
    super({port:options.port||25630,runDir:options.runDir||path.join(__dirname,'../build/mygo-party2-tedious')});
    this.tasks={};this.completed={};this.resets={};this.history={};this.assignedAt={};this.healthPackets=[];this.projectiles=[];this.cooldowns=[];
    // This isolated first match owns region0, template size176, center(88,101,88).
    // Derive source positions from the audited anchor, never a random seat's current position.
    this.source=(x,y,z)=>new Vec3(x+54,y+113,z+410);
  }
  connect(id) {
    const bot=super.connect(id);
    this.tasks[id]=0;this.completed[id]=0;this.resets[id]=0;this.history[id]=[];
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');
      const task=text.match(/任务>\s+\[(\d+)\]/);
      if(task){this.tasks[id]=Number(task[1]);this.assignedAt[id]=Date.now();this.history[id].push(Number(task[1]));this.log('taskAssigned',{id,task:this.tasks[id],text});}
      if(/任务重置>/.test(text)){this.resets[id]++;this.log('taskReset',{id,text});}
      const completed=text.match(/已完成任务\s+(\d+)\/8/);
      if(completed){this.completed[id]=Number(completed[1]);this.log('taskCompleted',{id,count:this.completed[id]});}
    });
    bot._client.on('update_health',packet=>{this.healthPackets.push({id,at:Date.now(),health:packet.health});});
    bot._client.on('set_cooldown',packet=>{this.cooldowns.push({id,at:Date.now(),packet});this.log('nativeCooldown',{id,packet});});
    bot.on('entitySpawn',entity=>{if(entity.name==='ender_pearl'){this.projectiles.push({id,at:Date.now(),entityId:entity.id});this.log('nativePearl',{id,entityId:entity.id,position:entity.position});}});
    return bot;
  }
  async condition(condition) {
    const token=`P2_TT_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`,25);
    return this.serverLines.some(row=>row.text.includes(`[Server] ${token}`));
  }
  async sprint(ticks) {
    const since=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} actual server ticks; await Sprint completed.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(since).some(row=>/Sprint completed/i.test(row.text)),'tick sprint complete',60000);
    await sleep(50);
  }
  async equip(id,item) {
    const bot=this.bots[id];
    await this.wait(()=>bot.inventory.items().some(stack=>stack.name===item),`${id} has ${item}`,2000);
    await bot.equip(bot.inventory.items().find(stack=>stack.name===item),'hand');
  }
  async health(id,value) {
    return this.condition(`if entity @a[name=${name(id)},nbt={Health:${Number(value).toFixed(1)}f}]`);
  }
  async blockState(point,state) {
    const token=`P2_TT_BLOCK_${++this.sequence}`;
    await this.console(`execute in pvp:arena if block ${Math.floor(point.x)} ${Math.floor(point.y)} ${Math.floor(point.z)} minecraft:${state} run say ${token}`,25);
    return this.serverLines.some(row=>row.text.includes(`[Server] ${token}`));
  }
  async waitAdvance(id,task,completed) {
    await this.wait(()=>this.completed[id]>completed,`${id} task${task} completes`,8000);
    this.check(`${id} task${task} advances exactly once`,this.completed[id]===completed+1,{completed:this.completed[id],history:this.history[id]});
  }
  async clicks(id,point,count,delay=90) {
    const bot=this.bots[id];
    for(let i=0;i<count;i++) {
      bot._client.write('block_place',{hand:0,location:point,direction:4,cursorX:0,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence});
      if(delay)await sleep(delay);
    }
    if(!delay)await sleep(100);
  }
  async ring(id,full) {
    const seat=this.seats[id],bell=this.source(...BELLS[seat]);
    await this.pinChunk(bell);
    this.check('Original ceiling bell is created',await this.blockState(bell,'bell[attachment=ceiling]'));
    this.log('fixtureNote',{description:'Position the real player within native bell reach; all ring counts below use actual right-click packets.'});
    await this.teleport(id,bell.offset(-1,-3,.5));await this.equip(id,'stone_sword');
    if(full) {
      const resets=this.resets[id],completed=this.completed[id];
      await this.clicks(id,bell,31,45);
      await this.wait(()=>this.resets[id]>resets,'31 rings trigger exact delayed reset',4000);
      this.check('31 bell rings reset only the current task',this.tasks[id]===3&&this.completed[id]===completed,{task:this.tasks[id],completed:this.completed[id]});
      await this.teleport(id,bell.offset(-1,-3,.5));
    }
    const at=Date.now();await this.clicks(id,bell,30,full?50:0);
    this.check('Exactly30 rings do not complete before delayed native check',this.tasks[id]===3,{elapsedMs:Date.now()-at});
  }
  async tune(id,full) {
    const seat=this.seats[id],base=this.source(...NOTES[seat]);
    await this.pinChunk(base);
    this.log('fixtureNote',{description:full?'Position player near actual note blocks; 24 right clicks each wrap note1 to0.':'Controlled end-threshold fixture sets the already validated original note states to their source goal.'});
    for(let index=0;index<3;index++) {
      const note=base.offset(seat<2?index:0,1,seat<2?0:index);
      if(full) {
        this.check('Native note task starts at note1',await this.blockState(note,'note_block[note=1,instrument=harp,powered=false]'));
        await this.teleport(id,note.offset(-1,-1,.5));
        await this.clicks(id,note,index===2?23:24,45);
        if(index<2) this.check('24 actual clicks wrap note1 to native note0',await this.blockState(note,'note_block[note=0,instrument=harp,powered=false]'));
        else {
          this.check('The last note remains incomplete at native note24',this.tasks[id]===7&&await this.blockState(note,'note_block[note=24,instrument=harp,powered=false]'));
          await this.clicks(id,note,1,45);
        }
      } else await this.console(`execute in pvp:arena run setblock ${note.x} ${note.y} ${note.z} note_block[note=0,instrument=harp,powered=false]`);
    }
  }
  async visit(id) {
    const seat=this.seats[id];
    this.log('fixtureNote',{description:'Teleport through the four original pedestal distance zones to isolate visit-order and home-return rules, not a rendered parkour route.'});
    for(let other=0;other<4;other++)if(other!==seat){await this.teleport(id,this.source(...SPAWNS[other]));await sleep(70);}
    const completed=this.completed[id];
    this.check('Visiting all other pedestals still requires home return',this.tasks[id]===6);
    await this.teleport(id,this.source(...SPAWNS[seat]));
    await this.wait(()=>this.completed[id]>completed,'all four pedestals and return home',2000);
  }
  async killTask(id,full) {
    const victim=IDS.find(other=>other!==id&&this.bots[other]&&this.bots[other].game.gameMode==='adventure');
    if(!victim)throw new Error('No native combat target');
    const attacker=this.bots[id];
    const point=this.source(34.5,-12,-340.5);
    this.log('fixtureNote',{description:'Arrange a legal native sword hit on a two-health opponent at the original pedestal; console health is a combat fixture, the final attack is real.',id,victim});
    await this.sprint(65);
    await this.console(`effect clear ${name(victim)} resistance`);
    await this.console(`effect give ${name(victim)} instant_health 1 5 true`);
    await this.teleport(id,point);await this.teleport(victim,point.offset(1.5,0,0));
    if(full) {
      await this.console(`damage ${name(victim)} 18 minecraft:generic`);
      await this.equip(id,'stone_sword');await sleep(700);
      await this.wait(()=>!!attacker.players[name(victim)]?.entity,'attacker sees real player victim',2000);
      const target=attacker.players[name(victim)].entity;
      await attacker.lookAt(target.position.offset(0,1.1,0),true);
      this.log('realSwordHit',{id,victim,held:attacker.heldItem?.name});attacker.attack(target);
    } else {
      this.log('fixtureNote',{description:'After native combat has been independently validated, a source-credited lethal fixture advances another finisher.'});
      await this.console(`damage ${name(victim)} 100 minecraft:player_attack by ${name(id)}`);
    }
  }
  async pearl(id,full) {
    const bot=this.bots[id];
    if(!full) {
      this.log('fixtureNote',{description:'End-threshold fixture places a validated second racer on the original central gold detector.'});
      await this.teleport(id,this.source(34.5,7,-321.5));return;
    }
    const gold=this.source(34,6,-322);await this.pinChunk(gold);
    this.check('Original central gold target exists',await this.serverBlockIs(gold,'gold_block'));
    await this.sprint(65);await this.console(`effect clear ${name(id)} resistance`);
    await this.console(`effect give ${name(id)} instant_health 1 5 true`);
    this.log('fixtureNote',{description:'Arrange an airborne throw just outside the original central platform; actual pearl creation, collision, teleport and five damage remain native. This does not claim a full pedestal-to-center rendered throw.'});
    await this.teleport(id,this.source(34.5,7.5,-327.5));
    await this.equip(id,'ender_pearl');
    await bot.lookAt(this.source(34.5,7.3,-321.5),true);
    const at=Date.now();bot.activateItem();await sleep(80);bot.deactivateItem();
    this.check('Native pearl use sets original20tick cooldown',this.cooldowns.some(row=>row.id===id&&row.at>=at
      &&(row.packet.cooldownTicks===20||row.packet.ticks===20)),this.cooldowns.filter(row=>row.id===id&&row.at>=at));
    await this.wait(()=>this.projectiles.some(row=>row.at>=at),'native EnderPearlEntity spawned',2000);
    await this.wait(()=>this.completed[id]>this.pearlBefore,'native pearl reaches source gold',4500);
    this.check('Native pearl collision applies original five damage',this.healthPackets.some(row=>row.id===id&&row.at>=at&&row.health<=15&&row.health>0),this.healthPackets.filter(row=>row.id===id&&row.at>=at));
  }
  async heal(id,full) {
    const bot=this.bots[id];
    await this.wait(()=>this.healthPackets.some(row=>row.id===id&&row.at>=this.assignedAt[id]&&row.health<=8),'native instant damageII creates missing health',5000);
    this.check('Healing task first applies real instant damageII',bot.health<=19,this.snapshot(id));
    if(full) {
      const before=bot.health;
      await this.sprint(20);
      this.check('Food and Peaceful regeneration stay disabled while source RegenerationII remains native',bot.health<=before+1,{before,after:bot.health,ticks:20});
      await this.sprint(510);
      this.check('Native RegenerationII heals the task to source20 health',bot.health===20||this.tasks[id]!==8,this.snapshot(id));
    } else {
      this.log('fixtureNote',{description:'End-threshold fixture heals the already validated later racer after real instant damage.'});
      await this.console(`effect give ${name(id)} instant_health 1 5 true`);
    }
  }
  async craft(id,full) {
    if(!full) {
      this.log('fixtureNote',{description:'End-threshold inventory fixture gives a later racer the wooden axe, after A has natively harvested and crafted it.'});
      await this.console(`give ${name(id)} wooden_axe`);return;
    }
    const bot=this.bots[id],seat=this.seats[id],tree=this.source(...TREES[seat]);
    const trunk=tree.offset(2,0,2),table=this.source(...TABLES[seat]);
    await this.pinChunk(trunk);await this.pinChunk(table);
    this.check('Original tree clone has warped-stem trunk',await this.serverBlockIs(trunk,'warped_stem'));
    const completed=this.completed[id],resets=this.resets[id];
    this.log('fixtureNote',{description:'A lethal fall fixture verifies current-task reset, tree re-clone and retained completed tasks.'});
    await this.sprint(65);
    await this.console(`effect clear ${name(id)} resistance`);
    await this.console(`give ${name(id)} warped_planks 3`);
    await this.console(`damage ${name(id)} 100 minecraft:fall`);
    await this.wait(()=>this.resets[id]>resets,'craft task death retry',2500);
    this.check('Death preserves current task and prior completions',this.tasks[id]===4&&this.completed[id]===completed);
    this.check('Death removes current task crafting materials',!bot.inventory.items().some(item=>['warped_stem','warped_planks','stick'].includes(item.name)));
    this.check('Death restores the original native tree',await this.serverBlockIs(trunk,'warped_stem'));
    await this.equip(id,'stone_sword');
    for(let height=0;height<2;height++) {
      const blockPos=trunk.offset(0,height,0);
      await this.teleport(id,trunk.offset(0,-.0,-1));
      await this.wait(()=>this.block(id,blockPos)?.name==='warped_stem','client sees native warped trunk',2000);
      this.log('nativeHarvest',{id,position:blockPos,item:bot.heldItem?.name});
      await bot.dig(this.block(id,blockPos),true);
      await this.teleport(id,trunk.offset(.5,0,.5));await sleep(700);
    }
    await this.wait(()=>bot.inventory.items().filter(item=>item.name==='warped_stem').reduce((sum,item)=>sum+item.count,0)>=2,'two native harvested stem drops collected',3000);
    this.check('Adventure can_break sword yields actual source wood',bot.inventory.items().some(item=>item.name==='warped_stem'));
    await this.console(`recipe take ${name(id)} minecraft:warped_planks`);
    let recipe=bot.recipesFor(bot.registry.itemsByName.warped_planks.id,null,1,null)[0];
    if(!recipe)throw new Error('No native warped-planks recipe');
    for(let step=0;step<2;step++) {
      await bot.craft(recipe,1);
      await bot._syncWindow(bot.inventory);
      this.log('nativeInventoryCraftSync',{id,step,inventory:this.snapshot(id),cursor:bot.inventory.selectedItem});
      await this.console(`data get entity ${name(id)} Inventory`);
    }
    await this.wait(()=>bot.inventory.items().filter(item=>item.name==='warped_planks').reduce((sum,item)=>sum+item.count,0)>=8,'server acknowledges eight native planks',1500);
    this.check('Native inventory crafting makes eight warped planks despite undiscovered recipe',bot.inventory.items().filter(item=>item.name==='warped_planks').reduce((sum,item)=>sum+item.count,0)>=8,this.snapshot(id));
    await this.console(`recipe take ${name(id)} minecraft:stick`);
    recipe=bot.recipesFor(bot.registry.itemsByName.stick.id,null,1,null)[0];
    if(!recipe)throw new Error('No native sticks recipe');await bot.craft(recipe,1);await bot._syncWindow(bot.inventory);
    await this.teleport(id,table.offset(-1,0,.5));
    await this.wait(()=>this.block(id,table)?.name==='crafting_table','native table loaded',2000);
    await this.console(`recipe take ${name(id)} minecraft:wooden_axe`);
    recipe=bot.recipesFor(bot.registry.itemsByName.wooden_axe.id,null,1,this.block(id,table))[0];
    if(!recipe)throw new Error('No native wooden-axe crafting-table recipe');
    this.log('nativeCraft',{id,recipe:'wooden_axe',table});await bot.craft(recipe,1,this.block(id,table));
  }
  async trade(id,full) {
    if(!full) {
      this.log('fixtureNote',{description:'End-threshold inventory fixture gives a later racer the nether star after A has completed all eight native trades.'});
      await this.console(`give ${name(id)} nether_star`);return;
    }
    const bot=this.bots[id],seat=this.seats[id],station=this.source(...TABLES[seat]),completed=this.completed[id];
    await this.teleport(id,station.offset(-1,0,.5));
    await this.wait(()=>Object.values(bot.entities).some(entity=>entity.name==='villager'&&entity.position.distanceTo(station.offset(.5,0,.5))<2),'source native villager tracked',2500);
    const villager=Object.values(bot.entities).find(entity=>entity.name==='villager'&&entity.position.distanceTo(station.offset(.5,0,.5))<2);
    const menu=await bot.openVillager(villager);
    this.check('Original native merchant GUI exposes eight unordered source offers',menu.trades.length===8,menu.trades.map(offer=>[offer.inputItem1.name,offer.outputItem.name,offer.maximumNbTradeUses]));
    for(let index=0;index<8;index++) {
      const offer=menu.trades.findIndex(trade=>trade.inputItem1.name===TRADE_CHAIN[index]&&trade.outputItem.name===TRADE_CHAIN[index+1]);
      this.check(`Native trade${index+1} uses source chain`,offer>=0&&menu.trades[offer].maximumNbTradeUses===999,{offer});
      this.log('nativeTrade',{id,offer,from:TRADE_CHAIN[index],to:TRADE_CHAIN[index+1]});
      try{await menu.trade(offer,1);}catch(error){
        if(index!==7)throw error;
        await this.wait(()=>this.completed[id]>completed,'native star ends merchant task before client menu settles',1500);
        this.log('nativeTradeCompletionRace',{description:'Server closed the actual native merchant after star reached inventory.',error:error.message});
      }
    }
    if(bot.currentWindow)bot.closeWindow(bot.currentWindow);
  }
  async completeAll(id,full) {
    const started=Date.now(),seen=new Set(this.history[id].slice(0,this.completed[id]));
    while(this.completed[id]<8) {
      await this.wait(()=>this.tasks[id]>=1&&this.tasks[id]<=8,`${id} task assigned`);
      const task=this.tasks[id],completed=this.completed[id];
      this.check(`${id} task selection does not repeat completed task`,!seen.has(task),{task,seen:[...seen]});
      if(task===1)await this.killTask(id,full);
      else if(task===2){this.pearlBefore=completed;await this.pearl(id,full);}
      else if(task===3)await this.ring(id,full);
      else if(task===4)await this.craft(id,full);
      else if(task===5)await this.trade(id,full);
      else if(task===6)await this.visit(id);
      else if(task===7)await this.tune(id,full);
      else if(task===8)await this.heal(id,full);
      await this.waitAdvance(id,task,completed);seen.add(task);
      this.check(`${id} completed history includes no duplicate task`,new Set(this.history[id]).size===this.history[id].length,{history:this.history[id]});
    }
    this.check(`${id} completes all eight distinct original tasks`,seen.size===8,{seen:[...seen],elapsedMs:Date.now()-started});
  }
  async outsideCraft() {
    const id='E',bot=this.bots.E;
    await this.console(`gamemode survival ${name(id)}`);
    await this.console('execute in minecraft:overworld run gamerule doLimitedCrafting true');
    await this.console(`clear ${name(id)}`);await this.console(`give ${name(id)} warped_stem`);
    await this.console(`recipe take ${name(id)} *`);
    await sleep(100);
    const item=bot.inventory.items().find(item=>item.name==='warped_stem');
    await bot.clickWindow(item.slot,0,0);await bot.clickWindow(1,0,0);await sleep(300);
    this.check('Outside player cannot craft an undiscovered recipe under limited crafting',!bot.inventory.slots[0],{output:bot.inventory.slots[0]?.name});
    await this.console(`recipe give ${name(id)} minecraft:warped_planks`);
    await bot.clickWindow(1,0,0);await bot.clickWindow(1,0,0);await sleep(150);
    this.check('Outside control can craft the same recipe after native discovery',bot.inventory.slots[0]?.name==='warped_planks',{output:bot.inventory.slots[0]?.name});
    await bot.clickWindow(1,0,0);await bot.clickWindow(9,0,0);
  }
  async run() {
    const since=await this.start('tedious');
    this.seats={};
    for(const id of IDS) {
      this.seats[id]=SPAWNS.findIndex(point=>pos(this.bots[id]).distanceTo(this.source(...point))<.3);
      this.check(`${id} owns one of four original pedestals`,this.seats[id]>=0,this.snapshot(id));
    }
    const outer=this.source(60,38,-348),gold=this.source(34,6,-322);await this.pinChunk(outer);await this.pinChunk(gold);
    this.check('Complete native four-pedestal source field is staged',await this.serverBlockIs(outer,'bedrock')||await this.serverBlockIs(outer,'nether_bricks'));
    this.check('Original central3x3 gold remains',await this.serverBlockIs(gold,'gold_block'));
    await this.console('scoreboard objectives add p2_tt dummy');
    await this.console('scoreboard objectives add p2_tt_bell minecraft.custom:minecraft.bell_ring');
    await this.console('execute in pvp:arena run gamerule doLimitedCrafting true');
    await this.console('execute in pvp:arena run gamerule naturalRegeneration true');
    this.connect('E');await this.wait(()=>this.bots.E.probeSpawned,'outside control E connected',20000);
    await this.outsideCraft();
    await this.completeAll('A',true);
    this.check('One finisher does not end a four-player source round',!this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)));
    await this.completeAll('B',false);
    await this.completeAll('C',false);
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'N-1 source threshold ends round',4000);
    this.check('Three finishers trigger original N-1 threshold',this.completed.A===8&&this.completed.B===8&&this.completed.C===8&&this.completed.D<8);
    this.check('Eight-task ranking gives earlier finisher highest placement',this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\s+名\s+MyGoCheckA.*本轮\s+\+4/.test(row.text)));
    await this.console('execute in pvp:arena store result score $limited p2_tt run gamerule doLimitedCrafting');
    this.check('Task module never changes global limited-crafting rule',await this.condition('if score $limited p2_tt matches 1'));
    this.check('All native task villagers are removed on close',!await this.condition('in pvp:arena if entity @e[type=minecraft:villager,tag=party_tedious_villager]'));
    for(const id of IDS)this.check(`${id} task effects are restored`,!await this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:regeneration"}]}]`)
      &&!await this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:weakness"}]}]`));
    await this.wait(()=>IDS.every(id=>!!this.bots[id].currentWindow),'next five-round selection',30000);
    this.check('Next round resumes and excludes source411',IDS.every(id=>!this.menuChoices(this.bots[id].currentWindow).some(choice=>choice.source===411)));
    this.check('Pinned source target is really cleared',await this.waitServerBlock(gold,'air'));
    await this.leaveAll();await this.unpinChunks();
  }
}
async function main() {
  const probe=new TediousProbe();let error;
  try{await probe.launch();await probe.connectAll('ABCD');await probe.run();}
  catch(failure){error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={TediousProbe};
