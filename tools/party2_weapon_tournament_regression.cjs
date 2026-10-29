'use strict';
// Reason: source410's raw progression must settle once per round while its
// effects/projectiles stay out of the next four games and the final match.
// This lifecycle test uses explicit damage/finish-zone fixtures; the separate
// 72-check weapon regression exercises the actual five native weapons.
const {WeaponProbe}=require('./party2_weapon_regression.cjs');
const {GAMES}=require('./party2_protocol_regression.cjs');
const path=require('node:path'),fs=require('node:fs'),crypto=require('node:crypto');
const IDS=['A','B','C','D'],PLAN=['weapon','rlgl','rainbow','runner','dropper'];
const name=id=>`MyGoCheck${id}`;
const uuid=id=>{const b=crypto.createHash('md5').update(`OfflinePlayer:${name(id)}`).digest();b[6]=(b[6]&15)|48;b[8]=(b[8]&63)|128;const h=b.toString('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;};
class WeaponTournament extends WeaponProbe{
 constructor(){super({port:25634,runDir:path.join(__dirname,'../build/mygo-party2-weapon-tournament')});this.round=0;this.rows=new Map();this.finalHeaders=0;this.finalRows=[];this.votes=new Set();}
 connect(id){const bot=super.connect(id);bot.on('windowOpen',window=>{if(IDS.includes(id)&&JSON.stringify(window.title).includes('Party'))void this.votePlan(id,window).catch(e=>{this.voteError=e;});});bot.on('messagestr',message=>{if(id!=='A')return;const text=String(message).replace(/§./g,'');const header=text.match(/第\s+(\d+)\/5\s+轮排名/);if(header){this.round=Number(header[1]);this.rows.set(this.round,[]);}if(this.rows.has(this.round)&&/第 \d+ 名.*MyGoCheck.*本轮/.test(text))this.rows.get(this.round).push(text);if(text.includes('Party 2 五轮总排名'))this.finalHeaders++;if(this.finalHeaders&&/第 \d+ 名.*MyGoCheck.*累计.*分/.test(text))this.finalRows.push(text);});return bot;}
 async votePlan(id,window){const key=`${this.round}:${id}:${window.id}`;if(this.votes.has(key))return;this.votes.add(key);await this.wait(()=>this.menuChoices(window).length>0,'menu lore');const choices=this.menuChoices(window),source=GAMES[PLAN[this.round]].source,choice=choices.find(x=>x.source===source);this.check(`Round${this.round+1} ${id} can vote original${source}`,!!choice,{choices,source});this.check(`Round${this.round+1} omits every played game`,PLAN.slice(0,this.round).every(g=>!choices.some(x=>x.source===GAMES[g].source)));await this.bots[id].clickWindow(choice.slot,0,0);}
 async until(p,label,timeout=15000){await this.wait(()=>{if(this.voteError)throw this.voteError;return p();},label,timeout);}
 async started(round){const source=GAMES[PLAN[round-1]].source,selection=()=>this.chats.find(r=>r.id==='A'&&new RegExp(`第\\s+${round}/5\\s+轮选择：.*原版编号\\s+${source}`).test(r.text));await this.until(()=>selection(),`round${round} selects${source}`,90000);await this.until(()=>IDS.every(id=>this.bots[id].probeStartedAt>=selection().at),`round${round} PLAY`,60000);this.log('sourceRoundStarted',{round,source,players:IDS.map(id=>this.snapshot(id))});}
 async tag(ids){await this.console('tag @a remove p2_wd_finish');for(const id of ids)await this.console(`tag ${name(id)} add p2_wd_finish`);}
 stats(){const p=path.join(this.runDir,'config/pvp/stats.json');return fs.existsSync(p)?JSON.parse(fs.readFileSync(p,'utf8')):{};}
 async runTournament(){
  const before=this.stats(),started=Date.now();for(const id of IDS)await this.chat(id,'/pvp join minecraftparty');
  await this.started(1);
  this.log('fixtureNote',{description:'Fifteen native player_attack damage commands isolate 410 source stat progression and ranking; actual native weapons were separately verified.'});
  for(const killer of ['A','B','C'])for(let i=0;i<5;i++){await this.sprint(65);await this.fixtureDamage('D',200,killer);await new Promise(r=>setTimeout(r,100));}
  await this.until(()=>this.rows.get(1)?.length===4,'source410 round1 complete');
  this.check('Source410 first round ranks A/B/C/D',[['A',1,4],['B',2,3],['C',3,2],['D',4,1]].every(([id,place,points])=>this.rows.get(1).some(t=>new RegExp(`第 ${place} 名.*${name(id)}.*本轮 \\+${points}`).test(t))),this.rows.get(1));
  this.check('Source410 private states cleared before later rounds',!await this.effect('A','weakness')&&!await this.effect('A','regeneration')&&!await this.condition('in pvp:arena if entity @e[tag=party_wd_projectile]'));
  await this.started(2);let p=this.bots.A.probePosition;await this.tag(IDS);await this.console(`execute in pvp:arena as @a[tag=p2_wd_finish] run tp @s ${p.x.toFixed(8)} ${p.y.toFixed(8)} ${(p.z-145.5).toFixed(8)}`);await this.until(()=>this.rows.get(2)?.length===4,'RLGL after410');
  await this.started(3);p=this.bots.A.probePosition;await this.tag(['B','C','D']);await this.console(`execute in pvp:arena as @a[tag=p2_wd_finish] run tp @s ${p.x.toFixed(8)} ${(p.y-27).toFixed(8)} ${p.z.toFixed(8)}`);await this.until(()=>this.rows.get(3)?.length===4,'Rainbow after410');
  await this.started(4);await this.tag(['B','C','D']);await this.console('execute as @a[tag=p2_wd_finish] run damage @s 1000 minecraft:fall');await this.until(()=>this.rows.get(4)?.length===4,'Runner after410');
  await this.started(5);const starts=IDS.map(id=>this.bots[id].probePosition),x=starts.reduce((n,p)=>n+p.x,0)/4,z=starts.reduce((n,p)=>n+p.z,0)/4;await this.tag(IDS);await this.console(`execute in pvp:arena as @a[tag=p2_wd_finish] run tp @s ${x.toFixed(8)} -62.75 ${z.toFixed(8)}`);await this.sprint(200);
  await this.until(()=>this.rows.get(5)?.length===4&&this.finalHeaders===1&&this.finalRows.length===4,'all five rounds finish',35000);
  const totals={A:20,B:17,C:16,D:15};for(const [i,id]of IDS.entries())this.check(`${id} final rank/score accumulates410 plus four later modes`,this.finalRows.some(t=>new RegExp(`第 ${i+1} 名.*${name(id)}.*累计 ${totals[id]} 分`).test(t)),this.finalRows);
  await this.until(()=>IDS.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'five-round hub return',35000);
  await this.until(()=>IDS.every(id=>this.bots[id].inventory.items().some(x=>x.name==='compass')),'restored hub compasses',15000);
  this.check('Exactly five distinct rounds and one final header',this.rows.size===5&&this.finalHeaders===1,{rounds:[...this.rows.keys()],finalHeaders:this.finalHeaders});
  await this.until(()=>IDS.every(id=>(this.stats()[uuid(id)]?.matches||0)===(before[uuid(id)]?.matches||0)+1),'one persisted match per player',25000);
  for(const id of IDS){this.check(`${id} exactly one feedback hint`,this.chats.filter(r=>r.id===id&&r.at>=started&&r.text.includes('qfmaker.com/blackboard/mcserverfb')).length===1);const old=before[uuid(id)]||{},now=this.stats()[uuid(id)]||{};this.check(`${id} exactly one final match and expected win/loss`,now.matches===(old.matches||0)+1&&now.wins===(old.wins||0)+(id==='A'?1:0)&&now.losses===(old.losses||0)+(id==='A'?0:1),{old,now});}
 }
}
(async()=>{const p=new WeaponTournament();let error;try{await p.launch();await p.connectAll('ABCDE');await p.runTournament();}catch(e){error=e;p.log('failure',{error:e.stack});}const r=await p.close(error);if(!r.ok)process.exitCode=1;})().catch(e=>{console.error(e.stack);process.exitCode=1;});
