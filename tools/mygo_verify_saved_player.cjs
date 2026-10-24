'use strict';

// Reason: normal server shutdown during MyGO must persist restored lobby gear,
// not the temporary pumpkin helmet or mode-specific movement modifier.
const fs = require('node:fs');
const path = require('node:path');
const moduleRoot = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const nbt = require(path.join(moduleRoot, 'prismarine-nbt'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-server-test'));
const name = process.argv[2] || 'MyGoCheckA';
if (!path.basename(runDir).includes('mygo')) throw new Error('Use the isolated MyGO test directory.');

(async () => {
  const operator = JSON.parse(fs.readFileSync(path.join(runDir, 'ops.json'), 'utf8')).find(entry => entry.name === name);
  if (!operator) throw new Error(`No isolated test operator called ${name}`);
  const filename = path.join(runDir, 'mygo-validation-world/playerdata', `${operator.uuid}.dat`);
  const { parsed } = await nbt.parse(fs.readFileSync(filename));
  const player = nbt.simplify(parsed);
  const helmet = player.Inventory.find(item => item.Slot === 103);
  const apples = player.Inventory.filter(item => item.id === 'minecraft:apple').reduce((sum, item) => sum + item.count, 0);
  const attrs = player.Attributes || player.attributes || [];
  const checks = [
    { name: 'shutdown restores saved lobby helmet', pass: helmet?.id === 'minecraft:golden_helmet', actual: helmet },
    { name: 'shutdown restores saved lobby inventory', pass: apples === 3, actual: apples },
    { name: 'shutdown removes MyGO movement modifier', pass: !JSON.stringify(attrs).includes('pvp:mygo_speed'), actual: attrs },
    { name: 'shutdown does not save temporary pumpkin', pass: !player.Inventory.some(item => item.id === 'minecraft:carved_pumpkin'), actual: player.Inventory },
  ];
  const result = { event: 'savedPlayerVerification', time: new Date().toISOString(), name, filename,
    passed: checks.filter(check => check.pass).length, failed: checks.filter(check => !check.pass).length, checks };
  fs.writeFileSync(path.join(runDir, 'saved-player-results.json'), JSON.stringify(result, null, 2) + '\n');
  process.stdout.write(JSON.stringify(result) + '\n');
  if (result.failed) process.exitCode = 1;
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
