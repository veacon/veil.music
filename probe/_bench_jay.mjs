// 用周杰伦 2026 新专辑《太阳之子》曲目，对压缩包里的全部音源做真实链路跑测。
// search → musicUrl → 字节校验，输出 CSV + 汇总。
import fs from 'node:fs'
import path from 'node:path'
import { LxHost } from './lxhost.mjs'
import { searchAll } from './search.mjs'
import { verify } from './verify.mjs'

process.on('unhandledRejection', () => {})
process.on('uncaughtException', (e) => console.log('UNCAUGHT', e?.message))

const SONGS = [
  { title: '那天下雨了', artist: '周杰伦', album: '太阳之子' },
  { title: '太阳之子', artist: '周杰伦', album: '太阳之子' },
  { title: '西西里', artist: '周杰伦', album: '太阳之子' },
  { title: '七月的极光', artist: '周杰伦', album: '太阳之子' },
]
const LADDER = ['flac', '320k', '128k']
const CALL_TIMEOUT = Number(process.env.CALL_TIMEOUT || 8000)
const INIT_TIMEOUT = 6000
const CONC = 6

const withTimeout = (p, ms, label) => Promise.race([
  p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + (label || ''))), ms)
  // eslint-disable-next-line
  ),
])

const dir = path.resolve('../.tmp/lx-sources')
const files = []
for (const root of [dir, path.join(dir, '疑似早期版本')]) {
  if (!fs.existsSync(root)) continue
  for (const f of fs.readdirSync(root)) if (f.toLowerCase().endsWith('.js')) files.push(path.join(root, f))
}
files.sort()

// 1) 每个歌 → 每个源的最佳候选（与 App 一致：按分数取）
console.log('=== 搜索候选 ===')
const perSong = []
for (const s of SONGS) {
  let cands = []
  try { cands = await searchAll(s) } catch (e) { console.log('search fail', s.title, e.message) }
  const bySrc = {}
  for (const c of cands) {
    if (!bySrc[c.source] || c.score > bySrc[c.source].score) bySrc[c.source] = c
  }
  const best = Object.values(bySrc)
  perSong.push({ song: s, best })
  console.log(s.title, '->', best.map(c => `${c.source}:${c.score}:${c.id}:${c.title}`).join(' | '))
}

// 2) 加载所有脚本
const hosts = []
for (const f of files) {
  const h = new LxHost({ timeout: CALL_TIMEOUT })
  try {
    const info = await h.load(f, INIT_TIMEOUT)
    const srcs = Object.keys(info.sources).filter(k => k !== 'local')
    if (!srcs.length) continue
    hosts.push({ f, h, sources: info.sources, name: info.meta?.name || '' })
  } catch { /* skip */ }
}
console.log('可加载脚本', hosts.length, '/', files.length)

// 3) 真实取链 + 校验
const tasks = []
for (const host of hosts) {
  for (const { song, best } of perSong) {
    for (const cand of best) {
      const qs = host.sources[cand.source]?.qualitys || []
      if (!qs.length) continue
      tasks.push({ host, song, cand, qs })
    }
  }
}
// 交错任务顺序，避免单个慢脚本阻塞前面
tasks.sort((a, b) => SONGS.findIndex(s => s.title === a.song.title) - SONGS.findIndex(s => s.title === b.song.title))

const rows = []
let idx = 0
async function worker() {
  while (idx < tasks.length) {
    const t = tasks[idx++]
    const { host, song, cand, qs } = t
    const ladder = LADDER.filter(q => qs.includes(q))
    for (const q of ladder) {
      const started = Date.now()
      let row = { file: path.relative(dir, host.f), name: host.name, src: cand.source, q, song: song.title, ok: false, ms: 0, kind: '', kbps: 0, err: '' }
      try {
        const info = {
          source: cand.source, name: song.title, songName: song.title, singer: song.artist,
          albumName: song.album, interval: cand.durationSec ? `${Math.floor(cand.durationSec / 60)}:${String(cand.durationSec % 60).padStart(2, '0')}` : undefined,
          ...(cand.extra || {}), id: cand.id, songid: cand.id, songId: cand.id,
        }
        const url = await withTimeout(host.h.musicUrl(cand.source, q, info), CALL_TIMEOUT + 2500, cand.source)
        if (typeof url !== 'string' || !/^https?:\/\//.test(url)) throw new Error('bad url: ' + String(url).slice(0, 60))
        const v = await withTimeout(verify(url, cand.durationSec || 200), 10000, 'verify')
        row.ms = Date.now() - started
        row.ok = v.ok === true
        row.kind = v.kind || ''
        row.kbps = v.kbps || 0
        row.err = v.err || ''
      } catch (e) {
        row.ms = Date.now() - started
        row.err = String(e?.message || e).slice(0, 100)
      }
      rows.push(row)
      if (row.ok) {
        console.log(`OK  ${row.file.padEnd(42)} ${row.src} ${row.q} ${row.kind} ${row.kbps}kbps ${row.ms}ms | ${row.song}`)
        break // 该脚本+源+歌命中即停，不试更低档
      }
    }
  }
}
await Promise.all(Array.from({ length: CONC }, worker))

fs.writeFileSync('_bench_jay.csv',
  'file,name,src,q,song,ok,ms,kind,kbps,err\n' +
  rows.map(r => [r.file, r.name, r.src, r.q, r.song, r.ok ? 1 : 0, r.ms, r.kind, r.kbps, r.err.replace(/[,\n]/g, ' ')].join(',')).join('\n'))

console.log('\n=== 汇总（按脚本）===')
const byFile = {}
for (const r of rows) {
  const b = byFile[r.file] || (byFile[r.file] = { file: r.file, name: r.name, ok: 0, total: 0, ms: [], kbps: [] })
  b.total++
  if (r.ok) { b.ok++; b.ms.push(r.ms); b.kbps.push(r.kbps) }
}
const arr = Object.values(byFile).sort((a, b) => b.ok - a.ok || (a.ms[0] || 9e9) - (b.ms[0] || 9e9))
for (const b of arr) {
  const avgMs = b.ms.length ? Math.round(b.ms.reduce((x, y) => x + y, 0) / b.ms.length) : 0
  const maxKbps = b.kbps.length ? Math.max(...b.kbps) : 0
  console.log(`${String(b.ok).padStart(2)}/${String(b.total).padEnd(2)} avg=${String(avgMs).padStart(5)}ms max=${String(maxKbps).padStart(4)}kbps | ${b.file} [${b.name.slice(0, 14)}]`)
}
console.log('\n总尝试', rows.length, '成功', rows.filter(r => r.ok).length)
