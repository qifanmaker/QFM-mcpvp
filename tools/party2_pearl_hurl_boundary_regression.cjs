'use strict';

// Reason: source5+ uses an alternating, parity-adjusted player_line, independent
// initial mp markers and introLIGHT updates. Review: actual five-player queue,
// packets/positions and timeout; only explicitly labeled position/tick fixtures.
const path=require('node:path');
const {PearlProbe}=require('./party2_pearl_hurl_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
class FiveProbe extends PearlProbe {
  constructor(){super({runDir:path.join(__dirname,'../build/mygo-party2-pearl-hurl-five')});}
  async run(){await this.resetLobby();const since=await this.startPearl();
    const actual=Object.values(this.bots).map(b=>b.probePosition.minus(this.offset)).sort((a,b)=>a.x-b.x),expected=this.rules.spawnRules.five.map(p=>new Vec3(...p)).sort((a,b)=>a.x-b.x);
    this.check('Real5-player launch retains original alternating positions and uncenteredZ',actual.length===5&&actual.every((p,i)=>p.distanceTo(expected[i])<.02),{actual,expected});
    this.check('Native5-player launch forces original north-facing yaw and level pitch',Object.keys(this.bots).every(id=>{const r=this.rotations[id];return r&&Math.abs(Math.abs(r.yaw)-180)<.02&&Math.abs(r.pitch)<.02;}),this.rotations);
    this.check('Native actual5-player roster receives one pearl each',await this.waitPearls());await this.dropControl();
    const initial=this.starts.A;let air;
    for(let dy=3;dy<9;dy++){const p=initial.offset(0,dy,0);if(await this.condition(`in pvp:arena if block ${Math.floor(p.x)} ${Math.floor(p.y)} ${Math.floor(p.z)} air`)){air=p;break;}}
    if(!air)throw new Error('No original AIR above source5-player live line');
    this.log('fixtureNote',{description:'Actual AIR failure above the original live line must return to the intro-updated nonzeroLIGHT safe marker, rather than the different initial mp marker.'});
    await this.teleport('A',air);await sleep(160);this.check('IntroLIGHT updates five-player fallback to the exact live position',this.bots.A.probePosition.distanceTo(initial)<.03,{initial,actual:this.bots.A.probePosition});
    const cpPoint=await this.arrive('A',1);this.check('Five-player source progress is independent for all participants',this.cp.A===1&&['B','C','D','E'].every(id=>this.cp[id]===0),this.cp);
    this.log('fixtureNote',{description:'Native /kill exercises the actual fatal-death hook; no checkpoint, safe marker or health field is edited by the test.'});await this.console('kill MyGoCheckA');await sleep(180);this.check('Actual death retains checkpoint and restores health at the latest original safeLIGHT',this.cp.A===1&&this.bots.A.health===20&&this.bots.A.game.gameMode==='adventure'&&this.bots.A.probePosition.distanceTo(this.source(cpPoint.x,cpPoint.y,cpPoint.z))<.03,{checkpoint:this.cp.A,health:this.bots.A.health,mode:this.bots.A.game.gameMode,position:this.bots.A.probePosition});
    const marker=this.source(0,63,0);await this.pinChunk(marker);
    await this.sprint(3000);this.check('Five-player source round stays open before its3600tick limit',!this.chats.some(c=>c.id==='A'&&c.at>=since&&/第\s+1\/5\s+轮排名/.test(c.text)));
    await this.sprint(610);await this.wait(()=>this.chats.some(c=>c.id==='A'&&c.at>=since&&/第\s+1\/5\s+轮排名/.test(c.text)),'actual180second timeout ranks5players',5000);
    this.check('Native timeout ranks partial5-player progress without a false finisher',this.cp.A===1&&['B','C','D','E'].every(id=>this.cp[id]===0),this.cp);
    await this.wait(()=>['B','C','D','E'].every(id=>this.chats.some(c=>c.id==='A'&&c.at>=since&&new RegExp(`^第 2 名 MyGoCheck${id} `).test(c.text))),'native tied placement message delivery',2000);const ranks=this.chats.filter(c=>c.id==='A'&&c.at>=since).map(c=>c.text);this.check('Source progress and ties feed actual five-round placement points',ranks.some(t=>/^第 1 名 MyGoCheckA .*本轮 \+5/.test(t))&&['B','C','D','E'].every(id=>ranks.some(t=>new RegExp(`^第 2 名 MyGoCheck${id} .*本轮 \\+4`).test(t))),ranks.filter(t=>/^第 \d+ 名/.test(t)));
    await this.wait(()=>!!this.bots.A.currentWindow,'five-player cleanup continues series',180000);this.check('Five-player series excludes played426',!this.menuChoices(this.bots.A.currentWindow).some(c=>c.source===426));this.check('Five-player full map cleanup removes loaded original island',await this.waitServerBlock(marker,'air'));await this.leaveAll();await this.unpinChunks();
  }
  async waitPearls(){await this.wait(()=>Object.values(this.bots).every(b=>b.inventory.items().some(i=>i.name==='ender_pearl')),'five actual native pearl inventories',3500);return Object.values(this.bots).every(b=>b.inventory.items().filter(i=>i.name==='ender_pearl').reduce((n,i)=>n+i.count,0)===1);}
}
async function main(){const p=new FiveProbe();let error;try{await p.launch();await p.connectAll('ABCDE');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
