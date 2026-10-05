<template>
 <section class="live-workbench" :class="{compact}">
  <header class="live-heading"><div><span class="eyebrow">M3 / CAUSAL DATA STREAM</span><h2>{{ compact?'实时生长与风险':'实时接入 · 生长纠偏与风险预警' }}</h2><p v-if="!compact">M3历史数据按时间回放，观测到达后修正状态；曲线仅展示已接入的数据。</p></div><span class="live-state" :class="{on:playing}">{{ playing?'正在接入':run?.finished?'回放完成':run?'已暂停':'等待接入' }}</span></header>
  <div class="live-controls">
   <select v-model.number="year" aria-label="在线回放年份" :disabled="busy||playing" @change="run&&start()"><option :value="2023">2023 · 校准季</option><option :value="2024">2024 · 校准季</option><option :value="2025">2025 · 展示季</option></select>
   <select v-model.number="stepCount" aria-label="数据推进速度"><option :value="1">半小时 / 秒</option><option :value="12">6小时 / 秒</option><option :value="48">1天 / 秒</option></select>
   <button class="primary" :disabled="busy" @click="start">{{ run?'重开接入':'开始接入' }}</button><button v-if="run" :disabled="busy||run.finished" @click="toggle">{{ playing?'暂停':'继续' }}</button><button v-if="run&&!compact" :disabled="busy||playing||run.finished" @click="step">单步推进</button>
   <button v-if="run&&!compact" @click="openTwin">大棚实时展示 ↗</button><button v-if="run&&compact" @click="openDetails">完整工作区 ↗</button><button v-if="run&&!compact" @click="exportRun">导出已接入数据</button>
  </div>
  <p v-if="error" class="live-error">{{ error }} <button v-if="run" @click="reload">读取当前状态</button></p>
  <div v-if="!run" class="live-empty"><strong>先预测，再接收观测，再修正</strong><p>开始后按半小时依次消费M3环境。没有株高测量的时段继续预测，有测量到达才进行纠偏。</p></div>
  <template v-else>
   <div class="stream-clock"><strong>{{ current?.at.replace('T',' ') }}</strong><span>{{ run.cursor }}/{{ run.totalSlots }} 半小时时段 · {{ run.updateCount }} 次逐株更新</span></div>
   <div class="live-metrics"><div><span>{{ compact && current?.scenario?'场景株高 · 模型值':'在线株高 · 批次均值' }}</span><strong>{{ fmt(compact?current?.scenario?.growth.plantHeightCm??current?.correctedHeightCm:current?.correctedHeightCm,1) }}<small>cm</small></strong></div><div><span>{{ current?.scenario?'场景温度 / 湿度':'气温 / 湿度' }}</span><strong>{{ fmt(activeEnvironment?.temperatureC,1) }}<small>°C / {{ fmt(activeEnvironment?.airHumidityPct,1) }}%</small></strong></div><div><span>VPD · 温湿度计算</span><strong>{{ fmt(activeRisk?.vpdKpa,2) }}<small>kPa</small></strong></div></div>
   <progress class="stream-progress" :value="run.cursor" :max="run.totalSlots"/>
   <div v-if="recentUpdate" class="update-event"><span>最近纠偏 · {{ recentUpdate.at.slice(5,16).replace('T',' ') }} · {{ recentUpdate.plantId }}</span><strong>{{ fmt(recentUpdate.beforeCm,1) }} → {{ fmt(recentUpdate.observedCm,1) }} → {{ fmt(recentUpdate.afterCm,1) }} cm</strong><small>预测 → 实测 → 更新 · 观测权重 {{ fmt((recentUpdate.measurementWeight??0)*100,0) }}%<b v-if="recentUpdate.reviewRequired"> · 偏差较大，建议复核</b></small></div>
   <div v-else class="update-event"><span>首日实测建立初态</span><small>后续尚无新株高测量到达，模型持续预测。</small></div>
   <div v-if="!compact" ref="chartRef" class="live-chart"></div>
   <div class="live-bottom">
    <section class="risk-section"><header><h3>当前风险与检查建议</h3><span>{{ activeRisk?.alerts.length || 0 }} 条</span></header>
     <p v-if="!activeRisk?.alerts.length" class="quiet">当前数据未触发已配置的环境预警。</p>
     <article v-for="alert in activeRisk?.alerts" :key="alert.code" class="live-alert" :class="alert.level.toLowerCase()"><div><b>{{ alert.title }}</b><span>{{ alert.level==='HIGH'?'高风险':alert.level==='INFO'?'数据提示':'关注' }} · {{ alert.durationMinutes }} 分钟</span></div><p v-if="alert.unit">{{ fmt(alert.value,alert.unit==='kPa'?2:1) }}{{ alert.unit }} · 阈值 {{ alert.threshold }}{{ alert.unit }}</p><p>{{ alert.advice }}</p><small>{{ alert.basis }}{{ alert.estimatedInput?' · 输入含估计值':'' }}</small><button v-if="alert.code==='HUMID'" @click="router.push('/imgRecord')">查看病害识别记录 ↗</button></article>
     <div class="exposure"><span>近 {{ fmt((activeRisk?.windowMinutes??0)/60,1) }} 小时累计</span><b>高温 {{ activeRisk?.highTemperatureMinutes24h }} min · 高湿 {{ activeRisk?.highHumidityMinutes24h }} min</b><small>热暴露 {{ fmt(activeRisk?.heatDegreeHours24h,1) }} °C·h · 按数据时间计时</small></div>
    </section>
    <section v-if="!compact" class="reference-section"><h3>多因素生长参考</h3><p>温度 × 光照截获 × CO₂ × 假设水分，推进干物质、叶面积和物候。以下为未校准模型量。</p>
     <dl><div><dt>模型叶面积指数</dt><dd>{{ fmt(current?.growth.lai,2) }}</dd></div><div><dt>模型总干重</dt><dd>{{ fmt(current?.growth.wTotal,1) }} g/m²</dd></div><div><dt>CO₂输入</dt><dd>{{ fmt(current?.environment.co2Ppm,0) }} ppm</dd></div><div><dt>原始土壤VWC</dt><dd>{{ fmt(current?.environment.soilMoistureVwcPct,1) }}%</dd></div><div><dt>原始光照</dt><dd>{{ fmt(current?.environment.lightRaw,2) }}（原单位）</dd></div></dl>
     <p>最近到达叶参考：LAI {{ fmt(lastLeaf?.laiRaw,2) }} · LDW {{ fmt(lastLeaf?.ldwRaw,3) }}。仪器/面积单位未对齐，暂不用于状态纠偏。</p><p class="reference-note">果实饱满度、实测风速尚未接入；RUE初态、土壤胁迫与光照换算仍有假设。</p>
    </section>
   </div>
   <ScenarioPanel v-if="!compact && run?.current.scenario" :run="run" inline @change="acceptSnapshot" />
   <section v-if="!compact" class="live-scores"><h3>新测量到达前的预测误差</h3><p>{{ run.scores.prior.n }} 个逐株配对点 · 首日排除；在线预测使用过去已到达的观测。</p><table><thead><tr><th>计算方式</th><th>MAE / cm</th><th>RMSE / cm</th></tr></thead><tbody><tr><td>固定参数，不在线纠偏</td><td>{{ fmt(run.scores.openLoop.mae) }}</td><td>{{ fmt(run.scores.openLoop.rmse) }}</td></tr><tr><td>在线纠偏后的下一次预测</td><td>{{ fmt(run.scores.prior.mae) }}</td><td>{{ fmt(run.scores.prior.rmse) }}</td></tr><tr class="muted"><td>当次更新后拟合偏差</td><td>{{ fmt(run.scores.posterior.mae) }}</td><td>{{ fmt(run.scores.posterior.rmse) }}</td></tr></tbody></table></section>
   <details class="stream-details"><summary>来源、算法与运行记录</summary><p>环境来源：{{ current?.environment.origin }} · 原始时间 {{ current?.environment.observedAt || '补值' }} · 本次接收 {{ current?.receivedAt }}</p><p>冻结参数版本 {{ run.parameterVersion }}</p><ul><li v-for="note in run.assumptions" :key="note">{{ note }}</li></ul><p>已到达 {{ events.length }} 条纠偏/预警变化记录；导出保留原始观测来源。</p><p v-for="event in events.slice(-10)" :key="event.type+event.at+(event.plantId||event.code)">{{ event.at }} · {{ event.type==='HEIGHT_UPDATE'?event.plantId+' 株高更新':event.title+' '+(event.level==='CLEAR'?'已解除':event.level) }}</p><p class="hash">{{ run.observationsSha256 }}</p></details>
  </template>
 </section>
