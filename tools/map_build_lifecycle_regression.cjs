'use strict';

// Reason: a slow completed map lost its COUNTDOWN watchdog allowance. The
// disposable observer ages only elapsed clocks, then real packets and ticks
// must complete build -> countdown -> ACTIVE -> winner -> return/cleanup.
const path = require('node:path');
const readline = require('node:readline');
const { spawn } = require('node:child_process');
const { PartyProbe } = require('./party2_protocol_regression.cjs');

class MapBuildLifecycleProbe extends PartyProbe {
  constructor() {
    super({ port: Number(process.env.MYGO_TEST_PORT || 25704),
      runDir: process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-map-build-lifecycle') });
    this.native = null;
  }
  async launch() {
    this.log('environment', { root: this.root, runDir: this.runDir, port: this.port,
      minecraft: '1.21.1', headless: true, rendered: false });
    this.server = spawn('python3', [path.join(this.root, 'tools/map_build_lifecycle_server.py'),
      '--port', String(this.port), '--run-dir', this.runDir],
    { cwd: this.root, stdio: ['pipe', 'pipe', 'pipe'] });
    for (const [stream, kind] of [[this.server.stdout, 'stdout'], [this.server.stderr, 'stderr']]) {
      readline.createInterface({ input: stream }).on('line', line => {
        const row = { at: Date.now(), stream: kind, text: line };
        this.serverLines.push(row); this.log('server', row);
        const marker = line.indexOf('MAP_BUILD_LIFECYCLE ');
        if (marker >= 0) {
          this.native = JSON.parse(line.slice(marker + 'MAP_BUILD_LIFECYCLE '.length));
          this.log('nativeObservation', this.native);
        }
      });
    }
    this.server.once('exit', (code, signal) => { this.serverExit = { code, signal }; this.log('serverExit', this.serverExit); });
    await this.wait(() => this.serverLines.some(row => /Done \([\d.]+s\)!/.test(row.text)), 'Fabric server ready', 120000);
  }
  async scenario() {
    await this.connectAll('AB');
    for (const id of ['A', 'B']) await this.chat(id, '/pvp join skywars');
    await this.chat('A', '/pvp start OVERWORLD');
    await this.wait(() => this.native?.phase === 'clockFixture' || this.native?.phase === 'failed',
      'actual SkyWars build completes and the explicit slow-clock fixture applies', 120000);
    this.check('The isolated native observer applied the labeled clock fixture', this.native.ok, this.native);
    await this.wait(() => this.native?.phase === 'active' || this.native?.phase === 'failed',
      'post-build countdown reaches ACTIVE without cancellation', 30000);
    this.check('Slow-build clock fixture preserves real ACTIVE gameplay', this.native.ok && this.native.phase === 'active', this.native);
    this.check('Both protocol players received the arena world', ['A', 'B'].every(id => this.bots[id].probeWorld === 'pvp:arena'),
      ['A', 'B'].map(id => this.snapshot(id)));
    for (const bot of Object.values(this.bots)) if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
    const since = Date.now();
    await this.chat('B', '/hub');
    await this.wait(() => this.chats.some(row => row.id === 'A' && row.at >= since && row.text.includes('你赢了')),
      'real winner message after B leaves', 15000);
    await this.wait(() => this.native?.complete || this.native?.phase === 'failed', 'real finish and return lifecycle', 15000);
    this.check('Native checks all passed through normal match finish', this.native.ok && this.native.complete
      && this.native.checks.every(row => row.pass), this.native);
    await this.wait(() => ['A', 'B'].every(id => this.bots[id].probeWorld === 'minecraft:overworld'), 'both clients back in lobby', 15000);
    await this.wait(() => this.serverLines.some(row => /比赛 #\d+ 已结束并清理/.test(row.text)), 'actual deferred arena cleanup', 60000);
    this.check('Deferred map cleanup completed without a watchdog cancellation',
      !this.serverLines.some(row => /倒计时异常，强制取消|铺图超时兜底/.test(row.text)));
  }
}

async function main() {
  const probe = new MapBuildLifecycleProbe(); let error;
  try { await probe.launch(); await probe.scenario(); }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
