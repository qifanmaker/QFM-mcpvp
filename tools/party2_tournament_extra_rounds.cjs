'use strict';

// Reason/pre-review: legacy lifecycle probes cover five specific games, but a
// ten-round tournament must continue through five more distinct real modules.
// Advance only the owned server's native clock and let normal round rules score;
// retain every original scenario and verify all ten cumulative awards afterward.
const TOURNAMENT_ROUNDS = 10;
const EXTRA_GAMES = Object.freeze(['tile', 'meat', 'oitq', 'memory', 'farming']);
const INTERNAL_EXTRA_GAMES = Object.freeze(['farming', 'weapon', 'speed', 'steve', 'pearl']);
const MAXIMUM_TICKS = Object.freeze({
  tile: 2200, meat: 2600, oitq: 2000, memory: 2200, farming: 2000,
  weapon: 2600, speed: 3800, steve: 2600, pearl: 3800
});

function roundRows(probe, round) {
  const rows = (probe.rows || probe.rounds).get(round);
  return rows instanceof Map ? [...rows.values()] : rows || [];
}

async function completeTimedRound(probe, round, game, ids) {
  const maximumTicks = MAXIMUM_TICKS[game];
  if (maximumTicks == null) throw new Error(`No native deadline budget for ${game}`);
  probe.log('fixtureNote', { description: `Round ${round}: keep protocol players idle in ${game} and advance native ticks until its original rules settle. No score or phase fields are changed.` });
  for (let advanced = 0; roundRows(probe, round).length === 0 && advanced < maximumTicks; advanced += 100) {
    if (probe.voteError) throw probe.voteError;
    await probe.sprint(100);
  }
  await probe.wait(() => {
    if (probe.voteError) throw probe.voteError;
    return roundRows(probe, round).length === ids.length;
  }, `round ${round} ${game} publishes all native ranking rows`, 5000);
}

function verifyTenRoundTotals(probe, ids, expectedFirstFive) {
  const rounds = probe.rows || probe.rounds;
  probe.check('Exactly ten scored rounds are retained', rounds.size === TOURNAMENT_ROUNDS
    && Array.from({ length: TOURNAMENT_ROUNDS }, (_, index) => rounds.has(index + 1)).every(Boolean), [...rounds.keys()]);
  const totals = Object.fromEntries(ids.map(id => [id, 0]));
  for (let round = 1; round <= TOURNAMENT_ROUNDS; round++) {
    const rows = roundRows(probe, round);
    probe.check(`Round ${round} publishes exactly one row per participant`, rows.length === ids.length, rows);
    for (const id of ids) {
      const matches = rows.map(row => String(row.text || row).replace(/§./g, '')
        .match(/第\s+(\d+)\s+名\s+MyGoCheck([A-Z]).*本轮\s+\+(\d+)｜累计\s+(\d+)/))
        .filter(row => row && row[2] === id);
      probe.check(`Round ${round} ${id} has one placement and award`, matches.length === 1, rows);
      const place = Number(matches[0][1]), awarded = Number(matches[0][3]), cumulative = Number(matches[0][4]);
      const points = ids.length - place + 1;
      totals[id] += points;
      probe.check(`Round ${round} ${id} awards placement points exactly once`, place >= 1 && place <= ids.length
        && awarded === points && cumulative === totals[id], { place, awarded, cumulative }, { points, total: totals[id] });
    }
    if (round === 5 && expectedFirstFive) {
      probe.check('Original five-game scenarios retain their exact expected cumulative totals',
        ids.every(id => totals[id] === expectedFirstFive[id]), { ...totals }, expectedFirstFive);
    }
  }
  probe.check('Final table contains exactly one row per participant', probe.finalRows.length === ids.length, probe.finalRows);
  const winners = new Set();
  for (const id of ids) {
    const total = totals[id], place = 1 + Object.values(totals).filter(value => value > total).length;
    const rows = probe.finalRows.map(row => String(row.text || row).replace(/§./g, '')
      .match(/第\s+(\d+)\s+名\s+MyGoCheck([A-Z])\s+累计\s+(\d+)\s+分/))
      .filter(row => row && row[2] === id);
    probe.check(`${id} final rank and total include all ten rounds`, rows.length === 1
      && Number(rows[0][1]) === place && Number(rows[0][3]) === total, rows, { place, total });
    if (place === 1) winners.add(id);
  }
  return { totals, winners };
}

module.exports = { TOURNAMENT_ROUNDS, EXTRA_GAMES, INTERNAL_EXTRA_GAMES, completeTimedRound, verifyTenRoundTotals };