</template>
<script lang="ts">
const startPlayback=new Set<string>();
</script>
<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import * as echarts from 'echarts';
import ScenarioPanel from '../digitalTwin/components/ScenarioPanel.vue';
import {formatM3Number as fmt,type M3ReplayState} from '/@/api/m3';
import {startM3Live,stepM3Live,getM3Live,deleteM3Live,commandM3Scenario,type M3LiveRun,type M3LiveFrame,type M3LiveEvent} from '/@/api/m3/live';
const props=withDefaults(defineProps<{compact?:boolean}>(),{compact:false});
const emit=defineEmits<{(event:'frame',state:M3ReplayState):void;(event:'run',run:M3LiveRun):void;(event:'playback',playing:boolean):void;(event:'error',message:string):void}>();
const router=useRouter(),route=useRoute(),run=ref<M3LiveRun|null>(null),frames=ref<M3LiveFrame[]>([]),events=ref<M3LiveEvent[]>([]);
const year=ref(2025),stepCount=ref(1),playing=ref(false),busy=ref(false),error=ref(''),chartRef=ref<HTMLDivElement>();
const current=computed(()=>run.value?.current),activeRisk=computed(()=>run.value?.current.scenario?.risk??run.value?.current.risk),recentUpdate=computed(()=>events.value.filter(e=>e.type==='HEIGHT_UPDATE').slice(-1)[0]);
const activeEnvironment=computed(()=>current.value?.scenario?.environment??current.value?.environment);
const lastLeaf=computed(()=>frames.value.filter(f=>f.leafReference.laiRaw!==null).slice(-1)[0]?.leafReference);
let timer:ReturnType<typeof setTimeout>|undefined,chart:echarts.ECharts|undefined,observer:ResizeObserver|undefined,disposed=false;
let pollTimer:ReturnType<typeof setTimeout>|undefined,mountTask:Promise<void>=Promise.resolve();
function pause(){playing.value=false;if(timer)clearTimeout(timer);timer=undefined;}
async function accept(value:M3LiveRun,replace=false){
 if(disposed||!replace&&run.value&&(value.cursor<run.value.cursor||value.cursor===run.value.cursor&&(value.current.scenario?.version??0)<(run.value.current.scenario?.version??0)))return;
 sessionStorage.setItem('m3ActiveRun',value.runId);year.value=value.year;
 if(replace){frames.value=value.frames;events.value=value.events;}
 else {const last=frames.value.slice(-1)[0]?.cursor??-1;frames.value.push(...value.frames.filter(f=>f.cursor>last));events.value.push(...value.events);}
 run.value={...value,frames:frames.value,events:events.value};
 emit('frame',{frame:value.current,heightCm:value.current.scenario?.growth.plantHeightCm??value.current.correctedHeightCm,year:value.year,track:'online',lai:value.current.scenario?.growth.lai??value.current.growth.lai});emit('run',{...value,frames:frames.value,events:events.value});
 await nextTick();render();if(value.finished||value.failure)pause();if(value.failure)error.value='计算已停止：'+value.failure+'，请重开接入';
}
async function start(){
 pause();error.value='';busy.value=true;
 try{
  if(run.value)await deleteM3Live(run.value.runId);
  run.value=null;frames.value=[];events.value=[];
  const value=await startM3Live(year.value);await accept(value,true);
  startPlayback.add(value.runId);
  await router.replace({query:{...route.query,liveRun:value.runId}});
  if(!disposed){startPlayback.delete(value.runId);playing.value=true;schedule();}
 }catch(e){error.value=e instanceof Error?e.message:String(e);}
 finally{busy.value=false;}
}
async function step(){
 if(!run.value||busy.value||run.value.finished)return;
 busy.value=true;error.value='';
 try{await accept(run.value.current.scenario?.pending?await getM3Live(run.value.runId,true):await stepM3Live(run.value.runId,run.value.cursor,stepCount.value));}
 catch(e){pause();error.value=e instanceof Error?e.message:String(e);}
 finally{busy.value=false;}
}
function schedule(){if(!playing.value||disposed)return;timer=setTimeout(async()=>{await step();if(playing.value)schedule();},1000);}
function toggle(){if(playing.value)pause();else if(run.value&&!run.value.finished){playing.value=true;schedule();}}
async function startAutomatic(){
 await mountTask;
 if(disposed)throw new Error('大棚页面已关闭');
 if(busy.value)throw new Error('当前操作尚未完成，请稍后继续');
 pause();busy.value=true;error.value='';stepCount.value=1;
 try{
  let value=run.value;
  if(!value){const id=String(route.query.liveRun||sessionStorage.getItem('m3ActiveRun')||'');if(id){try{value=await getM3Live(id);}catch{value=null;}}}
  if(!value||value.finished||value.failure)value=await startM3Live(2025);
  const scenario=value.current.scenario;
  if(!scenario)throw new Error('当前运行没有可接管的事件场景');
  if(!scenario.autoEvents||!scenario.autoActuation)value=await commandM3Scenario(value.runId,'configure',{autoEvents:true,autoActuation:true});
  value=await getM3Live(value.runId);
  await accept(value,true);
  if(route.query.liveRun!==value.runId)await router.replace({query:{...route.query,liveRun:value.runId}});
  if(!disposed){playing.value=true;schedule();}
 }catch(e){error.value=e instanceof Error?e.message:String(e);throw e;}
 finally{busy.value=false;}
}
async function reload(){
 pause();if(!run.value&&!route.query.liveRun)return;
 busy.value=true;error.value='';
 try{await accept(await getM3Live(String(route.query.liveRun||run.value?.runId)),true);}
 catch(e){error.value=e instanceof Error?e.message:String(e);}
 finally{busy.value=false;}
}
function openTwin(){pause();if(run.value)router.push({path:'/digitalTwin',query:{mode:'m3',liveRun:run.value.runId}});}
function openDetails(){pause();if(run.value)router.push({path:'/modelCalibration',query:{liveRun:run.value.runId}});}
function render(){
 if(props.compact||!chartRef.value||!frames.value.length)return;
 if(!chart){chart=echarts.init(chartRef.value);observer=new ResizeObserver(()=>chart?.resize());observer.observe(chartRef.value);}
 chart.setOption({animation:false,color:['#a79f8e','#526f49','#bc8c49'],
  legend:{top:4,icon:'circle',textStyle:{color:'#766e5e'}},
  tooltip:{trigger:'axis',valueFormatter:(v:unknown)=>typeof v==='number'?v.toFixed(1)+' cm':'—'},
  grid:{left:54,right:20,top:45,bottom:45},
  xAxis:{type:'category',data:frames.value.map(f=>f.at),axisLabel:{formatter:(s:string)=>s.slice(5,10)+' '+s.slice(11,16),color:'#8a8270'},axisLine:{lineStyle:{color:'#ded8c9'}}},
  yAxis:{type:'value',name:'株高 / cm',splitLine:{lineStyle:{color:'#eae5da'}}},
  series:[{name:'固定参数模拟',type:'line',showSymbol:false,lineStyle:{type:'dashed',width:2},data:frames.value.map(f=>f.originalHeightCm)},
   {name:'实时纠偏模拟',type:'line',showSymbol:false,lineStyle:{width:3},data:frames.value.map(f=>f.correctedHeightCm)},
   {name:'已收到M3测量',type:'scatter',symbolSize:8,data:frames.value.map(f=>f.observedHeightCm)}]},true);
}
function exportRun(){
 if(!run.value)return;const blob=new Blob([JSON.stringify({...run.value,frames:frames.value,events:events.value},null,2)],{type:'application/json'});
 const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='m3-online-'+run.value.year+'-'+run.value.cursor+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function pollSnapshot(){pollTimer=setTimeout(async()=>{
 if(disposed)return;
 if(run.value&&!busy.value){try{let value=await getM3Live(run.value.runId,true);if((!props.compact&&value.cursor>run.value.cursor)||value.updateCount>events.value.filter(e=>e.type==='HEIGHT_UPDATE').length)value=await getM3Live(run.value.runId);await accept(value,value.frames.length>0);}
 catch(e){pause();error.value=e instanceof Error?e.message:String(e);}}
 if(!disposed)pollSnapshot();
 },2000);}
onMounted(()=>{mountTask=(async()=>{if(route.query.liveRun){await reload();if(run.value&&startPlayback.delete(run.value.runId)){playing.value=true;schedule();}}pollSnapshot();})();});
watch(()=>route.query.liveRun,id=>{if(typeof id==='string'&&id!==run.value?.runId)void reload();});
watch(playing,value=>emit('playback',value));watch(error,value=>emit('error',value));
onBeforeUnmount(()=>{disposed=true;pause();if(pollTimer)clearTimeout(pollTimer);observer?.disconnect();chart?.dispose();});
async function acceptSnapshot(value:M3LiveRun){await accept(value,value.frames.length>0);}
defineExpose({pause,reload,acceptSnapshot,startAutomatic});
</script>
<style scoped>
.live-workbench{margin:22px 0 30px;background:#fffdf7;border:1px solid #ddd7c8;color:#444c37;padding:26px}.live-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.eyebrow{font-size:11px;letter-spacing:2px;color:#7c865e}.live-heading h2{font-size:23px;margin:9px 0}.live-heading p,p{font-size:12px;color:#8d8472;line-height:1.8}.live-state{white-space:nowrap;font-size:11px;padding:7px 12px;background:#eee9dc;color:#8c8068}.live-state.on{background:#e2ead6;color:#57723e}.live-controls{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}.live-controls select,button{font:inherit;font-size:12px;border:1px solid #dcd5c6;background:#fcfaf2;color:#686b53;padding:8px 11px;cursor:pointer}.live-controls .primary{background:#586e48;color:#fff;border-color:#586e48}button:disabled{opacity:.45;cursor:default}.live-error{background:#f5e7dc;padding:12px;color:#a46b48}.live-empty{padding:35px 20px;text-align:center;border-top:1px solid #e4ddce}.stream-clock{display:flex;align-items:baseline;justify-content:space-between;gap:12px;border-top:1px solid #e3ddcf;padding-top:20px}.stream-clock strong{font-size:19px;font-weight:500}.stream-clock span{font-size:11px;color:#9a907c}.live-metrics{display:grid;grid-template-columns:repeat(3,1fr);margin:22px 0;gap:20px}.live-metrics span{display:block;font-size:11px;color:#9b907b}.live-metrics strong{display:block;font-size:28px;font-weight:500;margin-top:10px}.live-metrics small{font-size:12px;margin-left:7px}.stream-progress{width:100%;height:3px;accent-color:#617548;display:block}.update-event{display:flex;align-items:baseline;flex-wrap:wrap;gap:12px;margin:17px 0;background:#edf1e3;padding:13px 16px}.update-event span,.update-event small{font-size:11px;color:#809062}.update-event strong{font-weight:500;font-size:17px}.update-event b{color:#a56c44}.live-chart{height:330px;width:100%;margin:22px 0}.live-bottom{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(0,1fr);gap:30px;border-top:1px solid #ded8c9;padding-top:20px}h3{font-size:15px;font-weight:500;margin:0 0 12px}.risk-section>header{display:flex;justify-content:space-between}.risk-section>header>span{font-size:11px;color:#9b907c}.live-alert{border-left:3px solid #be9d55;background:#f4efe2;padding:12px 14px;margin:9px 0}.live-alert.high{border-color:#b57048;background:#f5e9dd}.live-alert.info{border-color:#9aab89;background:#eef2e7}.live-alert>div{display:flex;justify-content:space-between;gap:12px}.live-alert b{font-size:12px;font-weight:500}.live-alert span,.live-alert small{font-size:11px;color:#9a886b}.live-alert p{margin:5px 0;font-size:11px}.live-alert button{font-size:11px;margin-top:8px;padding:5px 8px}.quiet{font-size:12px}.exposure{margin:15px 0;display:grid;gap:7px;font-size:11px;color:#9a907b}.exposure b{font-weight:500;color:#686e54}.reference-section dl>div{display:flex;justify-content:space-between;font-size:12px;padding:9px 0;border-bottom:1px solid #ece6d8}.reference-section dt{color:#968b75}.reference-section dd{margin:0}.reference-note{font-size:11px}.live-scores{border-top:1px solid #ded8c9;margin-top:22px;padding-top:20px}.live-scores table{width:100%;font-size:12px;border-collapse:collapse}.live-scores th,.live-scores td{padding:12px;text-align:right;border-bottom:1px solid #e5dece}.live-scores th:first-child,.live-scores td:first-child{text-align:left}.live-scores th{color:#978e7c;font-size:11px;font-weight:400}.muted{color:#a4977e}.stream-details{margin-top:16px;font-size:11px;color:#978b73;line-height:1.8}.stream-details summary{cursor:pointer}.stream-details ul{padding-left:18px}.hash{word-break:break-all;font-size:11px}.live-workbench :deep(.scenario-panel.inline){position:static;width:100%;max-height:none;margin-top:22px;box-shadow:none;display:block;padding:20px;border:1px solid #dedfce}.compact{position:absolute;top:112px;left:20px;z-index:9;width:300px;max-height:calc(100% - 195px);overflow:auto;margin:0;padding:19px;background:rgba(255,253,245,.97);box-shadow:0 12px 35px #303b2426}.compact .live-heading h2{font-size:18px}.compact .eyebrow{font-size:11px;letter-spacing:1px}.compact .live-controls{gap:6px}.compact .live-controls select,.compact button{font-size:11px;padding:6px 7px}.compact .live-heading{gap:8px}.compact .live-state{font-size:11px;padding:5px 7px}.compact .stream-clock{display:grid;gap:6px}.compact .stream-clock strong{font-size:15px}.compact .live-metrics{gap:8px;margin:14px 0}.compact .live-metrics strong{font-size:22px}.compact .live-metrics small{font-size:11px;margin-left:2px}.compact .live-metrics span{font-size:11px}.compact .update-event{padding:10px;font-size:11px;gap:7px}.compact .update-event strong{font-size:14px}.compact .live-bottom{grid-template-columns:1fr;gap:0;padding-top:13px}.compact .live-alert{padding:8px 10px}.compact .live-alert>div{gap:5px}.compact h3{font-size:12px}.compact .live-empty{padding:20px 0}.compact .stream-details{font-size:11px}@media(max-width:950px){.live-bottom{grid-template-columns:1fr}.stream-clock{flex-wrap:wrap}.live-workbench:not(.compact){padding:18px}.live-metrics{gap:10px}.live-metrics strong{font-size:22px}}
</style>
