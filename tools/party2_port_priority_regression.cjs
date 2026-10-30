'use strict';

// Reason:421 must use actual locked chest GUIs, item transfers, frames, keys,
// scoped chunk leases and native terrain changes. Review: own25656 runtime;
// all console position/piston/water fixtures are labeled. No source functions
// execute, no score or module state is edited, and no rendered-client claim.
const fs=require('node:fs');
const path=require('node:path');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const name=id=>`MyGoCheck${id}`;
GAMES.port={source:421,players:4};
const ITEMS=['copper_ingot','netherite_ingot','prismarine_shard','quartz','gold_ingot','emerald','diamond'];
const LABELS=['铜锭','下界合金锭','海晶碎片','下界石英','金锭','绿宝石','钻石'];
const KEYS=['Copper Ingot Key','Netherite Ingot Key','Prismarine Shard Key','Quartz Key','Gold Ingot Key','Emerald Key','Diamond Key'];
class PortProbe extends PartyProbe {
  constructor(options={}){super({port:25656,runDir:path.join(__dirname,'../build/mygo-party2-port-priority'),...options});this.found={};this.target={};this.history={};this.framePackets=[];this.rules=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/pvp/party/port_priority_rules.json'),'utf8'));}
  connect(id){const b=super.connect(id);this.found[id]=0;this.history[id]=[];
    b.on('messagestr',message=>{const text=String(message).replace(/§./g,'');const progress=text.match(/已找到 (\d)\/3 件物品/);if(progress){this.found[id]=Number(progress[1]);this.log('nativeProgress',{id,count:this.found[id],text});}
      const target=text.match(/下一个物品：(.+)/);if(target){this.target[id]=LABELS.findIndex(label=>label===target[1].trim());this.history[id].push(this.target[id]);this.log('nativeTarget',{id,item:this.target[id],text});}});
    b.on('entitySpawn',e=>{if(e.name==='item_frame')this.framePackets.push({id,entityId:e.id,position:e.position});});return b;
  }
  async condition(condition){const token=`P2_PP_${++this.sequence}`;await this.console(`execute ${condition} run say ${token}`,25);return this.serverLines.some(r=>r.text.includes('] '+token));}
  async sprint(ticks){const n=this.serverLines.length;this.log('fixtureNote',{description:`Advance${ticks} real server ticks, without editing game time or progress.`});this.rawConsole(`tick sprint ${ticks}`);await this.wait(()=>this.serverLines.slice(n).some(r=>/Sprint completed/i.test(r.text)),'native tick sprint complete',60000);await sleep(70);}
  async resetLobby(){this.log('fixtureNote',{description:'Actual hub commands and server-side Overworld confirmation precede every queue entry; persistent playerdata is not assumed clean.'});await this.console('execute as @a run hub');await this.wait(()=>Object.values(this.bots).every(b=>b.probeWorld==='minecraft:overworld'),'real hub dimension',20000);
    for(const id of Object.keys(this.bots))this.check(`${id} server-side baseline is Overworld`,await this.condition(`as ${name(id)} at @s if dimension minecraft:overworld`));}
  async startPort(){this.game='port';for(const id of Object.keys(this.bots)){this.found[id]=0;this.history[id]=[];}const since=Date.now();for(const b of Object.values(this.bots))b.probeStartedAt=0;
    for(const id of Object.keys(this.bots))await this.chat(id,'/pvp join minecraftparty');
    await this.wait(()=>{if(this.voteError)throw this.voteError;return this.chats.some(r=>r.at>=since&&/第\s+\d+\/5\s+轮选择：/.test(r.text)&&r.text.includes('编号 421'));},'real421 menu selection',90000);
    for(const b of Object.values(this.bots))b.probeStartedAt=0;const selected=Date.now();
    await this.wait(()=>Object.values(this.bots).every(b=>b.probeStartedAt>=selected),'full1.74m harbor reaches PLAY',120000);
    this.game=null;const center=Object.values(this.bots).reduce((sum,b)=>sum.plus(b.probePosition),new Vec3(0,0,0)).scaled(1/Object.keys(this.bots).length);const offset=center.minus(new Vec3(-367.5,36,-307.5));this.source=(x,y,z)=>offset.offset(x,y,z);
    this.log('gameStarted',{game:'port',center,offset,players:Object.keys(this.bots).map(id=>this.snapshot(id))});return since;
  }
  async clickBlock(id,point){this.bots[id]._client.write('block_place',{hand:0,location:point,direction:5,cursorX:1,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence});await sleep(100);}
  async forceState(point){const objective='p2_pp_forced';await this.console(`scoreboard objectives add ${objective} dummy`);await this.console(`execute in pvp:arena store success score #query ${objective} run forceload query ${Math.floor(point.x)} ${Math.floor(point.z)}`);return this.condition(`if score #query ${objective} matches 1`);}
  async liveBlockIs(point,block){return this.condition(`in pvp:arena if block ${Math.floor(point.x)} ${Math.floor(point.y)} ${Math.floor(point.z)} minecraft:${block}`);}
  async discover(){this.sites=[];
    for(const entry of this.rules.sites){const p=this.source(...entry.position);if(await this.liveBlockIs(p,'beacon'))this.sites.push({position:p,facing:entry.facing});}
    this.check('Exactly7 of the12 source candidates contain real native beacons',this.sites.length===7,{sites:this.sites});
    this.byItem={};for(const site of this.sites){for(let item=0;item<7;item++)if(await this.condition(`in pvp:arena if entity @e[type=minecraft:item_frame,x=${site.position.x},y=${site.position.y+2},z=${site.position.z},distance=..3,nbt={Item:{id:"minecraft:${ITEMS[item]}"},Fixed:1b,Invisible:1b}]`)){site.item=item;this.byItem[item]=site;break;}}
    this.check('Seven actual frame/chest sites show seven different native items',Object.keys(this.byItem).length===7,this.sites);
    await this.console('scoreboard objectives add p2_pp_entities dummy');await this.console('execute in pvp:arena store result score #frames p2_pp_entities if entity @e[type=minecraft:item_frame,nbt={Fixed:1b,Invisible:1b}]');
    this.check('All28 real fixed item-frame displays exist',await this.condition('if score #frames p2_pp_entities matches 28'));
    // Audited original cell: crop.min is coal ore; use a verified stone seafloor witness.
    this.marker=this.source(-500,-64,-400);await this.pinChunk(this.marker);this.check('Original deep seafloor exists before cleanup',await this.serverBlockIs(this.marker,'stone'));
    const sign=this.source(-374,28,-370);this.check('Engine-room navigation text survives the native sign projection',await this.condition(`in pvp:arena if data block ${sign.x} ${sign.y} ${sign.z} front_text.messages`));
    const shortcut=this.source(-308,30,-239);await this.pinChunk(shortcut);this.check('Source unlocked southern chest retains its actual diamond',await this.condition(`in pvp:arena if data block ${shortcut.x} ${shortcut.y} ${shortcut.z} Items[{id:"minecraft:diamond"}]`));
    const head=this.source(-501,-33,-487);await this.pinChunk(head);this.check('Original custom gold-block head retains its native texture profile',await this.condition(`in pvp:arena if data block ${head.x} ${head.y} ${head.z} profile.properties[{name:"textures"}]`));
    this.check('Falling and PvP safety are scoped without removing native movement effects',await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:speed",amplifier:3b},{id:"minecraft:water_breathing"}]}]'));
  }
  async equipKey(id){const b=this.bots[id],target=this.target[id];
    // prismarine-item.equal compares legacy nbt but ignores1.21 components;
    // closeWindow copyInventory can therefore retain the old literal key name.
    // Ask the real server for its inventory rather than fabricate a client item.
    await b._syncWindow(b.inventory);
    try{await this.wait(()=>b.inventory.items().some(s=>s.name==='tripwire_hook'&&JSON.stringify(s).includes(KEYS[target])),`${id} native current key`,3000);}
    catch(error){await this.console(`data get entity ${name(id)} Inventory`);this.log('nativeInventoryDiagnostic',{id,target,slots:b.inventory.items(),window:b.currentWindow?.id});throw error;}
    await b.equip(b.inventory.items().find(s=>s.name==='tripwire_hook'&&JSON.stringify(s).includes(KEYS[target])),'hand');}
  async open(id,site){const b=this.bots[id];if(b.currentWindow)b.closeWindow(b.currentWindow);await this.equipKey(id);await this.teleport(id,site.position.offset(2.4,1,.5));const chest=site.position.offset(0,1,0);await this.wait(()=>b.blockAt(chest)?.name==='chest','native chest chunk is visible',5000);await this.clickBlock(id,chest);await this.wait(()=>!!b.currentWindow,'correct literal key opens native chest GUI',5000);return b.currentWindow;}
  async take(id,cursorProbe=false){const b=this.bots[id],before=this.found[id],item=this.target[id],site=this.byItem[item];const window=await this.open(id,site);
    this.check(`${id} target is in real chest slot13`,window.slots[13]?.name===ITEMS[item]&&window.slots[13].count===1,{item,slot:window.slots[13]?.name,window:window.type});
    this.check('Source quest chest has no alternative loot slots',window.slots.slice(0,27).filter(Boolean).length===1);
    if(cursorProbe){const windowId=window.id;await b.clickWindow(13,0,0);await sleep(180);if(b.currentWindow)await b._syncWindow(b.currentWindow);
      this.check('Taking into the cursor does not award inventory-based progress',this.found[id]===before);
      this.check('Native clear removes an uncollected cursor item and refills slot13 in the same live GUI',b.currentWindow?.id===windowId&&b.currentWindow.slots[13]?.name===ITEMS[item]&&!b.currentWindow.selectedItem,{windowId,current:b.currentWindow?.id,slot:b.currentWindow?.slots[13]?.name,cursor:b.currentWindow?.selectedItem});
    }
    this.log('nativeChestTransfer',{id,item,slot:13,kind:'shift-click'});await b.clickWindow(13,0,1);await this.wait(()=>this.found[id]===before+1,`${id} actual inventory goal advances`,5000);
    this.check(`${id} advances exactly one quest on a real GUI transfer`,this.found[id]===before+1,{count:this.found[id]});
    if(before<2)this.check('Refilling preserves the open native chest ScreenHandler',b.currentWindow?.id===window.id,{before:window.id,after:b.currentWindow?.id});
    if(b.currentWindow)b.closeWindow(b.currentWindow);await sleep(100);
  }
  async wrongKey(){const target=this.target.A,wrong=this.sites.find(s=>s.item!==target);await this.equipKey('A');await this.teleport('A',wrong.position.offset(2.4,1,.5));await this.clickBlock('A',wrong.position.offset(0,1,0));await sleep(220);
    this.check('A wrong native key fails to open a different chest',!this.bots.A.currentWindow,{target,wrong:wrong.item});}
  async terrain(){this.log('fixtureNote',{description:'Install a tiny native lever/piston and isolated water source in originally-air cells to verify actual world changes and cleanup. They are not source game geometry or a route traversal.'});
    this.piston=this.source(-510,52,-490);const lever=this.piston.offset(-1,0,0),stone=this.piston.offset(1,0,0);this.pushed=this.piston.offset(2,0,0);
    await this.pinChunk(this.piston);
    this.check('Piston destination is genuinely original air',await this.serverBlockIs(this.pushed,'air'));
    await this.console(`execute in pvp:arena run fill ${lever.x} ${lever.y-1} ${lever.z-1} ${stone.x} ${stone.y-1} ${stone.z+1} stone`);
    await this.console(`execute in pvp:arena run setblock ${this.piston.x} ${this.piston.y} ${this.piston.z} sticky_piston[facing=east]`);
    await this.console(`execute in pvp:arena run setblock ${stone.x} ${stone.y} ${stone.z} stone`);
    await this.console(`execute in pvp:arena run setblock ${lever.x} ${lever.y} ${lever.z} lever[face=floor,facing=east,powered=false]`);
    await this.teleport('D',lever.offset(-1,0,.5));await this.clickBlock('D',lever);this.check('Actual player lever use drives a native piston into an originally-air cell',await this.waitServerBlock(this.pushed,'stone'));
    const water=this.source(-506,52,-486);this.flowed=water.offset(0,-1,0);this.check('Fluid destination is original air',await this.serverBlockIs(this.flowed,'air'));
    await this.console(`execute in pvp:arena run setblock ${water.x} ${water.y} ${water.z} water`);this.check('Scoped native fluid actually flows into a new cell',await this.waitServerBlock(this.flowed,'water'));
    await this.pinChunk(this.pushed);
  }
  async run(){await this.resetLobby();await this.console('execute in pvp:arena run forceload add 8192 8192');const since=await this.startPort();await this.discover();await this.wrongKey();await this.terrain();
    const firstType=this.target.A;await this.take('A',true);const completed=this.found.A;
    this.log('fixtureNote',{description:'Native generic_kill death observes the source central respawn and retained items/progress; no objective or score is changed.'});await this.console('damage MyGoCheckA 100 minecraft:generic_kill');await sleep(160);
    this.check('Death retains found count and returns to the source center',this.found.A===completed&&this.bots.A.probePosition.distanceTo(this.source(-367.5,36,-307.5))<.3,{found:this.found.A,position:this.bots.A.probePosition});
    this.check('Already-collected goods remain available after death',this.bots.A.inventory.items().some(s=>s.name===ITEMS[firstType]));
    await this.take('A');await this.take('A');this.check('One of four finishers does not end the source round',!this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)));
    for(let i=0;i<3;i++)await this.take('B');for(let i=0;i<2;i++)await this.take('D');for(let i=0;i<3;i++)await this.take('C');
    await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'N-1 native finishers settle',5000);
    this.check('Exactly N-1 finishes leave the last participant unfinished',this.found.A===3&&this.found.B===3&&this.found.C===3&&this.found.D===2,this.found);
    this.check('All players receive the same3 targets in distinct source orders',new Set(Object.values(this.history).map(h=>h.join(','))).size===4&&Object.values(this.history).every(h=>h.length===3&&new Set(h).size===3&&h.every(i=>this.history.A.includes(i))),this.history);
    await this.wait(()=>!!this.bots.A.currentWindow,'cleanup finishes and five-round voting returns',120000);
    this.check('Next-round menu excludes source421',!this.menuChoices(this.bots.A.currentWindow).some(c=>c.source===421));
    this.check('Deep original seafloor is removed in a pinned loaded chunk',await this.waitServerBlock(this.marker,'air'));
    this.check('Native piston destination leaves no residual block',await this.waitServerBlock(this.pushed,'air'));
    this.check('Native fluid destination leaves no residual water',await this.waitServerBlock(this.flowed,'air'));
    for(const site of this.sites)this.check('Only module-acquired forced beacon chunks are released',!await this.forceState(site.position),site.position);
    this.check('Unrelated pre-existing forced chunk remains forced',await this.forceState(new Vec3(8192,0,8192)));
    await this.console('execute in pvp:arena run forceload remove 8192 8192');await this.leaveAll();await this.unpinChunks();
  }
}
async function main(){const p=new PortProbe();let error;try{await p.launch();await p.connectAll('ABCD');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={PortProbe};
