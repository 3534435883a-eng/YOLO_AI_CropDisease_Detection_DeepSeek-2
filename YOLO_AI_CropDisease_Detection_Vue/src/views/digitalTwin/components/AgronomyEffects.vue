<template>
 <aside class="agronomy-sheet" aria-label="大棚环境与决策效果">
  <header><span>当前农情</span><small>仿真响应</small></header>
  <p class="model-clock">{{ run?.current.at.replace('T',' ') || '等待接入' }}<small>{{ run?.playback?.waitingForAi ? 'AI分析中，模拟时钟暂候' : run?.playback?.playing ? '服务器持续推进' : '模拟时钟已暂停' }}</small></p>
  <div class="climate"><div><span>棚内温度</span><strong>{{ number(environment?.temperatureC) }}<small>°C</small></strong></div><div><span>空气湿度</span><strong>{{ number(environment?.airHumidityPct,0) }}<small>%</small></strong></div></div>
  <p class="condition" :class="{attention:risk?.riskLevel==='HIGH'}"><span>环境状态</span><b>{{ !risk?'等待数据':risk.riskLevel==='HIGH'?'需要处理':risk.riskLevel==='LOW'?'继续观察':'关注持续暴露' }}</b></p>
  <section v-if="root" class="root-section">
   <div class="section-heading"><h3>根区水分</h3><span>估计 · %vol</span></div>
   <div class="root-view" :class="{irrigating:irrigating}"><i class="drip-line"></i><i class="water-fill" :style="{height:root.soilMoistureVwcPct+'%'}"></i><div class="root-lines"><i></i><i></i><i></i></div><strong>{{ number(root.soilMoistureVwcPct) }}<small>%</small></strong><span>根区剖面示意</span></div>
   <dl class="water-budget"><div><dt>累计灌溉</dt><dd>{{ number(root.waterUsedL) }} <small>L</small></dd></div><div><dt>蒸散耗水</dt><dd>{{ number(root.evapotranspirationL) }} <small>L</small></dd></div><div><dt>排水</dt><dd>{{ number(root.drainageL) }} <small>L</small></dd></div><div><dt>生长水分因子</dt><dd>{{ number(root.waterStressFactor,2) }}</dd></div></dl>
   <p class="note">根区由水量平衡推演，参数为场景假设；不作为M3根区实测。</p>
  </section>
  <section v-if="scenario" class="comparison">
   <div class="section-heading"><h3>处理带来的变化</h3><span>同事件对照</span></div>
   <dl><div><dt>温度差</dt><dd>{{ signed(scenario.environment.temperatureC-scenario.withoutIntervention.temperatureC) }} °C</dd></div><div><dt>湿度差</dt><dd>{{ signed(scenario.environment.airHumidityPct-scenario.withoutIntervention.airHumidityPct) }} 个百分点</dd></div><div v-if="root&&shadowRoot"><dt>根区水分差</dt><dd>{{ signed(root.soilMoistureVwcPct-shadowRoot.soilMoistureVwcPct) }} 个百分点</dd></div><div v-if="resources"><dt>累计用电</dt><dd>{{ number(resources.electricityKwh,2) }} kWh</dd></div></dl>
   <p class="note">差值＝当前处理 − 未干预推演。改善与代价需一起判断。</p>
  </section>
  <section v-if="conditions.length" class="disease-conditions"><div class="section-heading"><h3>病害条件关注</h3><span>非确诊</span></div><div v-for="item in conditions" :key="String(item.code)"><span>{{ item.title || item.name || item.code }}</span><small>{{ riskLabel(String(item.level||item.riskLevel||'WATCH')) }}</small></div><p class="note">环境条件提示需结合叶片检查；病斑变化由复查记录确认。</p></section>
  <details v-if="effects.length"><summary>设备为什么产生这些作用</summary><article v-for="effect in effects" :key="effect.device"><button @click="$emit('focus',effect.device)">{{ effect.title }}</button><p>{{ effect.mechanism }}</p><p>{{ effect.observed }}</p><p><b>代价</b>{{ effect.tradeoff }}</p><p><b>复查</b>{{ effect.review }}</p></article></details>
 </aside>
