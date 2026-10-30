'use strict';

// Reason: a cave race with no finisher must still settle at the source 4000tick
// limit, rank checkpoint progress, and advance the five-round tournament once.
// Pre-review: own25602 only, use real GUI voting; teleports/time acceleration are
// explicit fixtures. Start only after the other parkour server has stopped.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const path = require('node:path');
const { Vec3 } = require('vec3');
class TimeoutProbe extends PartyProbe {
  constructor() { super({port:25602,runDir:path.join(__dirname,'../build/mygo-party2-parkour-timeout')}); }
  async sprint(ticks) {
    const from=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} server ticks; wait for actual Sprint completed before checking postcondition.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(from).some(row=>/Sprint completed/i.test(row.text)), 'sprint completed',90000);
  }
  async run() {
    const since=await this.start('parkour');
    const p=this.bots.A.probePosition,offset=new Vec3(p.x-204.5,p.y+9,p.z+170.5);
    const source=(x,y,z)=>new Vec3(x+offset.x,y+offset.y,z+offset.z);
    const fixtures={A:{stage:5,point:[167.5,-5,-164.5]},B:{stage:3,point:[158.5,-9,-140.5]},C:{stage:1,point:[201.5,-9,-138.5]}};
    this.log('fixtureNote',{description:'Teleport A/B/C to three real lamp checkpoints; nobody reaches the beacon, D keeps zero progress.'});
    for(const [id,fixture] of Object.entries(fixtures)) {
      const at=Date.now();await this.teleport(id,source(...fixture.point));
      await this.wait(()=>this.chats.some(row=>row.id===id&&row.at>=at&&row.text===`检查点 ${fixture.stage}/15`),`${id} checkpoint`);
    }
    const old=source(204,-10,-171);await this.pinChunk(old);
    this.check('Source gold start is present before timeout',await this.serverBlockIs(old,'gold_block'));
    await this.sprint(3500);
    const rows=()=>this.chats.filter(row=>row.id==='A'&&row.at>=since&&/第\s+\d+\s+名\s+MyGoCheck/.test(row.text));
    this.check('Race stays in PLAY before its4000tick deadline',rows().length===0&&Object.values(this.bots).every(bot=>bot.game.gameMode==='adventure'),Object.values(this.bots).map(bot=>bot.game.gameMode));
    await this.sprint(600);
    await this.wait(()=>rows().length===4,'source200second timeout produces one ranking');
    for(const [id,rank] of Object.entries({A:1,B:2,C:3,D:4})) {
      this.check(`Timeout ranks ${id} by checkpoint progress`,rows().some(row=>new RegExp(`第\\s+${rank}\\s+名\\s+MyGoCheck${id}(?:\\s|$)`).test(row.text)),rows());
    }
    this.check('Timeout settles one round and keeps five-round match open',this.chats.filter(row=>row.id==='A'&&row.at>=since&&row.text.includes('第 1/5 轮排名')).length===1
      &&!this.chats.some(row=>row.at>=since&&(row.text.includes('Party 2 五轮总排名')||row.text.includes('qfmaker.com/blackboard/mcserverfb'))));
    this.check('Timeout map is removed before the next vote',await this.waitServerBlock(old,'air',50000),old);
    await this.leaveAll();
  }
}
(async()=>{const probe=new TimeoutProbe();let error;try{await probe.launch();await probe.connectAll();await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});}const report=await probe.close(error);if(!report.ok)process.exitCode=1;})().catch(error=>{console.error(error);process.exitCode=1;});
