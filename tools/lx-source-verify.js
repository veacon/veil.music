#!/usr/bin/env node
/*
 * 音源真实取流验证：init 之外，用真实歌曲调 musicUrl，确认能拿到可用播放直链。
 *
 * 与 lx-source-check.js 的区别：那个只测「脚本能否握手（init）」；
 * 本脚本真正走 musicUrl，验证「脚本能否解析出播放地址」。
 *
 * 关键约束（对应电视不能连 GitHub）：
 *  - 音源服务端都在国内，电视直连即可，所以脚本的网络请求走【直连】，不走代理。
 *  - 测试曲目（网易云 id）由外部 curl 拿到后传入，避免 node 依赖代理。
 *
 * 用法：node tools/lx-source-verify.js <目录> <wySongId> <歌名> <歌手> <时长秒>
 */
const fs = require('fs')
const vm = require('vm')
const path = require('path')
const https = require('https')
const http = require('http')

const BASE = path.resolve(__dirname, '..')
const PRELOAD = path.join(BASE, 'android/app/src/main/assets/script/user-api-preload.js')
const DIR = process.argv[2] ? path.resolve(process.argv[2]) : path.join(BASE, 'lx_sources')
// 测试参数：source 平台 / songmid / 歌名 / 歌手 / 时长
const TEST_SOURCE = process.argv[3] || 'kw'
const song = {
  id: process.argv[4] || '6717048',
  name: process.argv[5] || '小幸运',
  singer: process.argv[6] || '田馥甄',
  duration: Number(process.argv[7] || 260),
}

// 直连请求（模拟电视：无代理，直接访问音源服务端）
function directRequest(url, options, callback) {
  const u = new URL(url)
  const lib = url.startsWith('https') ? https : http
  const req = lib.request({
    hostname: u.hostname, port: u.port || (url.startsWith('https') ? 443 : 80),
    path: u.pathname + u.search, method: options.method || 'GET',
    headers: options.headers, timeout: options.timeout,
  }, callback)
  req.on('error', () => callback({ statusCode: 0, headers: {}, buf: Buffer.alloc(0) }))
  req.on('timeout', () => { req.destroy(); callback({ statusCode: 0, headers: {}, buf: Buffer.alloc(0) }) })
  req.end(options.body)
}