</template>
<script setup lang="ts">
import {computed} from 'vue';
import type {M3LiveRun} from '/@/api/m3/live';
const props=defineProps<{run:M3LiveRun|null}>();
defineEmits<{(e:'focus',device:string):void}>();
interface RootState{soilMoistureVwcPct:number;waterStressFactor:number;waterUsedL:number;drainageL:number;evapotranspirationL:number;diseaseConditions?:Record<string,unknown>[]}
interface Effect{device:string;title:string;mechanism:string;observed:string;tradeoff:string;review:string}
const scenario=computed(()=>props.run?.current.scenario);
const environment=computed(()=>scenario.value?.environment??props.run?.current.environment);
const risk=computed(()=>scenario.value?.risk??props.run?.current.risk);
const root=computed(()=>scenario.value?.agronomy as RootState|undefined);
const shadowRoot=computed(()=>scenario.value?.shadowAgronomy as RootState|undefined);
const resources=computed(()=>scenario.value?.resources as {electricityKwh:number}|undefined);
const effects=computed(()=>(scenario.value?.effects||[]) as Effect[]);
const conditions=computed(()=>root.value?.diseaseConditions||[]);
const irrigating=computed(()=>!!scenario.value?.devices.IRRIGATION);
const number=(n:unknown,digits=1)=>typeof n==='number'&&Number.isFinite(n)?n.toFixed(digits):'—';
const signed=(n:number)=>Number.isFinite(n)?(n>0?'+':'')+n.toFixed(1):'—';
const riskLabel=(value:string)=>({HIGH:'需关注',MEDIUM:'持续观察',LOW:'未触发',WATCH:'继续检查'} as Record<string,string>)[value]||'待检查';
</script>
<style scoped>
.agronomy-sheet{position:absolute;left:20px;top:112px;bottom:96px;width:244px;z-index:12;overflow:auto;scrollbar-width:thin;padding:20px;background:rgba(252,251,242,.97);border:1px solid #dddfcf;color:#42543c;box-sizing:border-box;font-size:13px}.agronomy-sheet header,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:8px}.agronomy-sheet header{font-size:14px}.agronomy-sheet small,.section-heading>span{font-size:11px;color:#858f76}.model-clock{font-variant-numeric:tabular-nums;font-size:13px;line-height:1.8;margin:15px 0 18px}.model-clock small{display:block}.climate{display:grid;grid-template-columns:1fr 1fr;gap:14px}.climate span{display:block;font-size:12px;color:#7e8b73}.climate strong{display:block;font-weight:500;font-size:29px;margin-top:7px;font-variant-numeric:tabular-nums}.climate strong small{margin-left:4px}.condition{display:flex;justify-content:space-between;font-size:12px;border-top:1px solid #e0e3d4;padding-top:13px;margin-top:17px}.condition b{font-weight:500;color:#648253}.condition.attention b{color:#9b6b40}.root-section,.comparison,.disease-conditions{border-top:1px solid #e0e3d4;padding-top:16px;margin-top:17px}.section-heading h3{font-size:13px;font-weight:500;margin:0}.root-view{position:relative;height:96px;overflow:hidden;margin:14px 0;background:#e6dfcd;border:1px solid #d7d7c4}.water-fill{position:absolute;left:0;right:0;bottom:0;background:#a1b8a8;transition:height 600ms ease;opacity:.68}.root-lines{position:absolute;inset:10px 20px 0}.root-lines i{position:absolute;top:0;bottom:12px;left:50%;width:2px;background:#8c7958;transform:rotate(18deg);transform-origin:top}.root-lines i:nth-child(2){transform:rotate(-25deg);height:68px}.root-lines i:nth-child(3){transform:rotate(43deg);height:42px}.root-view strong{position:absolute;left:14px;top:20px;font-size:24px;font-weight:500;font-variant-numeric:tabular-nums}.root-view>span{position:absolute;left:14px;bottom:9px;font-size:10px;color:#60755d}.drip-line{position:absolute;top:0;left:50%;width:3px;height:9px;background:#86afa4;opacity:0;z-index:2}.irrigating .drip-line{opacity:1;animation:drip 1.5s linear infinite}.water-budget{display:grid;grid-template-columns:1fr 1fr;gap:13px;margin:15px 0 0}.water-budget dt,.comparison dt{font-size:11px;color:#849075}.water-budget dd{margin:5px 0 0;font-size:16px;font-variant-numeric:tabular-nums}.comparison dl{margin:13px 0 0}.comparison dl>div{display:flex;justify-content:space-between;gap:8px;margin:10px 0}.comparison dd{font-size:12px;margin:0;font-variant-numeric:tabular-nums}.note{font-size:11px;color:#8b947d;line-height:1.8;margin:12px 0 0}.disease-conditions>div:not(.section-heading){display:flex;justify-content:space-between;gap:8px;font-size:12px;padding:9px 0;border-bottom:1px solid #e8ebdf}.agronomy-sheet details{border-top:1px solid #e0e3d4;margin-top:18px;padding-top:13px;font-size:12px}.agronomy-sheet summary{cursor:pointer}.agronomy-sheet article{padding:13px 0;border-bottom:1px solid #e0e3d4}.agronomy-sheet article button{border:0;padding:0;color:#55784b;background:none;font:inherit;cursor:pointer}.agronomy-sheet article p{font-size:11px;line-height:1.8;color:#7e8c71;margin:7px 0}.agronomy-sheet article b{font-weight:500;margin-right:8px}button:focus-visible,summary:focus-visible{outline:2px solid #709262;outline-offset:3px}@keyframes drip{to{transform:translateY(65px);opacity:0}}@media(prefers-reduced-motion:reduce){.drip-line{animation:none!important}.water-fill{transition:none}}@media(max-width:1150px){.agronomy-sheet{left:12px;width:214px;padding:16px}}
</style>
