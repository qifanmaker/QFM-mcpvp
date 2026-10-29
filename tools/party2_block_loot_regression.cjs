'use strict';

// Reason: source doTileDrops differs between harvesting and building games.
// Review: only this isolated Fabric25646 world is mutated. Native setblock
// destroy fixtures exercise Block item/XP paths with both host-rule values;
// out-of-region controls prove that no shared world gamerule was overwritten.
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const { Vec3 } = require('vec3');
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

class BlockLootProbe extends PartyProbe {
  constructor() {
    super({ port: 25646, runDir: path.join(__dirname, '../build/mygo-party2-block-loot') });
  }
  async launch() {
    this.log('fixtureNote', { description: 'Compile commands only into the disposable snapshot to set independent arena gamerules and invoke native directional dropStack.' });
    this.server = spawn('python3', [path.join(this.root, 'tools/party2_block_loot_server.py'),
      '--port', String(this.port), '--run-dir', this.runDir], { cwd: this.root, stdio: ['pipe', 'pipe', 'pipe'] });
    for (const [stream, kind] of [[this.server.stdout, 'stdout'], [this.server.stderr, 'stderr']]) {
      readline.createInterface({ input: stream }).on('line', line => {
        const row = { at: Date.now(), stream: kind, text: line };
        this.serverLines.push(row); this.log('server', row);
      });
    }
    this.server.once('exit', (code, signal) => { this.serverExit = { code, signal }; this.log('serverExit', this.serverExit); });
    await this.wait(() => this.serverLines.some(row => /Done \([\d.]+s\)!/.test(row.text)), 'Fabric server ready', 120000);
  }
  async condition(clause) {
    const token = `P2_BLOCK_LOOT_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`, 10);
    const spoken = new RegExp(`\\]\\s+${token}\\s*$`);
    return this.serverLines.some(row => spoken.test(row.text)) || this.chats.some(row => spoken.test(row.text));
  }
  async drops(point, allowed, label) {
    const { x, y, z } = point;
    const nearby = `x=${x},y=${y},z=${z},distance=..4`;
    await this.console(`execute in pvp:arena run kill @e[type=item,${nearby}]`);
    await this.console(`execute in pvp:arena run kill @e[type=experience_orb,${nearby}]`);
    await this.console(`execute in pvp:arena run p2lootdirection ${x} ${y} ${z}`, 10);
    this.check(`${label}: native directional drops ${allowed ? 'enabled' : 'suppressed'}`,
      await this.condition(`in pvp:arena ${allowed ? 'if' : 'unless'} entity @e[type=item,${nearby}]`));
    await this.console(`execute in pvp:arena run kill @e[type=item,${nearby}]`);
    await this.console(`execute in pvp:arena run setblock ${x} ${y} ${z} minecraft:diamond_ore`);
    await this.console(`execute in pvp:arena run setblock ${x} ${y} ${z} minecraft:air destroy`, 10);
    this.check(`${label}: native item drops ${allowed ? 'enabled' : 'suppressed'}`,
      await this.condition(`in pvp:arena ${allowed ? 'if' : 'unless'} entity @e[type=item,${nearby}]`));
    this.check(`${label}: native experience drops ${allowed ? 'enabled' : 'suppressed'}`,
      await this.condition(`in pvp:arena ${allowed ? 'if' : 'unless'} entity @e[type=experience_orb,${nearby}]`));
    await this.console(`execute in pvp:arena run kill @e[type=item,${nearby}]`);
    await this.console(`execute in pvp:arena run kill @e[type=experience_orb,${nearby}]`);
  }
  async run() {
    await this.launch();
    await this.connectAll('ABCD');
    await this.start('tedious');
    const position = this.bots.A.probePosition;
    const inside = new Vec3(Math.floor(position.x), 250, Math.floor(position.z));
    // This isolated server allocates arenas at nonnegative X. The negative-X
    // fixture stays outside every Party region even when a later match reallocates.
    const outside = new Vec3(-1000, 250, 88);
    await this.pinChunk(inside);
    await this.pinChunk(outside);
    await this.console('execute in pvp:arena run p2lootrule false');
    await this.drops(inside, true, '411 harvest overrides false host rule');
    await this.drops(outside, false, 'Outside Party preserves false host rule');
    await this.console('execute in pvp:arena run p2lootrule true');
    await this.drops(outside, true, 'Outside Party preserves true host rule');
    await this.leaveAll();
    await this.start('speed');
    const builder = this.bots.A.probePosition;
    const buildInside = new Vec3(Math.floor(builder.x), 250, Math.floor(builder.z));
    await this.pinChunk(buildInside);
    await this.drops(buildInside, false, '417 suppresses native loot with true host rule');
    await this.drops(outside, true, '417 leaves outside loot enabled');
    await this.console('execute in pvp:arena run p2lootrule false');
    await this.drops(buildInside, false, '417 suppresses native loot with false host rule');
    await this.drops(outside, false, '417 leaves outside loot disabled');
    await this.leaveAll();
    await this.drops(inside, false, 'Ended Party region returns to false host rule');
    await this.console('execute in pvp:arena run p2lootrule true');
    await this.drops(inside, true, 'Ended Party region returns to true host rule');
    await this.unpinChunks();
    await sleep(50);
  }
}

async function main() {
  const probe = new BlockLootProbe();
  let error;
  try { await probe.run(); }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
