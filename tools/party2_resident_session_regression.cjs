'use strict';
// Reason: retained Party terrain must survive full player match lifecycles and
// be reused without replaying thirteen million cells. GUI, equipment and match
// packets are real; terminal teleports and an AIR-cell mutation are fixtures.
const path=require('node:path');
const {RocketProbe}=require('./party2_rocket_launch_regression.cjs');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
class ResidentSessionProbe extends RocketProbe {
 constructor(){super({port:Number(process.env.MYGO_TEST_PORT||25708),runDir:process.env.MYGO_TEST_RUN_DIR||path.join(__dirname,'../build/mygo-party2-resident-session')});}
 async run(){
  await this.console('scoreboard objectives add p2_rl_count dummy');
  await this.begin();
  const original=this.offset.clone(),firstLoad=this.loadMillis;
  const far=this.source(-62,195,-2043),deep=this.source(-62,-64,-2043),dirty=this.source(-62,220,-40);
  for(const point of [far,dirty])await this.pinChunk(point);
  this.check('Resident game uses a separate Z band from its unchanged waiting lobby',this.offset.z>60000,{offset:this.offset});
  this.check('First real round retains the full original far surface and underground',await this.serverBlockIs(far,'black_concrete')&&await this.serverBlockIs(deep,'bedrock'));
  this.check('The runtime fixture starts in actual baseline AIR',await this.serverBlockIs(dirty,'air'));
  this.log('fixtureNote',{description:'Write one diamond block in baseline AIR; finish players using original source terminal TP, not a claimed full-course flight.'});
  await this.console(`execute in pvp:arena run setblock ${dirty.x} ${dirty.y} ${dirty.z} minecraft:diamond_block`);
  this.check('Actual leased scene records a real runtime block',await this.serverBlockIs(dirty,'diamond_block'));
  for(const id of ['A','B','C'])await this.tp(id,-62,242,-2043.4,'Terminal boundary fixture ends this pilot through actual source game logic.');
  await this.wait(()=>['A','B','C'].every(id=>this.done[id]),'all pilots finish source terminal',10000);
  const since=Date.now();
  await this.wait(()=>!!this.bots.A.currentWindow,'sparse reset reaches the next real vote',30000);
  const resetAndResults=Date.now()-since;
  this.check('Full resident surface and underground survive round cleanup',await this.serverBlockIs(far,'black_concrete')&&await this.serverBlockIs(deep,'bedrock'));
  this.check('The added AIR-cell diamond is restored instead of retained',await this.serverBlockIs(dirty,'air'));
  this.check('Native statues and minecarts from the completed round are removed',!await this.condition('in pvp:arena if entity @e[tag=party_rocket_launch_stand]')&&!await this.condition('in pvp:arena if entity @e[tag=party_rocket_launch_cart]'));
  await this.leaveAll(); await sleep(6000);
  await this.begin();
  const secondLoad=this.loadMillis;
  this.check('A new real match rents the same restored resident scene',this.offset.equals(original),{first:original,second:this.offset});
  this.check('A warmed real round starts within12seconds including its required8second INTRO',secondLoad<12000,{firstLoad,secondLoad,includesIntroMillis:8000});
  this.check('The reused source terrain remains complete with no previous AIR-cell mutation',await this.serverBlockIs(far,'black_concrete')&&await this.serverBlockIs(deep,'bedrock')&&await this.serverBlockIs(dirty,'air'));
  await this.leaveAll(); await sleep(6500);
  this.check('Cancelling the reused real round preserves its baseline',await this.serverBlockIs(far,'black_concrete')&&await this.serverBlockIs(deep,'bedrock'));
  await this.unpinChunks();
  this.log('residentSessionTiming',{firstLoadMillis:firstLoad,secondLoadMillis:secondLoad,roundResultAndResetMillis:resetAndResults,requiredIntroMillis:8000});
 }
}
(async()=>{const p=new ResidentSessionProbe();let error;try{await p.launch();await p.connectAll('ABC');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});await p.diagnose().catch(()=>{});}const report=await p.close(error);if(!report.ok)process.exitCode=1;})().catch(e=>{console.error(e.stack);process.exitCode=1;});
