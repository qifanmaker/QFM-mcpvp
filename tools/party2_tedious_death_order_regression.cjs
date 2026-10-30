'use strict';

// Reason: original411 checks goal predicates before deaths=1 resets the task.
// An inventory goal acquired in the lethal tick must count; a pending30-ring
// delay must not. Review: test-only functions execute tightly scoped fixture
// commands in one actual server tick. They never ship or execute source code.
const fs=require('node:fs');
const path=require('node:path');
const {TediousProbe}=require('./party2_tedious_regression.cjs');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const name=id=>`MyGoCheck${id}`;
const SPAWNS=[[34.5,-12,-340.5],[34.5,-12,-302.5],[53.5,-12,-321.5],[15.5,-12,-321.5]];
const NOTES=[[33,-12,-344],[33,-12,-300],[56,-12,-323],[12,-12,-323]];
const BELLS=[[34,-9,-327],[34,-9,-317],[39,-9,-322],[29,-9,-322]];

class DeathOrderProbe extends TediousProbe {
  constructor(){super({port:25630,runDir:path.join(__dirname,'../build/mygo-party2-tedious-death-order')});}
  async installProbe(){
    this.pack=path.join(this.runDir,'mygo-validation-world/datapacks/party-tedious-death-probe');
    this.functions=path.join(this.pack,'data/p2ttprobe/function');fs.mkdirSync(this.functions,{recursive:true});
    fs.writeFileSync(path.join(this.pack,'pack.mcmeta'),JSON.stringify({pack:{pack_format:48,description:'Isolated411 death-order observation fixtures; never shipped'}}));
    fs.writeFileSync(path.join(this.functions,'ordinary.mcfunction'),`damage ${name('A')} 100 minecraft:generic_kill\n`);
    fs.writeFileSync(path.join(this.functions,'axe.mcfunction'),`give ${name('A')} wooden_axe\ndamage ${name('A')} 100 minecraft:generic_kill\n`);
    fs.writeFileSync(path.join(this.functions,'star.mcfunction'),`give ${name('A')} nether_star\ndamage ${name('A')} 100 minecraft:generic_kill\n`);
    fs.writeFileSync(path.join(this.functions,'kill.mcfunction'),`effect clear ${name('B')} resistance\ndamage ${name('B')} 100 minecraft:player_attack by ${name('A')}\ndamage ${name('A')} 100 minecraft:generic_kill\n`);
    await this.reloadProbe();
  }
  async reloadProbe(){
    const token=`P2_TT_PROBE_READY_${++this.sequence}`;
    fs.writeFileSync(path.join(this.functions,'ready.mcfunction'),`say ${token}\n`);
    const since=this.serverLines.length;await this.console('reload',100);
    const deadline=Date.now()+10000;
    while(!this.serverLines.slice(since).some(row=>row.text.includes(`[Server] ${token}`))){
      if(Date.now()>deadline)throw new Error('Runtime-only fixture pack did not finish reloading');
      await this.console('function p2ttprobe:ready',80);
    }
  }
  async ordinary(task){
    const completed=this.completed.A,resets=this.resets.A;
    this.log('sameTickFixture',{description:`Lethal native generic_kill with task${task} unsatisfied; completed work must stay unchanged.`});
    await this.console('function p2ttprobe:ordinary');
    await this.wait(()=>this.resets.A>resets,'ordinary incomplete-task death reset',2500);
    this.check(`Unsatisfied task${task} death retries without awarding completion`,this.tasks.A===task&&this.completed.A===completed,{completed:this.completed.A,task:this.tasks.A});
  }
  async goalDeath(task,fn){
    const completed=this.completed.A;
    await this.sprint(65);
    this.log('sameTickFixture',{description:`The ${fn} function places a real task${task} goal in one tick immediately before lethal damage.`});
    await this.console(`function p2ttprobe:${fn}`);
    await this.wait(()=>this.completed.A>completed,`task${task} completion wins over same-tick death reset`,2500);
    this.check(`Satisfied task${task} counts before the same-tick death reset`,this.completed.A===completed+1,{completed:this.completed.A,task:this.tasks.A});
    this.check(`Task${task} completion survives as one distinct task`,new Set(this.history.A).size===this.history.A.length,{history:this.history.A});
  }
  async noteDeath(){
    const seat=this.seats.A,base=this.source(...NOTES[seat]),commands=[];
    for(let i=0;i<3;i++) {
      const pos=base.offset(seat<2?i:0,1,seat<2?0:i);
      commands.push(`execute in pvp:arena run setblock ${pos.x} ${pos.y} ${pos.z} note_block[note=0,instrument=harp,powered=false]`);
    }
    commands.push(`damage ${name('A')} 100 minecraft:generic_kill`);
    fs.writeFileSync(path.join(this.functions,'notes.mcfunction'),commands.join('\n')+'\n');
    await this.reloadProbe();
    await this.goalDeath(7,'notes');
  }
  async ringNotEarly(){
    const completed=this.completed.A,resets=this.resets.A,bell=this.source(...BELLS[this.seats.A]);
    await this.teleport('A',bell.offset(-1,-3,.5));await this.equip('A','stone_sword');
    await this.console('scoreboard objectives add p2_tt_edge_bells minecraft.custom:minecraft.bell_ring');
    await this.clicks('A',bell,30,0);
    this.check('Thirty real bell interactions are recorded before lethal fixture',await this.condition(`if score ${name('A')} p2_tt_edge_bells matches 30`));
    await this.console('function p2ttprobe:ordinary');
    await this.wait(()=>this.resets.A>resets,'pending-ring death resets',2500);
    this.check('Thirty rings plus death before deadline do not earn early completion',this.tasks.A===3&&this.completed.A===completed);
    // Source scheduled ring validation survives a death; let it expire before the clean retry.
    await this.sprint(25);
    this.check('The unfulfilled old delayed check still gives no task credit',this.tasks.A===3&&this.completed.A===completed);
    await this.ring('A',false);
    await this.waitAdvance('A',3,completed);
  }
  async positionalDeath(task){
    const completed=this.completed.A;
    if(task===2){
      const point=this.source(34.5,7,-321.5),resets=this.resets.A;
      fs.writeFileSync(path.join(this.functions,'gold_death.mcfunction'),
        `execute in pvp:arena run tp ${name('A')} ${point.x} ${point.y} ${point.z}\ndamage ${name('A')} 100 minecraft:generic_kill\n`);
      await this.reloadProbe();
      this.log('sameTickFixture',{description:'Reach gold and die in one tick; the first revived goal check observes the home pedestal, not the lethal-hit location.'});
      await this.console('function p2ttprobe:gold_death');
      await this.wait(()=>this.resets.A>resets,'gold-location death resets',2500);
      this.check('Death on gold does not preserve the old task2 position',this.tasks.A===2&&this.completed.A===completed);
      this.pearlBefore=completed;await this.pearl('A',false);await this.waitAdvance('A',task,completed);
    }else{
      for(let seat=0;seat<4;seat++)if(seat!==this.seats.A){await this.teleport('A',this.source(...SPAWNS[seat]));await this.sprint(3);}
      this.check('Visiting all other pedestals still requires returning home',this.tasks.A===6&&this.completed.A===completed);
      this.log('sameTickFixture',{description:'After visiting all three other pads, native lethal damage returns the intercepted player home; source positional completion precedes visit reset on the revived tick.'});
      await this.console('function p2ttprobe:ordinary');
      await this.waitAdvance('A',task,completed);
      this.check('Respawning home after all other visits completes task6 before reset',this.completed.A===completed+1);
    }
  }
  async run(){
    const since=await this.start('tedious');this.seats={};
    for(const id of ['A','B','C','D'])this.seats[id]=SPAWNS.findIndex(point=>this.bots[id].probePosition.distanceTo(this.source(...point))<.3);
    await this.installProbe();
    const observed=new Set(this.history.A.slice(0,this.completed.A));
    while(this.completed.A<8){
      const task=this.tasks.A,completed=this.completed.A;
      if([1,4,5,7].includes(task))await this.ordinary(task);
      if(task===1)await this.goalDeath(task,'kill');
      else if(task===4)await this.goalDeath(task,'axe');
      else if(task===5)await this.goalDeath(task,'star');
      else if(task===7)await this.noteDeath();
      else if(task===3)await this.ringNotEarly();
      else if(task===2||task===6)await this.positionalDeath(task);
      else if(task===8){await this.ordinary(task);await this.sprint(65);await this.console(`effect clear ${name('A')} resistance`);await this.heal('A',false);await this.waitAdvance('A',task,completed);}
      observed.add(task);
    }
    this.check('Completion-before-reset preserves all eight task results',observed.size===8&&this.completed.A===8,{observed:[...observed],history:this.history.A});
    this.check('One of four finishers still does not settle the whole round',!this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)));
    await this.leaveAll();
  }
}
async function main(){const probe=new DeathOrderProbe();let error;try{await probe.launch();await probe.connectAll('ABCD');await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});}const report=await probe.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={DeathOrderProbe};
