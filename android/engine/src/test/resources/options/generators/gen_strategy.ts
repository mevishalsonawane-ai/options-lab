// Fixtures for Payoff / StrategyTemplates: IraAlgo's own TypeScript, run by node's type stripping.
// Run `node transpile.mjs` first: it stages the TypeScript this imports.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const LIB = path.join(os.tmpdir(), 'options-lab-ts-lib')
const load = (name: string) => import(pathToFileURL(`${LIB}/${name}.ts`).href)
const SM: any = await load('strategyMath')
const ST: any = await load('strategyTemplates')
const TR: any = await load('templateResolution')
const AR: any = await load('arbitrage')

const OUT = path.join(path.dirname(path.dirname(fileURLToPath(import.meta.url))), 'strategy.json')
const SPOT = 24_350
const CARRY = 0.06
const NOW = new Date('2026-08-14T05:00:00.000Z')
const NEAR_DAYS = 11
const FAR_DAYS = 39
const STRIKES = Array.from({ length: 61 }, (_, i) => SPOT + (i - 30) * 50)

function buildLegs(template: any, opts: { lotSize?: number; iv?: number; carry?: boolean } = {}) {
  return template.legs.map((leg: any, index: number) => {
    const strike = TR.resolveStrikeOffset(STRIKES, SPOT, leg.strikeOffset)
    const isFar = leg.expiryOffset === 1
    const intrinsic = leg.optionType === 'CE' ? Math.max(0, SPOT - strike!) : Math.max(0, strike! - SPOT)
    const days = isFar ? FAR_DAYS : NEAR_DAYS
    const timeValue = 120 * Math.sqrt(days / NEAR_DAYS) * Math.exp(-Math.abs(strike! - SPOT) / 300)
    const out: any = {
      id: `${template.id}-${index}`, segment: 'OPTION', side: leg.side, optionType: leg.optionType, strike,
      lots: leg.lots, lotSize: opts.lotSize ?? 65, expiry: isFar ? '22SEP26' : '25AUG26',
      expiryTs: NOW.getTime() / 1000 + days * 86_400, price: intrinsic + timeValue, iv: opts.iv ?? 12,
      active: true, symbol: `${template.id}-${index}`,
    }
    if (opts.carry !== false) {
      out.referenceUnderlying = SPOT
      out.forwardPrice = SPOT * Math.exp((CARRY * days) / 365)
    }
    return out
  })
}

function payoffCase(name: string, legs: any[], o: { daysAtT0?: number; ivShift?: number; fallbackIv?: number; steps?: number; atmIv?: number; spot?: number; now?: Date } = {}) {
  const now = o.now ?? NOW
  const spot = o.spot ?? SPOT
  const atmIv = o.atmIv ?? 12
  const nearest = SM.nearestLegDays(legs, now)
  const range = SM.payoffPriceRange(spot, legs, atmIv, nearest / 365)
  const p = SM.computePayoff(legs, spot, nearest, o.daysAtT0 ?? 0, range, o.steps ?? 240, o.ivShift ?? 0, o.fallbackIv ?? 12, now)
  return {
    name, legs, now: now.toISOString(), spot, atmIv, daysAtT0: o.daysAtT0 ?? 0, ivShift: o.ivShift ?? 0, fallbackIv: o.fallbackIv ?? 12, steps: o.steps ?? 240,
    nearest, range, multi: SM.hasMultipleActiveExpiries(legs), netCredit: SM.netCredit(legs), totalPremium: SM.totalPremium(legs),
    result: p, pop: SM.probabilityOfProfit(p.samples, spot, atmIv, nearest / 365),
    pnlGrid: [0, 0.5, 0.9, 0.97, 1, 1.013, 1.08, 1.5, 3].map((m) => ({
      u: spot * m,
      now: SM.totalPnlAt(legs, spot * m, 0, o.ivShift ?? 0, o.fallbackIv ?? 12, now),
      t5: SM.totalPnlAt(legs, spot * m, 5, o.ivShift ?? 0, o.fallbackIv ?? 12, now),
      legs: legs.map((l) => SM.legPnlAt(l, spot * m, 2, undefined, now)),
    })),
  }
}

const out: any = {}

// 1. Templates, their icons, and preview values.
out.templates = ST.STRATEGY_TEMPLATES.map((t: any) => ({
  ...t,
  preview: [0, 50, 90, 96, 100, 104, 108, 120, 200].map((s) => [s, ST.previewValue(t, s)]),
}))

