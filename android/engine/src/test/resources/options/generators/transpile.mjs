// Stage IraAlgo's frontend maths (read-only source) in a temp dir so node's
// built-in type stripping can run it: only import specifiers are rewritten,
// and the Arbitrage page's pure row helpers are lifted out of the React page.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const IRAALGO = process.env.IRAALGO ?? '/home/user/finalproducttradingapp'
const SRC = `${IRAALGO}/frontend/src/lib`
export const LIB = path.join(os.tmpdir(), 'options-lab-ts-lib')
fs.mkdirSync(LIB, { recursive: true })
for (const name of ['optionGreeks', 'strategyMath', 'strategyTemplates', 'templateResolution', 'strategyContracts']) {
  let src = fs.readFileSync(`${SRC}/${name}.ts`, 'utf8')
  src = src.replace(/from '\.\/(\w+)'/g, "from './$1.ts'")
  src = src.replace(/^import type .* from '@\/.*$/gm, '')
  fs.writeFileSync(`${LIB}/${name}.ts`, src)
}
const page = fs.readFileSync(`${IRAALGO}/frontend/src/pages/Arbitrage.tsx`, 'utf8')
const chunk = 'type ArbitragePair = any\n' + page.slice(page.indexOf('const FRESH_MS'), page.indexOf('function directionLabel'))
fs.writeFileSync(`${LIB}/arbitrage.ts`, chunk + '\nexport { computeRow, midPrice, quoteKey, roundToTick, FRESH_MS }\n')
console.log('staged', LIB)