function parseHeader(raw) {
  const m = raw.match(/\/\*[\s\S]*?\*\//)
  if (!m) return {}
  const out = {}
  for (const line of m[0].split('\n')) {
    const t = line.match(/@(\w+)\s+(.+)/)
    if (t) out[t[1].toLowerCase()] = t[2].trim().replace(/\*$/, '').trim()
  }
  return out
}

async function verifyOne(file) {
  const raw = fs.readFileSync(path.join(DIR, file), 'utf8')
  const meta = parseHeader(raw)
  const key = 'k-' + file

  let initResolve
  const initPromise = new Promise(r => (initResolve = r))
  const pending = new Map()
  const timers = new Map()
  let seq = 1

  const sandbox = {
    console: { log() {}, info() {}, warn() {}, error() {}, debug() {} },
    JSON, Math, Date, Promise, String, Number, Boolean, Array, Object, RegExp, Error,
    Uint8Array, ArrayBuffer, DataView, encodeURIComponent, decodeURIComponent,
    encodeURI, decodeURI, parseInt, parseFloat, isNaN, setTimeout: null, clearTimeout: null,
  }
  sandbox.globalThis = sandbox
  const ctx = vm.createContext(sandbox)

  const UA = 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36'

  function doRequest(req) {
    const opts = req.options || {}
    const method = String(opts.method || 'GET').toUpperCase()
    const headers = Object.assign({ 'User-Agent': UA, Accept: 'application/json' }, opts.headers || {})
    let body
    if (opts.form && method === 'POST') body = new URLSearchParams(opts.form).toString()
    else if (opts.body != null) body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)
    directRequest(req.url, { method, headers, body, timeout: opts.timeout || 20000 }, (resp) => {
      let payload
      try {
        const buf = resp.buf
        let value
        if (opts.binary) value = [...buf]
        else {
          const text = buf.toString('utf8')
          try { value = JSON.parse(text) } catch (_) { value = text }
        }
        const h = {}
        for (const kk in (resp.headers || {})) h[kk.toLowerCase()] = resp.headers[kk]
        payload = { requestKey: req.requestKey, error: null,
          response: { statusCode: resp.statusCode || 0, statusMessage: 'OK', headers: h, body: value } }
      } catch (e) {
        payload = { requestKey: req.requestKey, error: String(e && e.message || e), response: null }
      }
      try { sandbox.__lx_native__(key, 'response', JSON.stringify(payload)) } catch (_) {}
    })
  }

  sandbox.__lx_native_call__ = (k, action, data) => {
    if (k !== key) return null
    if (action === 'init') {
      let obj = null
      try { obj = JSON.parse(data) } catch (_) {}
      initResolve(obj)
    } else if (action === 'request') {
      let req = null
      try { req = JSON.parse(data) } catch (_) {}
      if (req && req.requestKey && req.url) doRequest(req)
    } else if (action === 'response') {
      // 脚本回报 musicUrl 结果
      try {
        const o = JSON.parse(data)
        const r = pending.get(o.requestKey)
        if (r) { pending.delete(o.requestKey); r(o) }
      } catch (_) {}
    }
    return null
  }
  sandbox.__lx_native_call__set_timeout = (id, ms) => {
    const h = setTimeout(() => {
      try { sandbox.__lx_native__(key, '__set_timeout__', String(id)) } catch (_) {}
    }, ms)
    timers.set(id, h)
    return null
  }
  sandbox.__lx_native_call__utils_str2b64 = s => Buffer.from(String(s), 'utf8').toString('base64')
  sandbox.__lx_native_call__utils_b642buf = s => {
    const b = Buffer.from(String(s), 'base64')
    return JSON.stringify([...b])
  }
  sandbox.__lx_native_call__utils_str2md5 = s => {
    const crypto = require('crypto')
    return crypto.createHash('md5').update(String(s), 'utf8').digest('hex')
  }
  sandbox.__lx_native_call__utils_aes_encrypt = () => ''
  sandbox.__lx_native_call__utils_rsa_encrypt = () => ''

  vm.runInContext(fs.readFileSync(PRELOAD, 'utf8'), ctx, { filename: 'preload.js' })
  sandbox.lx_setup(key, file, meta.name || file, meta.description || '', meta.version || '',
    meta.author || '', meta.homepage || '', raw)

  try {
    vm.runInContext(raw, ctx, { filename: file, timeout: 15000 })
  } catch (e) {
    // 脚本顶层/异步 throw（如 juhe）不能让它把整个验证进程带崩
    if (initResolve) {
      // init 还没完成，标记失败
    }
    return { file, meta, error: 'evaluate: ' + e.message }
  }

  const init = await Promise.race([initPromise, new Promise(r => setTimeout(() => r(null), 20000))])
  if (!init) return { file, meta, error: 'init 超时' }
  if (init.status === false) return { file, meta, error: 'init FAIL: ' + init.errorMessage }

  const sources = (init.info && init.info.sources) || {}
  const src = sources[TEST_SOURCE]
  const actions = (src && src.actions) || []
  if (!actions.includes('musicUrl')) return { file, meta, error: `不支持 ${TEST_SOURCE} musicUrl`, actions: actions.join(',') }

  // 必须与 SourceManager.makeOldMusicInfo 完全一致：
  //  - interval 是 "mm:ss" 字符串（不是秒数），很多脚本会 split(':') 再 toString()
  //  - types 是数组、_types 是对象，两者都要有
  const mm = Math.floor(song.duration / 60)
  const ss = song.duration % 60
  const interval = String(mm).padStart(2, '0') + ':' + String(ss).padStart(2, '0')
  const musicInfo = {
    name: song.name, singer: song.singer, source: TEST_SOURCE, songmid: song.id,
    interval, albumName: '', img: '', typeUrl: {},
    albumId: '',
    types: [{ type: '128k', size: '' }, { type: '320k', size: '' }, { type: 'flac', size: '' }],
    _types: { '128k': { size: '' }, '320k': { size: '' }, flac: { size: '' } },
  }
  const reqKey = 'v-' + (seq++)
  const data = JSON.stringify({ requestKey: reqKey, data: { source: TEST_SOURCE, action: 'musicUrl', info: { type: '128k', musicInfo } } })
  const resultPromise = new Promise((resolve) => { pending.set(reqKey, resolve) })

  try { sandbox.__lx_native__(key, 'request', data) } catch (e) {
    return { file, meta, error: 'request 触发失败: ' + e.message }
  }

  const resp = await Promise.race([resultPromise, new Promise(r => setTimeout(() => r(null), 20000))])
  timers.forEach(h => clearTimeout(h))

  if (!resp) return { file, meta, error: 'musicUrl 超时（20s）' }
  if (!resp.status) return { file, meta, error: 'musicUrl 返回错误: ' + (resp.errorMessage || 'unknown') }
  const result = resp.result
  let url = null
  if (result && typeof result === 'object') {
    url = (result.data && (result.data.url || result.data.songmid)) || result.url || null
  }
  if (!url) return { file, meta, error: 'musicUrl 无 url', raw: JSON.stringify(result).slice(0, 200) }
  // 真实下载验证：GET 这个 URL，看是否返回音频（非 HTML、非 404）
  const audioCheck = await new Promise((resolve) => {
    directRequest(url, { method: 'GET', headers: { 'User-Agent': UA }, timeout: 15000 }, (resp) => {
      const buf = resp.buf || Buffer.alloc(0)
      const ct = String((resp.headers || {})['content-type'] || '').toLowerCase()
      const head = buf.slice(0, 3).toString('latin1')
      // 音频判定：content-type 是 audio/* 或 application/octet-stream，或前3字节是 ID3
      const isAudio = ct.startsWith('audio/') || ct.startsWith('application/octet-stream') || ct.includes('mpeg') || head === 'ID3'
      const isHtml = ct.includes('html') || (head.startsWith('<') || head.startsWith('\u00ff\u00fb') === false && head === '<ht')
      resolve({ status: resp.statusCode || 0, ct, size: buf.length, isAudio, isHtml })
    })
  })
  return {
    file, meta, ok: true, url: String(url).slice(0, 120),
    audioStatus: audioCheck.status, audioType: audioCheck.ct, audioSize: audioCheck.size,
    audioOk: audioCheck.isAudio,
  }
}