// 2. Every template on the chain templatePayoff.test.ts uses, in contango.
out.payoffs = ST.STRATEGY_TEMPLATES.map((t: any) => payoffCase(t.id, buildLegs(t)))
// ... at T+3 with a vol shift, with no carry, and with legs lacking IV.
for (const id of ['short_straddle', 'long_iron_condor', 'call_calendar', 'diagonal_calendar', 'batman_strategy', 'call_ratio_back_spread']) {
  const t = ST.STRATEGY_TEMPLATES.find((x: any) => x.id === id)
  out.payoffs.push(payoffCase(`${id}+t3+iv`, buildLegs(t), { daysAtT0: 3, ivShift: 10, steps: 120 }))
  out.payoffs.push(payoffCase(`${id}+nocarry`, buildLegs(t, { carry: false })))
  out.payoffs.push(payoffCase(`${id}+noiv`, buildLegs(t, { iv: 0 }), { fallbackIv: 15, atmIv: 15 }))
}

// 3. Hand-built books: futures, closed, inactive, expiry codes without expiryTs, live snapshot.
const ts = (d: number) => NOW.getTime() / 1000 + d * 86_400
const covered = [
  { id: 'f', segment: 'FUTURE', side: 'BUY', lots: 1, lotSize: 65, expiry: '25AUG26', expiryTs: ts(11), price: 24_400, iv: 0, active: true, symbol: 'NIFTY25AUG26FUT', marketPrice: 24_395, referenceUnderlying: SPOT },
  { id: 'c', segment: 'OPTION', side: 'SELL', optionType: 'CE', strike: 24_600, lots: 1, lotSize: 65, expiry: '25AUG26', expiryTs: ts(11), price: 95.5, iv: 11.5, active: true, symbol: 'x', referenceUnderlying: SPOT, forwardPrice: SPOT * Math.exp(0.06 * 11 / 365), marketPrice: 95.5, tickSize: 0.05 },
]
out.payoffs.push(payoffCase('covered_call', covered))
out.payoffs.push(payoffCase('bare_future', [{ ...covered[0], marketPrice: undefined }]))
out.payoffs.push(payoffCase('closed_and_inactive', [
  { ...covered[1], exitPrice: 40 },
  { ...covered[1], id: 'p', optionType: 'PE', strike: 24_100, price: 70, active: false },
  { ...covered[1], id: 'b', side: 'BUY', strike: 24_300, price: 160 },
]))
out.payoffs.push(payoffCase('all_closed', [{ ...covered[1], exitPrice: 40 }, { ...covered[0], exitPrice: 24_500 }]))
out.payoffs.push(payoffCase('expiry_codes', [
  { id: 'a', segment: 'OPTION', side: 'SELL', optionType: 'PE', strike: 24_000, lots: 2, lotSize: 75, expiry: '25AUG26', price: 60, iv: 13, active: true, symbol: 'a' },
  { id: 'b', segment: 'OPTION', side: 'BUY', optionType: 'PE', strike: 23_800, lots: 2, lotSize: 75, expiry: '22SEP26', price: 150, iv: 14, active: true, symbol: 'b' },
]))
out.payoffs.push(payoffCase('expired_terminal', [
  { id: 'a', segment: 'OPTION', side: 'SELL', optionType: 'CE', strike: 24_300, lots: 1, lotSize: 75, expiry: '13AUG26', price: 60, iv: 13, active: true, symbol: 'a' },
  { id: 'b', segment: 'OPTION', side: 'BUY', optionType: 'CE', strike: 24_500, lots: 1, lotSize: 75, expiry: '13AUG26', price: 10, iv: 14, active: true, symbol: 'b' },
]))
out.payoffs.push(payoffCase('snapshot_within_tick', [{ ...covered[1], price: 80, marketPrice: SM.legPnlAt({ ...covered[1], price: 0, side: 'BUY' } as any, SPOT, 0, undefined, NOW) + 0.03 }]))

// 4. Smaller pieces.
out.moneyness = [[24_350, 24_350, 50, 'CE'], [24_250, 24_350, 50, 'CE'], [24_250, 24_350, 50, 'PE'], [24_475, 24_350, 50, 'CE'],
  [24_325, 24_350, 50, 'PE'], [24_375, 24_350, 50, 'PE'], [100, 100, 0, 'CE'], [24_300, null, 50, 'CE']].map((a: any) => ({ args: a, result: SM.strikeMoneyness(a[0], a[1], a[2], a[3]) }))
out.bs = []
for (const type of ['CE', 'PE']) for (const [spot, strike, t, iv, r, q] of [[24_350, 24_500, 11 / 365, 0.12, 0, 0], [100, 90, 0.5, 0.3, 0.065, 0.01], [100, 120, 1, 0.8, 0.05, 0.02], [100, 100, 0, 0.2, 0, 0], [100, 95, 0.1, 0, 0, 0]])
  out.bs.push({ type, spot, strike, t, iv, r, q, price: SM.bsPrice(type as any, { spot, strike, t, iv, r, q }), greeks: SM.bsGreeks(type as any, { spot, strike, t, iv, r, q }) })
