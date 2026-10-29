'use strict';

// Reason: Party 2 needs repeatable checks against real Fabric/player packets,
// particularly individual Memory Lane buttons and Runner's timed world changes.
// Pre-review: isolate the runtime/classpath, never touch 25565, retain failed
// evidence, and distinguish console-arranged fixtures from ordinary gameplay.
// This is a headless protocol check, not a rendered-client acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { spawn } = require('node:child_process');
const { parseArgs } = require('node:util');
const { EventEmitter } = require('node:events');
const mineflayer = require('mineflayer'); // Resolve through NODE_PATH; no repo npm dependencies.
const { Vec3 } = require('vec3');

const GAMES = {
  spleef: { slot: 0, source: 408 }, tile: { slot: 1, source: 412 }, rlgl: { slot: 2, source: 424 },
  dropper: { slot: 3, source: 401 }, rainbow: { slot: 4, source: 413 },
  runner: { slot: 5, source: 415 }, memory: { slot: 6, source: 425 },
  meat: { slot: 7, source: 409 }, oitq: { slot: 8, source: 428 },
  parkour: { source: 404 }, platforms: { source: 414, players: 4 }
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const json = value => JSON.stringify(value, (_, item) => typeof item === 'bigint' ? item.toString() : item);
const clean = text => String(text).replace(/§./g, '');
const vec = position => new Vec3(position.x, position.y, position.z);

class PartyProbe extends EventEmitter {
  constructor(options = {}) {
    super();
    this.root = path.resolve(__dirname, '..');
    this.port = Number(options.port || 25586);
    this.runDir = path.resolve(options.runDir || path.join(this.root, 'build/mygo-party2-validation'));
    if (this.port === 25565 || this.port < 1024 || this.port > 65535 || !Number.isInteger(this.port)
        || !this.runDir.startsWith(path.join(this.root, 'build') + path.sep)
        || !path.basename(this.runDir).includes('mygo')) throw new Error('Use a mygo-named build/ directory and a separate test port.');
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    this.evidenceDir = path.join(this.runDir, `party2-evidence-${stamp}`);
    fs.mkdirSync(this.evidenceDir, { recursive: true });
    this.evidenceFile = path.join(this.evidenceDir, 'events.jsonl');
    this.bots = {}; this.chats = []; this.serverLines = []; this.checks = [];
    this.sequence = 0; this.game = null; this.fixtures = new Map(); this.pinnedChunks = new Map();
    this.attach = !!options.attach; this.closing = false;
  }

  log(event, detail = {}) {
    const row = { at: Date.now(), time: new Date().toISOString(), event, ...detail };
    fs.appendFileSync(this.evidenceFile, json(row) + '\n');
    if (!['server', 'fixtureTeleport', 'actionBar', 'position'].includes(event)) process.stdout.write(json(row) + '\n');
    this.emit('evidence', row);
    return row;
  }

  check(name, pass, actual, expected) {
    const result = { name, pass: !!pass, actual, expected };
    this.checks.push(result); this.log('assertion', result);
    if (!pass) throw new Error(`Assertion failed: ${name}`);
  }

  async wait(predicate, label, timeout = 15000) {
    const deadline = Date.now() + timeout;
    while (!predicate()) {
      if (this.serverExit && !this.closing) throw new Error(`Server exited: ${json(this.serverExit)}`);
      if (Date.now() > deadline) throw new Error(`Timeout: ${label}`);
      await sleep(40);
    }
  }

  async launch() {
    this.log('environment', { root: this.root, runDir: this.runDir, port: this.port, attach: this.attach,
      minecraft: '1.21.1', headless: true, rendered: false });
    if (this.attach) return;
    this.server = spawn('python3', [path.join(this.root, 'tools/mygo_test_server.py'),
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

  connect(id) {
    if (!/^[A-F]$/.test(id)) throw new Error('Prepared operator names are MyGoCheckA..F.');
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: this.port, username: `MyGoCheck${id}`,
      version: '1.21.1', auth: 'offline', hideErrors: false });
    this.bots[id] = bot; bot.probeId = id;
    bot.probeBlockUpdates = new Map();
    bot.on('blockUpdate', (previous, current) => {
      if (current?.position) {
        bot.probeBlockUpdates.set(current.position.toString(), { name: current.name, at: Date.now(), stateId: current.stateId });
      }
    });
    let authoritative;
    const originalWrite = bot._client.write.bind(bot._client);
    bot.probeWrite = originalWrite;
    bot._client.write = (name, packet) => {
      if (bot.probeAllowMovement && (name === 'position' || name === 'position_look')) {
        authoritative = new Vec3(packet.x, packet.y, packet.z);
        return originalWrite(name, packet);
      }
      // Do not replay stale Mineflayer movement after a cross-world teleport.
      if (authoritative && (name === 'position' || name === 'position_look')) {
        if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
        // teleport_confirm already acknowledges the server position. Replacing
        // delayed position packets with look/flying still invokes the server's
        // movement validator in the teleport tick and can cause a reply loop.
        return;
      }
      return originalWrite(name, packet);
    };
    bot._client.prependListener('position', packet => {
      if (bot.probeJumpUntilTeleport) {
        bot.probeJumpUntilTeleport = false;
        bot.probeAllowMovement = false;
        bot.physicsEnabled = false;
        bot.setControlState('jump', false);
      }
      const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
      const relative = axis => typeof packet.flags === 'object' ? packet.flags[axis]
        : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
      authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
        (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
      bot.probePosition = authoritative; this.log('position', { id, position: authoritative });
      // Send one current position after the teleport tick so chunk tracking
      // advances, without acknowledging a correction recursively in that tick.
      const teleport = bot.probeTeleportGeneration = (bot.probeTeleportGeneration || 0) + 1;
      setTimeout(() => {
        if (bot.probeTeleportGeneration === teleport && !bot.probeAllowMovement && !this.closing) {
          originalWrite('position', { x: authoritative.x, y: authoritative.y, z: authoritative.z, onGround: true });
        }
      }, 100);
    });
    const world = packet => {
      const state = packet.worldState || packet;
      bot.probeWorld = state.name ?? state.worldName ?? packet.worldName;
      this.log('world', { id, world: bot.probeWorld });
    };
    bot._client.prependListener('login', world); bot._client.prependListener('respawn', world);
    bot._client.on('set_title_text', packet => {
      this.log('title', { id, packet });
      if (json(packet).includes('开始')) bot.probeStartedAt = Date.now();
    });
    bot.on('spawn', () => { bot.physicsEnabled = false; bot.probeSpawned = true; this.log('spawn', this.snapshot(id)); });
    bot.on('messagestr', (message, position) => {
      const row = { id, text: clean(message), at: Date.now() }; this.chats.push(row);
      this.log(position === 'game_info' ? 'actionBar' : 'chat', row);
    });
    bot.on('windowOpen', window => {
      this.log('window', { id, title: window.title, slots: window.slots.slice(0, 54).map(item => item && item.name) });
      if (this.game && json(window.title).includes('Party')) {
        void this.vote(id, this.game).catch(error => { this.voteError = error; this.log('voteError', { id, error: error.stack }); });
      }
    });
    bot.on('error', error => this.log('clientError', { id, error: error.stack }));
    bot.on('kicked', reason => { bot.probeKicked = reason; this.log('kicked', { id, reason }); });
    bot.on('end', reason => { bot.probeSpawned = false; this.log('clientEnd', { id, reason }); });
    return bot;
  }

  async connectAll(ids = 'ABCD') {
    for (const id of ids) this.connect(id);
    await this.wait(() => [...ids].every(id => this.bots[id].probeSpawned), 'all clients spawned', 30000);
    await sleep(300);
  }

  snapshot(id) {
    const bot = this.bots[id];
    return { id, position: bot.probePosition || bot.entity?.position, world: bot.probeWorld,
      mode: bot.game?.gameMode, health: bot.health, held: bot.heldItem?.name,
      inventory: bot.inventory?.items().map(item => ({ name: item.name, count: item.count, slot: item.slot })),
      window: bot.currentWindow?.title };
  }

  async chat(id, command, delay = 80) {
    this.log('command', { id, command }); this.bots[id].chat(command); await sleep(delay);
  }

  rawConsole(command) {
    if (this.server) this.server.stdin.write(command.replace(/^\//, '') + '\n');
    else if (this.bots.A?.probeSpawned) this.bots.A.chat('/' + command.replace(/^\//, ''));
    else throw new Error('Console requires the owned server or an attached operator client A.');
  }

  async console(command, delay = 40) {
    const token = `P2_ACK_${++this.sequence}`;
    this.log('console', { command, token });
    this.rawConsole(command); this.rawConsole(`say ${token}`);
    await this.wait(() => this.serverLines.some(row => row.text.includes(token))
      || this.chats.some(row => row.text.includes(token)), `console acknowledgement ${token}`, 5000);
    if (delay) await sleep(delay);
  }

  async vote(id, game) {
    if (!GAMES[game]) throw new Error(`Unknown game ${game}`);
    const bot = this.bots[id];
    await this.wait(() => !!bot.currentWindow, `${id} voting menu`);
    // Five-round menus omit played entries, so enum ordinals stop matching
    // their slots. Read the actual server item lore before every vote.
    await this.wait(() => this.menuChoices(bot.currentWindow).length > 0, `${id} voting lore received`, 2500);
    const choice = this.menuChoices(bot.currentWindow).find(item => item.source === GAMES[game].source);
    if (!choice) throw new Error(`${game} is absent from the current unplayed-game menu`);
    this.log('vote', { id, game, slot: choice.slot, source: choice.source });
    await bot.clickWindow(choice.slot, 0, 0);
  }

  menuChoices(window) {
    return (window?.slots || []).slice(0, 45).flatMap((item, slot) => {
      if (!item) return [];
      const lore = clean(json(item.customLore ?? item.components ?? item.nbt));
      const source = lore.match(/原版编号\s+(\d+)/);
      return source ? [{ slot, source: Number(source[1]), item: item.name, lore }] : [];
    });
  }

  async start(game) {
    if (!GAMES[game]) throw new Error(`Unknown game ${game}`);
    this.game = game; this.voteError = null;
    const since = Date.now();
    for (const [id, bot] of Object.entries(this.bots)) {
      bot.probeStartedAt = 0;
      await this.chat(id, '/pvp join minecraftparty');
    }
    await this.wait(() => {
      if (this.voteError) throw this.voteError;
      return this.chats.some(row => row.at >= since && /第\s+\d+\/5\s+轮选择：/.test(row.text)
        && row.text.includes(`编号 ${GAMES[game].source}`));
    }, `${game} selected via real menu votes`, 90000);
    // Match may also send a generic start title before the voting phase. Only
    // the title after selection belongs to this minigame's PLAY transition.
    for (const bot of Object.values(this.bots)) bot.probeStartedAt = 0;
    const selectedAt = Date.now();
    await this.wait(() => Object.values(this.bots).every(bot => bot.probeStartedAt >= selectedAt), `${game} begins`, 45000);
    this.log('gameStarted', { game, players: Object.keys(this.bots).map(id => this.snapshot(id)) });
    this.game = null;
    return since;
  }

  block(id, position) { return this.bots[id].blockAt(vec(position)); }

  async click(id, position) {
    const block = this.block(id, position);
    if (!block) throw new Error(`Unloaded block: ${json(position)}`);
    this.log('blockClick', { id, position, block: block.name, player: this.snapshot(id),
      state: block.getProperties(), wall: this.block(id, vec(position).offset(1, 0, 0))?.name });
    this.bots[id]._client.write('block_place', { hand: 0, location: vec(position), direction: 4,
      cursorX: 0, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: ++this.sequence });
    this.bots[id].swingArm('right');
    await sleep(90);
  }

  async jump(id) {
    const bot = this.bots[id];
    this.log('jumpInput', { id, position: bot.probePosition });
    bot.probeJumpUntilTeleport = true;
    bot.probeAllowMovement = true; bot.physicsEnabled = true;
    bot.setControlState('jump', true);
    await sleep(180);
    bot.setControlState('jump', false);
    await sleep(150);
    bot.physicsEnabled = false; bot.probeAllowMovement = false;
  }

  async teleport(id, position) {
    await this.console(`execute in pvp:arena run tp MyGoCheck${id} ${position.x} ${position.y} ${position.z}`);
  }

  orbit(id, center, radius = 0.6) {
    this.fixtures.set(id, { center: vec(center), radius, started: Date.now() });
    if (this.fixtureTimer) return;
    this.log('fixtureNote', { description: 'Console teleport orbit keeps selected opponents active while isolating other rules. It does not validate normal sprint movement.' });
    this.fixtureTimer = setInterval(() => {
      for (const [who, fixture] of this.fixtures) {
        const bot = this.bots[who];
        if (bot.game?.gameMode !== 'adventure') continue;
        const angle = (Date.now() - fixture.started) * 0.002;
        const p = fixture.center.offset(Math.cos(angle) * fixture.radius, 0, Math.sin(angle) * fixture.radius);
        this.log('fixtureTeleport', { id: who, position: p });
        this.rawConsole(`execute in pvp:arena run tp MyGoCheck${who} ${p.x} ${p.y} ${p.z}`);
      }
    }, 120);
  }

  stopOrbits() {
    clearInterval(this.fixtureTimer); this.fixtureTimer = null; this.fixtures.clear();
  }

  async pinChunk(position) {
    const key = `${Math.floor(position.x / 16)},${Math.floor(position.z / 16)}`;
    if (this.pinnedChunks.has(key)) return;
    await this.console(`execute in pvp:arena run forceload add ${Math.floor(position.x)} ${Math.floor(position.z)}`);
    this.pinnedChunks.set(key, vec(position));
    await sleep(100);
    const token = `P2_LOADED_${++this.sequence}`;
    await this.console(`execute in pvp:arena if loaded ${Math.floor(position.x)} ${Math.floor(position.y)} ${Math.floor(position.z)} run say ${token}`);
    this.check('Test chunk remains loaded for direct world assertions', this.serverLines.some(row => row.text.includes(token))
      || this.chats.some(row => row.text.includes(token)), { key, position });
    this.log('chunkPinned', { key, position });
  }

  async unpinChunks() {
    for (const [key, position] of this.pinnedChunks) {
      await this.console(`execute in pvp:arena run forceload remove ${Math.floor(position.x)} ${Math.floor(position.z)}`);
      this.log('chunkUnpinned', { key, position });
      this.pinnedChunks.delete(key);
    }
  }

  async serverBlockIs(position, block) {
    const key = `${Math.floor(position.x / 16)},${Math.floor(position.z / 16)}`;
    if (!this.pinnedChunks.has(key)) throw new Error('Pin the observed live-game chunk before teardown; never reload an empty chunk to claim cleanup.');
    const token = `P2_BLOCK_${++this.sequence}`;
    await this.console(`execute in pvp:arena if block ${Math.floor(position.x)} ${Math.floor(position.y)} ${Math.floor(position.z)} minecraft:${block} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(token)) || this.chats.some(row => row.text.includes(token));
  }

  async waitServerBlock(position, block, timeout = 25000) {
    // Normal round cleanup follows its results page. Test cases that leave
    // together cancel the whole tournament and use Match's deferred cleanup.
    const deadline = Date.now() + timeout;
    do {
      if (await this.serverBlockIs(position, block)) return true;
      await sleep(300);
    } while (Date.now() < deadline);
    return false;
  }

  async noFallingBlocks() {
    const token = `P2_ENTITY_${++this.sequence}`;
    await this.console(`execute in pvp:arena if entity @e[type=minecraft:falling_block] run say ${token}`);
    return !this.serverLines.some(row => row.text.includes(token)) && !this.chats.some(row => row.text.includes(token));
  }

  async leaveAll() {
    this.stopOrbits(); this.game = null;
    // Vanilla tick freeze does not stop the mod's end-server-tick callback.
    // Run every bot's real /hub handler in one command execution instead, so
    // there is no intermediate one-player tournament tick. Tag only our bots.
    const tag = `p2_probe_leave_${++this.sequence}`;
    for (const id of Object.keys(this.bots)) await this.console(`tag MyGoCheck${id} add ${tag}`);
    try {
      await this.console(`execute as @a[tag=${tag}] run hub`);
      await this.wait(() => Object.values(this.bots).every(bot => bot.probeWorld !== 'pvp:arena'), 'players returned from arena', 25000);
    } finally {
      await this.console(`tag @a[tag=${tag}] remove ${tag}`);
    }
    await sleep(1500);
  }

  async memory() {
    const since = await this.start('memory');
    const initial = vec(this.bots.A.probePosition);
    const pattern = [];
    const reference = cell => new Vec3(Math.floor(initial.x + 3.5), Math.floor(initial.y) + Math.floor(cell / 5),
      Math.floor(initial.z + 1.5) - cell % 5);
    await this.wait(() => Array.from({ length: 15 }, (_, cell) => this.block('A', reference(cell))?.name)
      .every(name => name?.endsWith('_ore')), 'all reference canvas block updates loaded', 5000);
    for (let cell = 0; cell < 15; cell++) pattern.push(this.block('A', reference(cell))?.name);
    const counts = Object.fromEntries(['gold_ore', 'coal_ore', 'emerald_ore', 'copper_ore', 'diamond_ore']
      .map(name => [name, pattern.filter(value => value === name).length]));
    this.check('Memory reference has exactly three of each ore', Object.values(counts).every(count => count === 3), { pattern, counts });
    await this.pinChunk(reference(0));
    await this.jump('A');
    await this.wait(() => this.bots.A.heldItem?.name?.endsWith('_ore'), 'jump ends initial viewing', 3500);
    this.check('Memory jump ends viewing before ten seconds', Date.now() - this.bots.A.probeStartedAt < 10000,
      { elapsedMs: Date.now() - this.bots.A.probeStartedAt, position: this.bots.A.probePosition });
    const own = vec(this.bots.A.probePosition);
    const button = (position, cell) => new Vec3(Math.floor(position.x + 2.5), Math.floor(position.y) + Math.floor(cell / 5),
      Math.floor(position.z + 1.5) - cell % 5);
    const wrongCell = pattern.findIndex(ore => ore !== this.bots.A.heldItem.name);
    const wrongStart = Date.now();
    await this.click('A', button(own, wrongCell));
    await this.wait(() => this.chats.some(row => row.at >= wrongStart && row.id === 'A' && row.text.includes('位置错误！')), 'wrong button penalty');
    const penalty = vec(this.bots.A.probePosition);
    await sleep(2800);
    this.check('Memory penalty persists before four seconds', vec(this.bots.A.probePosition).distanceTo(penalty) < 0.2,
      this.snapshot('A'), { penalty });
    await this.wait(() => vec(this.bots.A.probePosition).distanceTo(initial) < 0.2 && !this.bots.A.heldItem,
      'penalty changes to reference viewing', 2500);
    const reviewAt = Date.now();
    await sleep(1700);
    this.check('Memory second viewing lasts more than one second', vec(this.bots.A.probePosition).distanceTo(initial) < 0.2,
      this.snapshot('A'));
    await this.wait(() => this.bots.A.heldItem?.name?.endsWith('_ore') && vec(this.bots.A.probePosition).distanceTo(own) < 0.3,
      'three-second viewing returns to own canvas', 2500);
    this.check('Memory timed penalty and review observed', Date.now() - wrongStart >= 6500 && Date.now() - reviewAt >= 2500,
      { totalMs: Date.now() - wrongStart, reviewMs: Date.now() - reviewAt });

    const complete = async id => {
      await this.wait(() => this.bots[id].heldItem?.name?.endsWith('_ore'), `${id} ready to fill canvas`, 12000);
      const home = vec(this.bots[id].probePosition), filled = new Set();
      for (let completed = 0; completed < 15; completed++) {
        const ore = this.bots[id].heldItem?.name;
        const cell = pattern.findIndex((name, index) => name === ore && !filled.has(index));
        this.check(`${id} requested ore has an unfilled matching cell ${completed + 1}`, cell >= 0, { ore, filled: [...filled] });
        const target = button(home, cell), wall = target.offset(1, 0, 0);
        const clickedAt = Date.now();
        await this.click(id, target);
        const changedTo = (position, name) => {
          const update = this.bots[id].probeBlockUpdates.get(position.toString());
          return update?.at >= clickedAt && update.name === name;
        };
        try {
          // Last finisher is teleported outside view distance immediately. Keep
          // the actual server block-update evidence even if its chunk unloads.
          await this.wait(() => changedTo(wall, ore) && changedTo(target, 'air'), `${id} canvas cell ${cell} updated`, 2500);
          this.log('memoryCellConfirmed', { id, cell, clickedAt,
            wall: this.bots[id].probeBlockUpdates.get(wall.toString()),
            button: this.bots[id].probeBlockUpdates.get(target.toString()) });
        } catch (error) {
          this.log('memoryCellFailure', { id, cell, ore, target, wall,
            button: this.block(id, target)?.name, actualWall: this.block(id, wall)?.name,
            player: this.snapshot(id), recentMessages: this.chats.filter(row => row.id === id).slice(-12) });
          await this.console(`data get entity MyGoCheck${id} Pos`);
          await this.console(`data get entity MyGoCheck${id} SelectedItem`);
          await this.console(`execute in pvp:arena if block ${wall.x} ${wall.y} ${wall.z} minecraft:${ore} run say P2_SERVER_WALL_MATCHES`);
          await this.console(`execute in pvp:arena if block ${target.x} ${target.y} ${target.z} minecraft:crimson_button run say P2_SERVER_BUTTON_PRESENT`);
          throw error;
        }
        filled.add(cell);
      }
      await this.wait(() => this.chats.some(row => row.at >= since && row.text.includes(`MyGoCheck${id} 已完成画布`)), `${id} completion message`);
      this.check(`${id} has all fifteen original cells`, [...filled].length === 15, this.snapshot(id));
    };
    await complete('A');
    const observer = vec(this.bots.A.probePosition);
    this.check('Memory completed player remains on adventure observation platform', this.bots.A.game.gameMode === 'adventure', this.snapshot('A'));
    // Queue order is independent of A/B/C login order. The four-player signs
    // select fixed lane numbers, not a position relative to the current lane.
    const currentLane = Math.round((observer.x - initial.x - 13) / 14);
    const targetLane = currentLane === 0 ? 1 : 0;
    const signZ = observer.z + (targetLane === 0 ? -2.5 : -1.5);
    const targetObserver = initial.offset(13 + 14 * targetLane, 6, 0);
    await this.click('A', new Vec3(Math.floor(observer.x + 1.5), Math.floor(observer.y + 1), Math.floor(signZ)));
    await this.wait(() => vec(this.bots.A.probePosition).distanceTo(targetObserver) < 0.2, 'completed player switches observation lane');
    this.check('Memory observation sign selects its actual target lane', vec(this.bots.A.probePosition).distanceTo(targetObserver) < 0.2,
      { currentLane, targetLane, targetObserver, actual: this.bots.A.probePosition });
    const afterSwitch = vec(this.bots.A.probePosition);
    await this.console('kill MyGoCheckA');
    await sleep(150);
    this.check('Memory completed player death restores observation without elimination', this.bots.A.game.gameMode === 'adventure'
      && this.bots.A.health > 0 && vec(this.bots.A.probePosition).distanceTo(afterSwitch) < 0.2, this.snapshot('A'));
    await complete('B'); await complete('C');
    await this.wait(() => this.chats.some(row => row.at >= since && /第\s+\d+\/5\s+轮排名/.test(row.text)), 'N-1 completed ends Memory round');
    this.check('Memory first finisher ranks first', this.chats.some(row => row.at >= since && /第 1 名.*MyGoCheckA/.test(row.text)),
      this.chats.filter(row => row.at >= since && row.id === 'A' && /第 \d+ 名/.test(row.text)));
    await this.leaveAll(); await sleep(6000);
    this.check('Memory reference geometry cleared after leaving', await this.waitServerBlock(reference(0), 'air'), { position: reference(0) });
    await this.unpinChunks();
  }

  async runner() {
    const since = await this.start('runner');
    const homes = Object.fromEntries(Object.keys(this.bots).map(id => [id, vec(this.bots[id].probePosition)]));
    const floor = homes.A.floored().offset(0, -1, 0);
    const staticFloor = new Vec3(Math.floor(Object.values(homes).reduce((sum, p) => sum + p.x, 0) / 4) - 8,
      Math.floor(homes.A.y) - 1, Math.floor(Object.values(homes).reduce((sum, p) => sum + p.z, 0) / 4) - 8);
    await this.pinChunk(floor);
    await this.pinChunk(staticFloor);
    await this.wait(() => this.block('A', staticFloor)?.name === 'oak_planks', 'untouched Runner corner is loaded oak', 1500);
    this.check('Runner cleanup witness begins as untouched static oak', this.block('A', staticFloor)?.name === 'oak_planks', { staticFloor });
    for (const id of ['B', 'C', 'D']) this.orbit(id, homes[id]);
    await this.wait(() => this.block('A', floor)?.name?.includes('concrete') || this.block('A', floor)?.name?.includes('terracotta'),
      'Runner standing tile changes to player color', 1200);
    const coloredAt = Date.now();
    this.log('runnerColoredTile', { floor, block: this.block('A', floor).name });
    await this.wait(() => this.block('A', floor)?.name === 'air', 'Runner colored tile disappears after forty ticks', 2300);
    const fallenAt = Date.now();
    const seenFalling = () => Object.values(this.bots.A.entities).some(entity => entity.name === 'falling_block' || /falling.?block/i.test(entity.displayName || ''));
    await this.wait(seenFalling, 'native falling-block spawn packet', 1500);
    this.check('Runner delayed collapse observed', fallenAt - coloredAt > 1000,
      { coloredAt, fallenAt, floor, fallingEntities: Object.values(this.bots.A.entities).filter(e => /falling/.test(e.name || '')).map(e => ({ id: e.id, name: e.name, position: e.position })) });
    await this.wait(() => this.chats.some(row => row.at >= since && row.text.includes('MyGoCheckA 因停步出局')), 'stationary player eliminated after initial grace', 2500);
    this.check('Runner stationary player spectates while moving fixtures remain', this.bots.A.game.gameMode === 'spectator'
      && ['B', 'C', 'D'].every(id => this.bots[id].game.gameMode === 'adventure'), Object.keys(this.bots).map(id => this.snapshot(id)));
    this.bots.B._client.write('entity_action', { entityId: this.bots.B.entity.id, actionId: 0, jumpBoost: 0 });
    await this.wait(() => this.chats.some(row => row.at >= since && row.text.includes('MyGoCheckB 因潜行出局')), 'crouch statistic triggers elimination', 2500);
    this.bots.B._client.write('entity_action', { entityId: this.bots.B.entity.id, actionId: 1, jumpBoost: 0 });
    this.fixtures.delete('B');
    this.check('Runner croucher is eliminated independently', this.bots.B.game.gameMode === 'spectator', this.snapshot('B'));

    const center = new Vec3(Math.floor(Object.values(homes).reduce((sum, p) => sum + p.x, 0) / 4) + 0.5,
      homes.C.y, Math.floor(Object.values(homes).reduce((sum, p) => sum + p.z, 0) / 4) + 0.5);
    this.check('Runner lethal-drop fixture begins above untouched oak', this.block('C', center.floored().offset(0, -1, 0))?.name === 'oak_planks',
      { center, block: this.block('C', center.floored().offset(0, -1, 0))?.name });
    this.orbit('C', center, 0.18);
    await sleep(1650);
    this.orbit('D', center.offset(0, -7, 0), 0.18);
    await this.wait(() => this.chats.some(row => row.at >= since && row.text.includes('MyGoCheckD 因落块或摔落伤害出局')),
      'original falling block lethally hits lower-floor player', 3500);
    this.stopOrbits();
    await this.wait(() => this.chats.some(row => row.at >= since && /第\s+\d+\/5\s+轮排名/.test(row.text)), 'Runner last survivor ends round');
    this.check('Runner surviving player ranks first', this.chats.some(row => row.at >= since && /第 1 名.*MyGoCheckC/.test(row.text)),
      this.chats.filter(row => row.at >= since && row.id === 'C' && /第 \d+ 名/.test(row.text)));
    this.check('Runner owned falling entities are gone after finish', await this.noFallingBlocks());
    await this.leaveAll(); await sleep(6000);
    this.check('Runner dynamic geometry cleared', await this.waitServerBlock(floor, 'air'), { floor });
    this.check('Runner untouched static oak is removed by round cleanup', await this.waitServerBlock(staticFloor, 'air'), { staticFloor });
    await this.unpinChunks();
  }

  async smoke(game) {
    await this.start(game);
    this.check(`${game} menu starts real playable scene`, Object.values(this.bots).every(bot => bot.probeWorld === 'pvp:arena'
      && bot.game?.gameMode === (game === 'spleef' ? 'survival' : 'adventure')), Object.keys(this.bots).map(id => this.snapshot(id)));
    this.log('coverageLimit', { game, note: 'Lifecycle smoke only; detailed gameplay is not asserted by this case.' });
    await this.leaveAll();
  }

  async close(error) {
    this.closing = true; this.stopOrbits();
    if (!this.serverExit) {
      try { await this.unpinChunks(); }
      catch (failure) { this.log('chunkUnpinError', { error: failure.stack }); error ||= failure; }
    }
    for (const bot of Object.values(this.bots)) {
      try { bot.clearControlStates(); bot.quit('Party 2 protocol regression complete'); } catch (_) { /* already closed */ }
    }
    await sleep(250);
    if (this.server && !this.serverExit) {
      this.server.stdin.write('stop\n');
      try { await this.wait(() => !!this.serverExit, 'isolated server stopped', 20000); }
      catch (stopError) { this.log('stopTimeout', { error: stopError.message, pid: this.server.pid }); }
    }
    const suspicious = this.serverLines.filter(row => /Encountered an unexpected exception|Exception in thread|Caused by:|ERROR\]/.test(row.text));
    const report = { ok: !error && this.checks.every(check => check.pass) && suspicious.length === 0,
      error: error?.stack, checks: this.checks, serverErrors: suspicious, evidenceDir: this.evidenceDir,
      headless: true, rendered: false };
    fs.writeFileSync(path.join(this.evidenceDir, 'report.json'), json(report) + '\n');
    this.log('report', report);
    return report;
  }
}

async function main() {
  const { values } = parseArgs({ options: {
    game: { type: 'string' }, only: { type: 'string' }, port: { type: 'string', default: '25586' },
    'run-dir': { type: 'string' }, attach: { type: 'boolean' }, interactive: { type: 'boolean' }, help: { type: 'boolean' }
  } });
  if (values.help) {
    process.stdout.write('NODE_PATH=/private/tmp/pvp-protocol-check/node_modules node tools/party2_protocol_regression.cjs [--game memory,runner|all|spleef|tile|rlgl|dropper|rainbow|meat|oitq] [--port 25586] [--attach] [--interactive]\n'
      + 'Prepare first: ./gradlew -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime; existing run/eula.txt must record acceptance.\n'
      + 'Default runs Memory and Runner deep cases. Other games are lifecycle smoke cases. Each case cancels the remaining tournament after its checks. --only aliases --game.\n'
      + 'Interactive stdin JSON ops: console {command}, chat {id,command}, start {game}, vote {id,game}, snapshot, block {id,position}, click {id,position}, jump {id}, attack {id,target}, test {game}, quit.\n');
    return;
  }
  const probe = new PartyProbe({ port: values.port, runDir: values['run-dir'], attach: values.attach });
  let error;
  try {
    await probe.launch(); await probe.connectAll();
    if (values.interactive) {
      let chain = Promise.resolve();
      await new Promise(resolve => {
        const input = readline.createInterface({ input: process.stdin });
        input.on('line', line => {
          chain = chain.then(async () => {
            const request = JSON.parse(line), id = request.id || 'A';
            switch (request.op) {
              case 'console': await probe.console(request.command); break;
              case 'chat': await probe.chat(id, request.command); break;
              case 'start': await probe.start(request.game); break;
              case 'vote': await probe.vote(id, request.game); break;
              case 'snapshot': probe.log('snapshot', { players: Object.keys(probe.bots).map(who => probe.snapshot(who)) }); break;
              case 'block': probe.log('block', { id, position: request.position, name: probe.block(id, request.position)?.name }); break;
              case 'click': await probe.click(id, request.position); break;
              case 'jump': await probe.jump(id); break;
              case 'attack': {
                const target = probe.bots[id].players[`MyGoCheck${request.target}`]?.entity;
                if (!target) throw new Error('Target player entity is not loaded.');
                probe.bots[id].attack(target); probe.log('attack', { id, target: request.target }); break;
              }
              case 'test': await (request.game === 'memory' ? probe.memory() : request.game === 'runner' ? probe.runner() : probe.smoke(request.game)); break;
              case 'quit': input.close(); resolve(); break;
              default: throw new Error(`Unknown op ${request.op}`);
            }
          }).catch(failure => probe.log('interactiveError', { error: failure.stack }));
        });
        input.on('close', () => { void chain.finally(resolve); });
      });
    } else {
      const requested = values.game || values.only || 'memory,runner';
      const games = requested === 'all' ? Object.keys(GAMES) : requested.split(',');
      for (const game of games) {
        if (game === 'memory') await probe.memory();
        else if (game === 'runner') await probe.runner();
        else await probe.smoke(game);
      }
    }
  } catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}

module.exports = { PartyProbe, GAMES };
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