;(async () => {
  // 隔离单个音源的异步崩溃（juhe 等会在沙箱 Promise 里 throw）
  process.on('uncaughtException', () => {})
  process.on('unhandledRejection', () => {})

  const files = fs.readdirSync(DIR).filter(f => f.endsWith('.js')).sort()
  console.log('测试曲目：', song.name, '-', song.singer, '(wy id=' + song.id + ', ' + song.duration + 's)')
  console.log('')

  const results = []
  for (const f of files) {
    const r = await verifyOne(f)
    console.log('=====', f)
    console.log('  name:', r.meta.name, '| ver:', r.meta.version)
    if (r.error) console.log('  RESULT: FAIL -', r.error, r.raw ? '| raw: ' + r.raw : '', r.actions ? '| actions: ' + r.actions : '')
    else console.log('  RESULT: OK ->', r.url, '| 音频下载:', r.audioOk ? '✅ 有效' : '❌ ' + r.audioStatus + '/' + r.audioType)
    results.push({ file: f, name: r.meta.name, ok: !!(r.ok && r.audioOk), urlOk: !!r.ok, audioOk: !!r.audioOk, detail: r.error || r.url })
    console.log()
  }

  console.log('==== 汇总 ====')
  for (const r of results) console.log((r.ok ? 'OK  ' : 'FAIL'), r.name, '->', r.detail)
  const ok = results.filter(r => r.ok).length
  console.log(`\n${ok}/${results.length} 个音源能真实取到音频`)
  process.exit(ok === results.length ? 0 : 1)
})()