out.normCdf = [-6, -2.5, -1, -0.3, 0, 0.3, 1, 2.5, 6].map((x) => [x, SM.normCdf(x)])
out.bands = [[SPOT, 12, 11 / 365, 1], [SPOT, 12, 11 / 365, 2], [100, 80, 2, 3], [0, 12, 1, 1], [100, 0, 1, 1]].map((a: any) => ({ args: a, result: SM.lognormalPriceBand(a[0], a[1], a[2], a[3]) }))
out.expiryDates = ['25AUG26', '5AUG26', '31FEB26', 'XXAUG26', '25aug26', '25AUG2026'].map((e) => ({ e, ms: SM.parseExpiryDate(e)?.getTime() ?? null, days: SM.daysToExpiry(e, NOW) }))
out.symbols = [['NIFTY', '25AUG26', 24_350, 'CE'], ['USDINR', '26AUG26', 83.25, 'PE'], ['NIFTY', '25AUG26', 24_350.0000001, 'PE']].map((a: any) => ({ args: a, result: SM.buildOptionSymbol(a[0], a[1], a[2], a[3]) }))
out.resolution = {
  strike: [[[80, 90, 100, 110, 130], 100, -2], [[80, 90, 100, 110, 130], 100, 2], [[90, 100, 110], 100, 2], [[110, 90, 100, 100], 100, 1], [[90, 110], 100, 0]]
    .map((a: any) => ({ args: a, result: TR.resolveStrikeOffset(a[0], a[1], a[2]) })),
  expiry: [[['28-AUG-2026', '04-AUG-2026'], '04-AUG-2026', 1], [['04-AUG-2026'], '04-AUG-2026', 1], [['25AUG26', '22SEP26', '28JUL26', 'junk'], '25AUG26', -1], [['25AUG26', '22SEP26'], '01JAN27', 0]]
    .map((a: any) => ({ args: a, result: TR.resolveExpiryOffset(a[0], a[1], a[2]) })),
  topology: [
    [{ strikeOffset: 0, resolvedStrike: 100 }, { strikeOffset: 2, resolvedStrike: 110 }],
    [{ strikeOffset: 0, resolvedStrike: 100 }, { strikeOffset: 2, resolvedStrike: null }],
    [{ strikeOffset: 0, resolvedStrike: 100 }, { strikeOffset: 0, resolvedStrike: 105 }],
    [{ strikeOffset: -2, resolvedStrike: 100 }, { strikeOffset: 2, resolvedStrike: 100 }],
    [{ strikeOffset: 2, resolvedStrike: 90 }, { strikeOffset: -2, resolvedStrike: 110 }],
  ].map((legs) => ({ legs, result: TR.validateTemplateStrikeTopology(legs as any) })),
  normalize: ['04-AUG-2026', '04-AUG-26', '04aug26', '', '4-aug-2026'].map((e) => [e, TR.normalizeExpiryCode(e)]),
}

// 5. The arbitrage scanner's row maths (pages/Arbitrage.tsx).
const pair = { id: 'NFO:NIFTY:near-next', underlying: 'NIFTY', exchange: 'NFO', type: 'near-next', near: { symbol: 'N', exchange: 'NFO' }, far: { symbol: 'F', exchange: 'NFO' } }
const quoteSets = [
  [{ bid: 24_400, ask: 24_401, ltp: 24_400.5, ts: 1000 }, { bid: 24_550, ask: 24_552, ltp: 24_551, ts: 2000 }],
  [{ bid: 24_400, ask: 24_401, ltp: 24_400.5, ts: 1000 }, { bid: 24_380, ask: 24_385, ltp: 24_382, ts: 2000 }],
  [{ ask: 24_401, ltp: 24_400.5, ts: 1000 }, { bid: 24_550, ltp: 24_551, ts: 2000 }],
  [{ ltp: 24_400.5, ts: 0 }, { bid: 24_550, ask: 24_552, ts: 0 }],
  [undefined, { bid: 24_550, ask: 24_552, ltp: 24_551, ts: 9000 }],
  [{ bid: 5_000, ask: 5_010, ts: 1000 }, { bid: 5_100, ask: 5_111, ts: 1000 }],
]
out.arbitrageRows = quoteSets.map(([near, far]) => {
  const quotes = new Map<string, any>()
  if (near) quotes.set('NFO:N', near)
  if (far) quotes.set('NFO:F', far)
  const row = AR.computeRow(pair as any, quotes, 7_500)
  return { near: near ?? null, far: far ?? null, now: 7_500, row: { ...row, pair: undefined, near: undefined, far: undefined } }
})

const json = JSON.stringify(out, (_k, v) => (typeof v === 'number' && !Number.isFinite(v) ? (Number.isNaN(v) ? 'NaN' : v > 0 ? 'Infinity' : '-Infinity') : v), 1)
fs.writeFileSync(OUT, json)
console.log('wrote strategy.json', json.length)
