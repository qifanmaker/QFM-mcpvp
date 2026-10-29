'use strict';

// Reason: the two-player game starts its grace period immediately, still needs
// all four physical pedestals, and ends on either the4800tick limit or one
// finisher. Review: a separate owned runtime on25630, after the four-player
// native-task test stops. Console fixtures isolate boundaries explicitly.
const path=require('node:path');
const {TediousProbe}=require('./party2_tedious_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const name=id=>`MyGoCheck${id}`;
const pos=bot=>new Vec3(bot.probePosition.x,bot.probePosition.y,bot.probePosition.z);
const SPAWNS=[[34.5,-12,-340.5],[34.5,-12,-302.5],[53.5,-12,-321.5],[15.5,-12,-321.5]];

class BoundaryProbe extends TediousProbe {
  constructor(){super({port:25630,runDir:path.join(__dirname,'../build/mygo-party2-tedious-boundary')});this.velocities=[];}
  connect(id){const bot=super.connect(id);bot._client.on('entity_velocity',packet=>{if(packet.entityId===bot.entity?.id)this.velocities.push({id,at:Date.now(),packet});});return bot;}
  async startTwo(){
    for(const id of ['A','B']){this.tasks[id]=0;this.completed[id]=0;this.resets[id]=0;this.history[id]=[];}
    const since=await this.start('tedious');
    const mean=pos(this.bots.A).plus(pos(this.bots.B)).scaled(.5);
    const offset=mean.minus(new Vec3(34.5,-12,-321.5));this.source=(x,y,z)=>offset.offset(x,y,z);
    this.seats={};for(const id of ['A','B'])this.seats[id]=SPAWNS.findIndex(point=>pos(this.bots[id]).distanceTo(this.source(...point))<.2);
    this.check('Two-player411 is present and starts at the original first two pedestals',this.seats.A>=0&&this.seats.A<2&&this.seats.B>=0&&this.seats.B<2,{seats:this.seats});
    return since;
  }
  async visit(id){
    const seat=this.seats[id],other=seat===0?1:0,completed=this.completed[id];
    this.log('fixtureNote',{description:'Two-player visit negative control: visit only the occupied opponent, return home, then separately visit both empty pedestals.'});
    await this.teleport(id,this.source(...SPAWNS[other]));await sleep(70);
    await this.teleport(id,this.source(...SPAWNS[seat]));await sleep(100);
    this.check('Occupied pedestals alone do not finish the two-player visit task',this.tasks[id]===6&&this.completed[id]===completed);
    for(const empty of [2,3]){await this.teleport(id,this.source(...SPAWNS[empty]));await sleep(70);}
    this.check('Both empty pedestal visits still require the final home return',this.tasks[id]===6&&this.completed[id]===completed);
    await this.teleport(id,this.source(...SPAWNS[seat]));await this.wait(()=>this.completed[id]>completed,'all four including empty pedestals',2000);
  }
  async run(){
    let since=await this.startTwo();
    await sleep(150);
    let nonkiller=['A','B'].find(id=>this.tasks[id]!==1);
    if(!nonkiller){const before=this.completed.A;await this.killTask('A',false);await this.waitAdvance('A',1,before);nonkiller='A';}
    const victim=nonkiller==='A'?'B':'A';
    this.check('Two players immediately receive source grace weakness on non-kill tasks',await this.condition(`if entity @a[name=${name(nonkiller)},nbt={active_effects:[{id:"minecraft:weakness"}]}]`),{nonkiller,task:this.tasks[nonkiller]});
    await this.sprint(65);await this.console(`effect clear ${name(victim)} resistance`);await this.console(`effect give ${name(victim)} instant_health 1 5 true`);
    const point=this.source(34.5,-12,-340.5);await this.teleport(nonkiller,point);await this.teleport(victim,point.offset(1.5,0,0));await this.equip(nonkiller,'stone_sword');
    await this.wait(()=>!!this.bots[nonkiller].players[name(victim)]?.entity,'native grace opponent tracked',2000);await sleep(700);
    const hitAt=Date.now();this.log('realGraceAttack',{nonkiller,victim});this.bots[nonkiller].attack(this.bots[nonkiller].players[name(victim)].entity);await sleep(150);
    this.check('Native grace weakness prevents a non-kill player sword hit',this.bots[victim].health===20&&!this.velocities.some(row=>row.id===victim&&row.at>=hitAt),{health:this.bots[victim].health,velocities:this.velocities.filter(row=>row.at>=hitAt)});
    const gold=this.source(34,6,-322);await this.pinChunk(gold);
    this.check('Source target exists before timeout observation',await this.serverBlockIs(gold,'gold_block'));
    await this.sprint(4200);
    this.check('Source game remains open before its4800tick limit without a finisher',!this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text))&&this.completed.A<8&&this.completed.B<8,{completed:this.completed});
    await this.sprint(600);
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'original240-second timeout produces ranking',2500);
    this.check('Timeout ranks partial progress while both are unfinished',this.completed.A<8&&this.completed.B<8,{completed:this.completed});
    await this.wait(()=>!!this.bots.A.currentWindow,'timeout returns to tournament selection',30000);
    this.check('Timeout preserves the five-round series and excludes411',!this.menuChoices(this.bots.A.currentWindow).some(choice=>choice.source===411));
    this.check('Timeout clears the real loaded source target',await this.waitServerBlock(gold,'air'));
    await this.leaveAll();await this.unpinChunks();await sleep(5500);

    since=await this.startTwo();
    const marker=this.source(34,-13,-341);await this.pinChunk(marker);
    await this.completeAll('A',false);
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'one finisher is N-1 in a two-player game',2500);
    this.check('One of two finishers ends the source round immediately',this.completed.A===8&&this.completed.B<8,{completed:this.completed});
    this.check('Two-player task order still visits all eight distinct tasks',new Set(this.history.A).size===8,{history:this.history.A});
    this.check('Empty third and fourth source pedestals remain part of the visit task',this.history.A.includes(6));
    await this.wait(()=>!!this.bots.A.currentWindow,'one-finisher result continues series',30000);
    this.check('Two-player finisher result cleans its pinned source pedestal',await this.waitServerBlock(marker,'air'));
    await this.leaveAll();await this.unpinChunks();
  }
}
async function main(){const probe=new BoundaryProbe();let error;try{await probe.launch();await probe.connectAll('AB');await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});}const report=await probe.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={BoundaryProbe};
