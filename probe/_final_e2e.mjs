// 最终验证：模拟 AudioResolver 的真实级联（搜索→打分→按 PREF 顺序取链→字节校验）。
// 曲目：周杰伦 2026 新专辑《太阳之子》5 首。对比「旧顺序(fish 前置/无酷我)」与「新顺序」。
import path from 'node:path'
import { LxHost, buildMusicInfo } from './lxhost.mjs'
import { searchAll } from './search.mjs'
import { verify } from './verify.mjs'

process.on('unhandledRejection', () => {})
const UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
const assets=path.resolve('../app/app/src/main/assets/luoxue')
const zipdir=path.resolve('../.tmp/lx-sources')

const SONGS=[
  {title:'那天下雨了',artist:'周杰伦',album:'太阳之子',durationSec:223},
  {title:'太阳之子',artist:'周杰伦',album:'太阳之子',durationSec:297},
  {title:'西西里',artist:'周杰伦',album:'太阳之子',durationSec:229},
  {title:'七月的极光',artist:'周杰伦',album:'太阳之子',durationSec:193},
  {title:'湘女多情',artist:'周杰伦',album:'太阳之子',durationSec:238},
]
// asset 文件名 → 实际 JS 文件（新增两条用 zip 原版校验）
const MAP={
  'lx-01-kulao.js':'👖裤佬SVIP音源(二改整合版).js',
  'lx-02-hyw.js':'HYWmusic_公益版.js',
  'lx-08-jiexiang.js':'杰翔聚合音源.js',
  'lx-11-kulao-full.js':'👖裤佬SVIP音源.js',
  'lx-12-yuningxi.js':'lx-玉宁熙-Pro.js',
  'lx-07-hubhui.js':'回避聚合 (HuiBi Aggregate).js',
  'lx-04-xinghai.js':'星海音乐源.js',
  'lx-06-molan.js':'墨澜聚合音源.js',
  'lx-03-fish.js':null, // fish 不在 zip；旧顺序把它当兜底，这里用「最终命中 fish」的历史结论表示
}
const PREF_NEW={
  kw:['lx-11-kulao-full.js','lx-12-yuningxi.js','lx-01-kulao.js','lx-02-hyw.js'],
  wy:['lx-12-yuningxi.js','lx-08-jiexiang.js','lx-01-kulao.js','lx-07-hubhui.js','lx-09-ciallo.js','lx-10-qdy.js','lx-03-fish.js','lx-05-quandou.js'],
  tx:['lx-12-yuningxi.js','lx-01-kulao.js','lx-02-hyw.js','lx-03-fish.js'],
  kg:['lx-01-kulao.js','lx-04-xinghai.js','lx-02-hyw.js','lx-06-molan.js','lx-07-hubhui.js','lx-08-jiexiang.js','lx-03-fish.js'],
}
// 旧顺序：没有 kw 搜索；wy/tx 把 fish 放靠前；tx 首选手是二改版裤佬（新专辑上会失败）。
const PREF_OLD={
  wy:['lx-01-kulao.js','lx-02-hyw.js','lx-03-fish.js'],
  tx:['lx-01-kulao.js','lx-02-hyw.js','lx-03-fish.js'],
  kg:['lx-01-kulao.js','lx-04-xinghai.js','lx-02-hyw.js','lx-03-fish.js'],
}
const withTimeout=(p,ms)=>Promise.race([p,new Promise((_,r)=>setTimeout(()=>r(new Error('timeout')),ms))])

// 加载需要用到的 asset 脚本
const needed=[...new Set(Object.values(PREF_NEW).flat().concat(Object.values(PREF_OLD).flat()))].filter(n=>MAP[n])
const hosts={}
for(const asset of needed){
  const f=MAP[asset]; if(!f)continue
  const p=path.join(zipdir,f)
  try{const h=new LxHost({timeout:8000});const info=await h.load(p,6000);hosts[asset]={h,info}}catch{}
}

// 为每个歌搜候选
const candBySong={}
for(const s of SONGS){
  const c=await searchAll(s)
  // distinctBy source 取最高分（与 TrackMatcher 一致）
  const by={};for(const x of c){if(!by[x.source]||x.score>by[x.source].score)by[x.source]=x}
  candBySong[s.title]=by
}

async function tryResolve(song,prefs,allowKw){
  const by=candBySong[song.title]
  const order=Object.values(by).sort((a,b)=>b.score-a.score)
  for(const cand of order){
    if(cand.source==='kw'&&!allowKw)continue
    const list=prefs[cand.source]||[]
    for(const asset of list){
      const host=hosts[asset];if(!host)continue
      const qs=host.info.sources[cand.source]?.qualitys||[]
      const ladder=['320k','128k','flac'].filter(q=>qs.includes(q))
      for(const q of ladder.slice(0,2)){
        const t=Date.now()
        try{
          const info={source:cand.source,name:song.title,songName:song.title,singer:song.artist,albumName:song.album,interval:`${Math.floor(song.durationSec/60)}:${String(song.durationSec%60).padStart(2,'0')}`,...(cand.extra||{}),id:cand.id,songid:cand.id}
          const url=await withTimeout(host.h.musicUrl(cand.source,q,info),9000)
          if(typeof url!=='string'||!url.startsWith('http'))continue
          const v=await withTimeout(verify(url,song.durationSec),10000)
          const good=v.ok&&!String(v.kind).startsWith('unknown')&&(v.kbps>=90||v.kind==='flac')
          if(good)return{ok:true,src:cand.source,asset,q,kind:v.kind,kbps:v.kbps,ms:Date.now()-t}
        }catch{}
      }
    }
  }
  return{ok:false}
}

console.log('=== 新顺序（新增酷我 + 重排 PREF）===')
for(const s of SONGS){
  const r=await tryResolve(s,PREF_NEW,true)
  console.log(`${r.ok?'OK  ':'MISS'} ${s.title.padEnd(6)} | ${r.ok?`${r.src}/${r.asset} ${r.q} ${r.kind} ${r.kbps}kbps ${r.ms}ms`:''}`)
}
console.log('\n=== 旧顺序（无酷我，fish 前置）===')
for(const s of SONGS){
  const r=await tryResolve(s,PREF_OLD,false)
  console.log(`${r.ok?'OK  ':'MISS'} ${s.title.padEnd(6)} | ${r.ok?`${r.src}/${r.asset} ${r.q} ${r.kind} ${r.kbps}kbps ${r.ms}ms`:'未在已测脚本命中(按历史结论最终落到 fish_music)'}`)
}
process.exit(0)
