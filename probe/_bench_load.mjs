import fs from 'node:fs'
import path from 'node:path'
import { LxHost } from './lxhost.mjs'

process.on('unhandledRejection', () => {})
const dir = path.resolve('../.tmp/lx-sources')
const files = []
for (const root of [dir, path.join(dir, '疑似早期版本')]) {
  if (!fs.existsSync(root)) continue
  for (const f of fs.readdirSync(root)) if (f.toLowerCase().endsWith('.js')) files.push(path.join(root, f))
}
files.sort()

const rows = []
for (const file of files) {
  const t0 = Date.now()
  let name = '', sources = {}, loadErr = ''
  const h = new LxHost({ timeout: 6000 })
  try {
    const info = await h.load(file, 6000)
    name = info.meta?.name || ''
    sources = info.sources || {}
  } catch (e) { loadErr = String(e?.message || e).slice(0, 120) }
  const srcDesc = Object.entries(sources).filter(([k]) => k !== 'local')
    .map(([k, v]) => `${k}:${(v.qualitys || []).join('/')}`).join(' ')
  const row = { file: path.relative(dir, file), ms: Date.now() - t0, name, srcDesc, loadErr }
  rows.push(row)
  console.log(`${loadErr ? 'LOADFAIL' : 'OK      '} ${String(row.ms).padStart(5)}ms | ${row.file.padEnd(46)} | ${name.slice(0, 16).padEnd(16)} | ${srcDesc.slice(0, 70)} ${loadErr}`)
}
fs.writeFileSync('_bench_load.json', JSON.stringify(rows, null, 2))
console.log('\nloaded', rows.filter(r => !r.loadErr).length, '/', rows.length)
