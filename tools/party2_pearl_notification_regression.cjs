'use strict';
// Reason: production pearl notification must depend on native teleport success,
// survive zero displacement, and reject failure/stale phase/world. This owns
// only25682 and snapshot-only controls; it never substitutes a notification.
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline');
const {spawn}=require('node:child_process');
const {PearlProbe}=require('./party2_pearl_hurl_regression.cjs');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
class NotificationProbe extends PearlProbe {
  constructor(){super({port:25682,runDir:path.join(__dirname,'../build/mygo-party2-pearl-notification')});}
  async launch(){
    this.log('environment',{root:this.root,runDir:this.runDir,port:this.port,minecraft:'1.21.1',headless:true,rendered:false,fixture:'Snapshot native onCollision invoker + explicit return/TAIL negatives; production success hook is unchanged.'});
    this.server=spawn('python3',[path.join(this.root,'tools/party2_pearl_notification_server.py'),'--port',String(this.port),'--run-dir',this.runDir],{cwd:this.root,stdio:['pipe','pipe','pipe']});
    for(const[stream,kind]of[[this.server.stdout,'stdout'],[this.server.stderr,'stderr']])readline.createInterface({input:stream}).on('line',line=>{const row={at:Date.now(),stream:kind,text:line};this.serverLines.push(row);this.log('server',row);});
    this.server.once('exit',(code,signal)=>{this.serverExit={code,signal};this.log('serverExit',this.serverExit);});
    await this.wait(()=>this.serverLines.some(row=>/Done \([\d.]+s\)!/.test(row.text)),'notification snapshot ready',120000);
  }
  async fixture(test,id){const begin=this.serverLines.length;await this.console(`p2notify ${test} MyGoCheck${id}`,35);await this.wait(()=>this.serverLines.slice(begin).some(r=>r.text.includes('P2_NOTIFY_RESULT ')),`native${test} result`,8000);const line=this.serverLines.slice(begin).find(r=>r.text.includes('P2_NOTIFY_RESULT ')).text;const row=JSON.parse(line.slice(line.indexOf('P2_NOTIFY_RESULT ')+17));this.log('nativeNotificationFixture',row);if(row.error)throw new Error(row.error);return row;}
  accepted(row){return row.routes.filter(r=>r.layer==='global'&&r.accepted).length;}
  async atStart(id='A'){await this.teleport(id,this.starts[id]);await sleep(90);}
  async run(){
    await this.launch();await this.connectAll('ABCDE');await this.resetLobby();
    let row=await this.fixture('ordinary','E');
    this.check('Ordinary outside-match native pearl still teleports normally',row.ownerAtNativeTarget&&row.nativeOwnerDisplaced&&row.removed,row);
    this.check('Outside-match success reaches router but no module notification',row.routes.length===1&&row.routes[0].layer==='global'&&!row.routes[0].accepted&&row.moduleCallbacks===0,row);
    for(const id of['A','B','C','D'])await this.chat(id,'/pvp join minecraftparty');await this.wait(()=>this.bots.A.probeWorld==='pvp:arena'&&!!this.bots.A.currentWindow,'real SELECT lobby',30000);row=await this.fixture('ordinary','A');
    this.check('A real non-PLAY session rejects a successful native collision',row.ownerAtNativeTarget&&row.routes.some(r=>r.layer==='session'&&!r.accepted)&&row.moduleCallbacks===0,row);
    await this.leaveAll();await this.wait(()=>Object.values(this.bots).every(b=>b.probeWorld==='minecraft:overworld'),'SELECT cancellation hub',20000);await sleep(5600);
    // E stays in the actual Overworld as the unrelated-world control.
    const outsider=this.bots.E;delete this.bots.E;await this.startPearl();this.bots.E=outsider;
    for(const test of['ordinary','zero','null','spoof','tailshift','crossworld']){
      const subject=test==='crossworld'?'B':'A';await this.atStart(subject);row=await this.fixture(test,subject);
      if(test==='ordinary'||test==='zero'){
        this.check(`${test} native success is routed once and notifies the current module once`,this.accepted(row)===1&&row.routes.filter(r=>r.layer==='session'&&r.accepted).length===1&&row.moduleCallbacks===1&&row.ownerAtNativeTarget&&row.removed,row);
        if(test==='zero')this.check('Zero-distance success does not require owner displacement',!row.nativeOwnerDisplaced,row);
      }else if(test==='crossworld'){
        this.check('Native cross-world teleport keeps its real result',row.ownerAtNativeTarget&&row.afterWorld==='minecraft:overworld',row);
        this.check('Cross-world success is rejected by the current Arena session',row.routes.some(r=>r.layer==='session'&&!r.accepted)&&this.accepted(row)===0&&row.moduleCallbacks===0,row);
      }else{
        this.check(`Explicit ${test} return/TAIL negative does not invoke the production router`,row.routes.length===0&&row.moduleCallbacks===0&&row.removed,row);
        if(test==='null'||test==='spoof')this.check(`${test} fixture leaves native owner position unchanged`,!row.nativeOwnerDisplaced,row);
        if(test==='tailshift')this.check('TAIL position revalidation rejects movement after successful teleport',row.nativeOwnerDisplaced&&!row.ownerAtNativeTarget,row);
      }
    }
    await this.atStart();
    // Finish using the source's actual ordered LIGHT predicates and a genuine
    // native gateway throw. Each arrival is explicitly a labeled position
    // fixture, not a claim of rendered traversal of the entire source route.
    for(let cp=1;cp<=([23,21,19][this.path-1]);cp++)await this.arrive('A',cp);
    await this.nativeGateway('A');row=await this.fixture('zero','A');
    this.check('An actually finished spectator is rejected by the still-PLAY session',this.bots.A.game.gameMode==='spectator'&&row.routes.some(r=>r.layer==='session'&&!r.accepted)&&row.moduleCallbacks===0,row);
    await this.leaveAll();await this.wait(()=>Object.values(this.bots).every(b=>b.probeWorld==='minecraft:overworld'),'all hub after native controls',20000);
    row=await this.fixture('oldsession','A');this.check('A retained old-session reference rejects a departed/closed-round callback',row.hasRememberedSession&&!row.directRouterAccepted&&row.moduleCallbacks===0,row);
    row=await this.fixture('ordinary','A');this.check('After hub, ordinary native pearl behavior remains intact without module notification',row.ownerAtNativeTarget&&row.moduleCallbacks===0&&this.accepted(row)===0,row);
    await this.unpinChunks();
    const provenance=JSON.parse(fs.readFileSync(path.join(this.runDir,'native-provenance.json'),'utf8'));this.check('All five production classes are byte-identical to the final build snapshot',Object.values(provenance).every(p=>p.snapshotSHA256===p.buildSHA256),provenance);
  }
}
async function main(){const p=new NotificationProbe();let error;try{await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
