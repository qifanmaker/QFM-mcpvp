'use strict';

// Reason: source418 does not end merely because one player remains. The survivor
// must strictly lead retained scores; all-out is a separate boundary. Review:
// lethal native player_attack commands are explicit survival fixtures only.
// The surviving player's earned score still comes from actual native input.
const path=require('node:path');
const {SteveProbe}=require('./party2_steve_says_regression.cjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const name=id=>`MyGoCheck${id}`;
class BoundaryProbe extends SteveProbe {
  constructor(){super({runDir:path.join(__dirname,'../build/mygo-party2-steve-says-boundary')});}
  async lethal(id,attacker='A'){
    this.log('fixtureNote',{description:`Native lethal player_attack eliminates ${id} to isolate source early-end ordering; no score is edited.`});
    await this.console(`damage ${name(id)} 100 minecraft:player_attack by ${name(attacker)}`);
    await this.wait(()=>this.bots[id].game.gameMode==='spectator',`${id} source death becomes spectator`,2500);
  }
  async oneNativeAction(action){
    const b=this.bots.A,task=action.task,says=action.instruction.includes('史蒂夫说');
    const boots=this.requestedItem(action,{'钻石靴':'diamond_boots','金靴子':'golden_boots','皮革靴':'leather_boots'});
    const stone=this.requestedItem(action,{'石头':'stone','圆石':'cobblestone','滴水石块':'dripstone_block'});
    const trap=(task===3&&says&&!b.inventory.items().some(s=>s.name===boots))||(task===8&&says&&!b.inventory.items().some(s=>s.name===stone))||action.instruction.includes('羊毛');
    const obey=says&&!trap;
    if(task===1&&obey){
      // A sole survivor cannot punch a spectator:0points is the faithful result.
      this.log('fixtureNote',{description:'No living target remains for the source punch task. Wait for the next instruction.'});return false;
    }
    if(task===2&&obey){b._client.write('entity_action',{entityId:b.entity.id,actionId:0,jumpBoost:0});await sleep(220);b._client.write('entity_action',{entityId:b.entity.id,actionId:1,jumpBoost:0});}
    if(task===3&&obey)await b.equip(b.inventory.items().find(s=>s.name===boots),'feet');
    if(task===4&&obey)await this.jump('A');
    if(task===5&&obey)this.lookNative('A',-89.5);
    if((task===6||task===7)&&obey){const p=this.source(-666,33,-29);await this.teleport('A',p.offset(-1,0,.5));await this.clickNative('A',p,task===6?1:3,task===6?1:0);}
    if(task===8&&obey)await b.tossStack(b.inventory.items().find(s=>s.name===stone));
    if(task===9&&obey)await this.ground('A',this.source(-664.5,34.3,-31.5));
    if(task===10&&obey){const target=this.requestedItem(action,{'橙色':'orange_concrete','黄色':'yellow_concrete','红色':'red_concrete'});await b.tossStack(b.inventory.items().find(s=>s.name===target));}
    if(task===11&&!says)await this.movement('A');
    return true;
  }
  async startBoundary(){this.actions=[];for(const id of ['A','B','C','D'])this.results[id]=[];const since=await this.start('steve');await this.baseline(since);return since;}
  async run(){
    let since=await this.startBoundary();
    for(const id of ['B','C','D'])await this.lethal(id);
    await this.wait(()=>this.actions.length>0,'sole tied survivor still receives an instruction',5000);
    this.check('A sole survivor tied with retained zero scores does not finish early',this.bots.A.game.gameMode==='adventure'&&!this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)));
    for(let i=0;i<22;i++){
      await this.wait(()=>this.actions.length>i,'next instruction for sole survivor',12000);
      const act=this.actions[i],canEarn=await this.oneNativeAction(act);
      await this.wait(()=>this.results.A.some(r=>r.number===act.number),'sole-survivor deadline',12000);
      const result=this.results.A.find(r=>r.number===act.number);
      this.check('Sole survivor native goal/refraining earns points without elimination',!result.out&&(canEarn?result.points>=3:result.points===0),result);
      if(result.total>0)break;
    }
    await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'strictly leading survivor ends between instructions',5000);
    this.check('A sole strictly leading survivor ends before twenty-two actions',this.actions.length<22&&this.results.A.at(-1).total>0,{actions:this.actions.length,result:this.results.A.at(-1)});
    await this.wait(()=>!!this.bots.A.currentWindow,'early-end continues five-round match',20000);
    this.check('Early-end removes the loaded classroom',await this.waitServerBlock(this.marker,'air'));
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
    since=await this.startBoundary();
    for(const id of ['B','C','D','A'])await this.lethal(id);
    await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'all eliminated source ends',5000);
    this.check('All eliminated participants settle without waiting for twenty-two actions',this.actions.length<22,{actions:this.actions.length});
    this.check('All-out keeps zero raw scores rather than inventing a surviving winner',!Object.values(this.results).some(rows=>rows.some(r=>r.total>0)));
    await this.wait(()=>!!this.bots.A.currentWindow,'all-out continues series',20000);
    this.check('All-out removes its loaded classroom',await this.waitServerBlock(this.marker,'air'));
    await this.leaveAll();await this.unpinChunks();
  }
}
async function main(){const p=new BoundaryProbe();let error;try{await p.launch();await p.connectAll('ABCD');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
