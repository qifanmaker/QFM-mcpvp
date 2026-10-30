'use strict';

// Reason: source421 also needs the2-player N-1 threshold,240-second timeout,
// actual source-bed respawn scoped away from a player's permanent home, and
// native access to the unlocked original cargo. Review: independent25656 run
// after the4-player process exits; labeled position/time fixtures only.
const path=require('node:path');
const {PortProbe}=require('./party2_port_priority_regression.cjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
class BoundaryProbe extends PortProbe {
  constructor(){super({runDir:path.join(__dirname,'../build/mygo-party2-port-priority-boundary')});}
  ranked(since){return this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text));}
  async bed(){
    this.log('fixtureNote',{description:'Seed disposable A permanent Overworld spawn and teleport next to an original orange source bed. Actual player bed use, then native damage, must preserve the permanent spawn and use the round-local bed.'});
    await this.console('execute in minecraft:overworld run spawnpoint MyGoCheckA 202 70 202');
    const bed=this.source(-402,39,-270);await this.teleport('A',bed.offset(0,0,1.5));await this.clickBlock('A',bed);await sleep(100);
    this.check('Actual native source-bed use retains the permanent Overworld home',await this.condition('if entity @a[name=MyGoCheckA,nbt={SpawnX:202,SpawnY:70,SpawnZ:202,SpawnDimension:"minecraft:overworld"}]'));
    await this.console('damage MyGoCheckA 100 minecraft:generic_kill');await sleep(180);
    this.check('Native death uses the original bed while in the harbor',this.bots.A.probePosition.distanceTo(bed)<4,{bed,position:this.bots.A.probePosition});
    this.check('Bed death still leaves the permanent home unchanged',await this.condition('if entity @a[name=MyGoCheckA,nbt={SpawnX:202,SpawnY:70,SpawnZ:202,SpawnDimension:"minecraft:overworld"}]'));
  }
  async originalCargo(){const b=this.bots.B,point=this.source(-308,30,-239);await this.teleport('B',point.offset(1.5,0,.5));await this.clickBlock('B',point);await this.wait(()=>!!b.currentWindow,'actual unlocked source cargo opens',4000);
    this.check('Original unlocked cargo opens without a matching quest key',b.currentWindow.type==='minecraft:generic_9x3'&&b.currentWindow.slots.slice(0,27).some(item=>item?.name==='diamond'));b.closeWindow(b.currentWindow);
  }
  async run(){await this.resetLobby();let since=await this.startPort();await this.discover();await this.bed();await this.originalCargo();
    this.check('Both two-player participants remain unfinished before timeout',this.found.A===0&&this.found.B===0);
    await this.sprint(4200);this.check('Native240-second round remains open before4800ticks',!this.ranked(since));
    await this.sprint(600);await this.wait(()=>this.ranked(since),'native240-second timeout ranks partial progress',5000);
    this.check('Timeout settles both unfinished participants',this.found.A===0&&this.found.B===0);
    await this.wait(()=>!!this.bots.A.currentWindow,'timeout cleanup returns to tournament vote',120000);
    this.check('Timeout keeps five-round series and excludes421',!this.menuChoices(this.bots.A.currentWindow).some(c=>c.source===421));
    this.check('Timeout clears original deep seafloor in the loaded witness',await this.waitServerBlock(this.marker,'air'));
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
    await this.resetLobby();since=await this.startPort();await this.discover();for(let i=0;i<3;i++)await this.take('A');
    await this.wait(()=>this.ranked(since),'one of two native finishers ends source round',5000);
    this.check('Two-player N-1 ends on exactly one finisher',this.found.A===3&&this.found.B===0,this.found);
    this.check('Both target orders retain the shared3-item set',new Set(this.history.A).size===3&&this.history.B.every(item=>this.history.A.includes(item)),this.history);
    await this.wait(()=>!!this.bots.A.currentWindow,'two-player finisher cleanup continues series',120000);
    this.check('Two-player finish clears original loaded seafloor',await this.waitServerBlock(this.marker,'air'));await this.leaveAll();await this.unpinChunks();
  }
}
async function main(){const p=new BoundaryProbe();let error;try{await p.launch();await p.connectAll('AB');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={BoundaryProbe};
