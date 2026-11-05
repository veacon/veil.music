#!/usr/bin/env node
/*
 * 音源自检：用 node 模拟 lx 宿主，把 assets/builtin_scripts 下的脚本按
 * JsScriptRuntime.load() 的真实顺序跑一遍（preload → lx_setup → 脚本本体），
 * 拿到每个脚本上报的能力表，确认「随包的这几个源现在还能不能起来」。
 *
 * 宿主侧实现与 Kotlin 端一一对应：
 *   __lx_native_call__(key, 'init' | 'request', data)   —— 脚本 → 宿主
 *   __lx_native__(key, 'response' | '__set_timeout__', …) —— 宿主 → 脚本
 * 网络走 node 内置 fetch，响应体按 ScriptHttpClient.decodeBody 的规则解析
 * （非 binary 先试 JSON.parse，失败留原文）。
 *
 * 用法：node tools/lx-source-check.js        全绿退出码 0，有源起不来退出码 1
 * 注意：需要能访问外网——有源在 init 阶段就要拉一次服务端配置。
 */
const fs = require('fs')
const vm = require('vm')
const path = require('path')

const BASE = path.resolve(__dirname, '..')
const PRELOAD = path.join(BASE, 'android/app/src/main/assets/script/user-api-preload.js')
const DIR = path.join(BASE, 'android/app/src/main/assets/builtin_scripts')

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

async function loadOne(file) {
  const raw = fs.readFileSync(path.join(DIR, file), 'utf8')
  const meta = parseHeader(raw)
  const key = 'k-' + file

  let initResolve
  const initPromise = new Promise(r => (initResolve = r))
  const timers = new Map()
  let timerSeq = 1

  const sandbox = {
    console: { log() {}, info() {}, warn() {}, error() {}, debug() {} },
    JSON, Math, Date, Promise, String, Number, Boolean, Array, Object, RegExp, Error,
    Uint8Array, ArrayBuffer, DataView, encodeURIComponent, decodeURIComponent,
    encodeURI, decodeURI, parseInt, parseFloat, isNaN, setTimeout: null, clearTimeout: null,
  }
  sandbox.globalThis = sandbox
  const ctx = vm.createContext(sandbox)

  // 原生桥
  const UA = 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36'
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
    }
    return null
  }

  async function doRequest(req) {
    const opts = req.options || {}
    const method = String(opts.method || 'GET').toUpperCase()
    const headers = Object.assign({ 'User-Agent': UA, Accept: 'application/json' }, opts.headers || {})
    let body
    if (opts.form && method === 'POST') body = new URLSearchParams(opts.form).toString()
    else if (opts.body != null) body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)
    let payload
    try {
      const ac = new AbortController()
      const t = setTimeout(() => ac.abort(), opts.timeout || 20000)
      const resp = await fetch(req.url, { method, headers, body, signal: ac.signal, redirect: 'follow' })
      clearTimeout(t)
      const buf = Buffer.from(await resp.arrayBuffer())
      let value
      if (opts.binary) value = [...buf]
      else {
        const text = buf.toString('utf8')
        try { value = JSON.parse(text) } catch (_) { value = text }
      }
      const h = {}
      resp.headers.forEach((v, kk) => { h[kk.toLowerCase()] = v })
      payload = { requestKey: req.requestKey, error: null,
        response: { statusCode: resp.status, statusMessage: resp.statusText || 'OK', headers: h, body: value } }
    } catch (e) {
      payload = { requestKey: req.requestKey, error: String(e && e.message || e), response: null }
    }
    try { sandbox.__lx_native__(key, 'response', JSON.stringify(payload)) } catch (_) {}
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
    return { file, meta, error: 'evaluate: ' + e.message }
  }

  const t0 = Date.now()
  const res = await Promise.race([
    initPromise,
    new Promise(r => setTimeout(() => r(null), 20000)),
  ])
  timers.forEach(h => clearTimeout(h))
  return { file, meta, init: res, ms: Date.now() - t0 }
}

;(async () => {
  const files = fs.readdirSync(DIR).filter(f => f.endsWith('.js')).sort()
  const summary = []
  for (const f of files) {
    const r = await loadOne(f)
    console.log('=====', f)
    console.log('  name:', r.meta.name, '| ver:', r.meta.version, '| author:', (r.meta.author || '').slice(0, 30))
    if (r.error) { console.log('  ERROR:', r.error) }
    else if (!r.init) { console.log('  INIT: 超时（20s 内没有上报 init）') }
    else {
      const ok = r.init.status !== false
      const sources = (r.init.info && r.init.info.sources) || {}
      const keys = Object.keys(sources)
      console.log('  status:', r.init.status, ok ? 'OK' : ('FAIL: ' + r.init.errorMessage), '| 耗时', r.ms, 'ms')
      console.log('  sources:', JSON.stringify(keys))
      for (const k of keys) {
        const s = sources[k] || {}
        console.log('     ', k, '| actions:', JSON.stringify(s.actions || []), '| qualitys:', JSON.stringify(s.qualitys || []))
      }
      summary.push({ name: r.meta.name, ok, keys })
    }
    console.log()
  }
  const total = files.length
  const ok = summary.filter(s => s.ok).length
  console.log('==== 汇总 ====')
  for (const s of summary) console.log((s.ok ? 'OK  ' : 'FAIL'), s.name, '->', s.keys.join(','))
  console.log(`\n${ok}/${total} 个内置源初始化成功`)
  process.exit(ok === total ? 0 : 1)
})()
