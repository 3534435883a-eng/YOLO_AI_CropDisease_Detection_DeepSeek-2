<template>
 <main class="calibration-page">
  <header class="page-head">
   <div><span class="eyebrow">HORTI-M3 / MODEL LAB</span><h1>模型校准与观测纠偏</h1><p>历史参数学习 → 保留年份评价 → 观测到达后的状态修正</p></div>
   <div class="head-actions"><el-button :disabled="!result" @click="exportResult">导出历史校准</el-button><el-button :loading="running" :disabled="!summary" @click="run">重新校准参数</el-button></div>
  </header>
  <div v-if="error" class="notice error">{{ error }} <button @click="load">重新加载</button></div>
  <section class="facts">
   <div><span>已导入株高</span><strong>{{ summary?.quality.totalHeightRecords ?? '—' }}<small>条</small></strong><p>三年原始目录 · 重复文件已排除</p></div>
   <div><span>独立测量日期</span><strong>{{ summary?.quality.measurementDates ?? '—' }}<small>个</small></strong><p>每日插值不增加实测样本</p></div>
   <div><span>校准批次</span><strong>2023 / 2024</strong><p>广辉201 · CK · 每批首日初始化</p></div>
   <div><span>历史参数评价季</span><strong>2025</strong><p>在线回放按到达观测更新状态</p></div>
  </section>
  <CalibrationStory embedded :run="liveRun" />
  <M3LiveWorkbench @run="liveRun=$event" />
  <el-button @click="showHistory=!showHistory">{{ showHistory?'收起历史跨年评价':'查看历史跨年评价与参数' }}</el-button>
  <div v-if="!result&&showHistory" class="empty-state"><h2>{{ loading?'正在读取观测资料':'观测已准备，等待创建对照' }}</h2><p>{{ resultError || '创建后显示原模拟、M3观测与修正模拟；结果按版本保存。' }}</p></div>
  <template v-if="result&&showHistory">
   <section class="result-bar"><span class="status" :class="{better: result.status==='HELD_OUT_YEAR_IMPROVEMENT'}">{{ result.status==='HELD_OUT_YEAR_IMPROVEMENT'?'2025保留期较原模型改善':'2025保留期未较原模型改善' }}</span><span>{{ result.version }}</span><span>{{ result.createdAt.slice(0,19).replace('T',' ') }}</span></section>
   <div class="workbench">
    <section class="plot-section">
     <div class="plot-head"><div class="year-tabs"><button v-for="s in result.seasons" :key="s.year" :class="{active:year===s.year}" @click="year=s.year">{{ s.year }} <small>{{ s.role==='holdout'?'保留评价':'校准' }}</small></button></div><el-select v-model="plant" class="plant-select"><el-option label="批次均值" value="mean"/><el-option v-for="id in season?.cohort.plantIds" :key="id" :label="id" :value="id"/></el-select></div>
     <div class="chart-caption"><h2>株高随环境驱动生长</h2><span>单位 cm · {{ season?.startDate }} 至 {{ season?.endDate }}</span></div>
     <div ref="chartRef" class="growth-chart"></div>
     <p class="plot-note">点为原始测量；线为同一环境下的模型预测。初态由首日株高建立，后续观测不重置预测状态。</p>
     <div class="score-head"><h2>{{ year===2025?'冻结参数后的年份评价':'训练批次拟合误差' }}</h2><span>初态排除 · {{ season?.scores.corrected.n }} 对配对点</span></div>
     <table class="score-table"><thead><tr><th>算法</th><th>MAE / cm</th><th>RMSE / cm</th><th>Bias / cm</th></tr></thead><tbody><tr v-for="item in scoreRows" :key="item.key" :class="{candidate:item.key==='corrected'}"><td><i :style="{background:item.color}"/>{{ item.name }}</td><td>{{ fmt(item.score?.mae) }}</td><td>{{ fmt(item.score?.rmse) }}</td><td>{{ fmt(item.score?.bias) }}</td></tr></tbody></table>
    </section>
    <aside class="mechanism">
     <span class="eyebrow">SIMULATION → CORRECTION</span><h2>从节间到株高</h2>
     <ol><li><b>积累热量</b><p>温度超过基温的部分累积为 GDD，推进植株发育。</p></li><li><b>生成与伸长节间</b><p>按热时间间隔出现节间，用 Logistic 曲线计算节间伸长，长度相加得到株高。</p></li><li><b>用历史观测修正</b><p>仅使用2023/2024校准两个参数，按年份平衡误差，再冻结到2025。</p></li></ol>
     <dl><div><dt>节间出现间隔</dt><dd>{{ fmt(result.parameters.phyllochronGdd) }} °Cd</dd></div><div><dt>最大节间长度</dt><dd>{{ fmt(result.parameters.maxInternodeLengthCm) }} cm</dd></div><div><dt>固定伸长斜率 k</dt><dd>{{ result.parameters.elongationK }}</dd></div><div><dt>固定拐点热时间</dt><dd>{{ result.parameters.midpointGdd }} °Cd</dd></div><div><dt>基温假设</dt><dd>{{ result.parameters.baseTemperatureC }} °C</dd></div></dl>
     <p v-if="result.parameters.atBound" class="bound-note">拟合参数到达边界，需更多器官观测或重新核对参数范围。</p>
     <p class="mechanism-foot">这是参考功能结构模型建立的简化株高机制；叶面积、果实与设备响应尚未校准。结果未自动替换平台默认模型。</p>
    </aside>
   </div>
   <section class="quality-section">
    <header><h2>输入质量与来源</h2><span>缺测统一补齐，逐时段保留来源</span></header>
    <div class="quality-grid"><div v-for="s in result.seasons" :key="s.year"><b>{{ s.year }} / {{ s.cohort.plantIds.length }}株CK</b><p>修正 RMSE {{ fmt(s.scores.corrected.rmse) }} cm · 原模型 {{ fmt(s.scores.original.rmse) }} cm</p><p>{{ s.quality.selectedHeightRecords }}条选定株高 · {{ s.quality.measurementDates.length }}个日期</p><strong>{{ fmt(s.quality.inputCoverage.observedCoveragePct,1) }}% <small>主测点覆盖</small></strong><p>参考测点 {{ s.quality.inputCoverage.referenceSlots }} 时段 · 训练年份均值 {{ s.quality.inputCoverage.meanSlots }} 时段</p></div></div>
    <details><summary>假设、模型边界与复核资料</summary><ul><li v-for="note in result.notes" :key="note">{{ note }}</li></ul><div class="reference-links"><a :href="result.paperUrl" target="_blank" rel="noopener">M3数据论文 ↗</a><a :href="result.sourceUrl" target="_blank" rel="noopener">数据原始发布 ↗</a><a href="https://doi.org/10.1093/insilicoplants/diaf022" target="_blank" rel="noopener">节间模型参考 ↗</a></div><p class="hash">输入SHA256：{{ result.observationsSha256 }}</p></details>
   </section>
  </template>
 </main>
</template>

<script setup lang="ts" name="modelCalibration">
import { computed,nextTick,onMounted,onBeforeUnmount,ref,watch } from 'vue';
import {useRouter} from 'vue-router';
import * as echarts from 'echarts';
import M3LiveWorkbench from './M3LiveWorkbench.vue';
import CalibrationStory from '../digitalTwin/components/CalibrationStory.vue';
import type {M3LiveRun} from '/@/api/m3/live';
import {calibrateM3,getM3Result,getM3Summary,formatM3Number as fmt,type M3Result,type M3Summary} from '/@/api/m3';
const router=useRouter(),summary=ref<M3Summary|null>(null),result=ref<M3Result|null>(null);
const year=ref(2025),plant=ref('mean'),loading=ref(false),running=ref(false),error=ref(''),resultError=ref('');
const showHistory=ref(false);
const liveRun=ref<M3LiveRun|null>(null);
const chartRef=ref<HTMLDivElement>(),season=computed(()=>result.value?.seasons.find(s=>s.year===year.value));
let chart:echarts.ECharts|undefined,observer:ResizeObserver|undefined;
const scoreRows=computed(()=>[
 {key:'original',name:'原模拟机制',color:'#9b989f',score:season.value?.scores.original},
 {key:'corrected',name:'校准节间机制',color:'#617347',score:season.value?.scores.corrected},
 {key:'linear',name:'线性积温基线',color:'#bc9564',score:season.value?.scores.linear}
]);
async function load(){
 loading.value=true;error.value='';
 const [a,b]=await Promise.allSettled([getM3Summary(),getM3Result()]);
 if(a.status==='fulfilled')summary.value=a.value;else error.value=String(a.reason?.message||a.reason);
 if(b.status==='fulfilled'){result.value=b.value;resultError.value='';}else resultError.value=String(b.reason?.message||b.reason);
 loading.value=false;await nextTick();render();
}
async function run(){
 running.value=true;error.value='';
 try{result.value=await calibrateM3();resultError.value='';await nextTick();render();}
 catch(e){error.value=e instanceof Error?e.message:String(e);}
 finally{running.value=false;}
}
function render(){
 if(!chartRef.value||!season.value)return;
 if(!chart){chart=echarts.init(chartRef.value);observer=new ResizeObserver(()=>chart?.resize());observer.observe(chartRef.value);}
 const frames=season.value.series;
 const points=frames.map(f=>plant.value==='mean'?f:f.plants.find(p=>p.plantId===plant.value));
 chart.setOption({animationDuration:350,color:['#9b989f','#617347','#bd8c47'],
  tooltip:{trigger:'axis',valueFormatter:(value:unknown)=>typeof value==='number'?value.toFixed(1)+' cm':'—'},
  legend:{top:5,right:20,icon:'circle',textStyle:{color:'#665e51'}},
  grid:{top:48,left:55,right:22,bottom:50},
  xAxis:{type:'category',data:frames.map(f=>f.date),axisLabel:{formatter:(v:string)=>v.slice(5),color:'#8a8171'},axisLine:{lineStyle:{color:'#e0dace'}}},
  yAxis:{type:'value',name:'株高 / cm',nameTextStyle:{color:'#8a8171'},axisLabel:{color:'#8a8171'},splitLine:{lineStyle:{color:'#eee9df'}}},
  dataZoom:[{type:'inside'}],
  series:[
   {name:'原模拟',type:'line',data:points.map(p=>p?.originalHeightCm??null),showSymbol:false,lineStyle:{width:2,type:'dashed'}},
   {name:'修正模拟',type:'line',data:points.map(p=>p?.correctedHeightCm??null),showSymbol:false,lineStyle:{width:3}},
   {name:'M3原始观测',type:'scatter',data:points.map(p=>p?.observedHeightCm??null),symbolSize:9}
  ]},true);
}
function exportResult(){if(!result.value)return;const url=URL.createObjectURL(new Blob([JSON.stringify(result.value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=result.value.version+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
watch(year,()=>{plant.value='mean';});watch([year,plant],async()=>{await nextTick();render();});
watch(showHistory,async(value)=>{if(value){await nextTick();render();}else{observer?.disconnect();chart?.dispose();chart=undefined;observer=undefined;}});
onMounted(load);onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose();});
</script>
<style scoped>
.calibration-page{padding:28px 32px;background:#f7f5ef;min-height:100%;color:#3f4336}
.page-head{display:flex;justify-content:space-between;align-items:center;gap:24px;padding-bottom:26px;border-bottom:1px solid #ddd8ca}.eyebrow{font-size:11px;letter-spacing:2px;color:#7b835b}h1{font-size:29px;font-weight:600;margin:8px 0}h2{font-size:17px;font-weight:600}p{color:#8a8272;font-size:13px;line-height:1.8}.head-actions{display:flex;gap:2px}.facts{display:grid;grid-template-columns:repeat(4,1fr);margin:24px 0 28px}.facts>div{padding:0 24px;border-right:1px solid #ddd8ca}.facts>div:first-child{padding-left:0}.facts>div:last-child{border:0}.facts span{font-size:12px;color:#827b6b}.facts strong{display:block;font-size:25px;font-weight:500;margin-top:10px}.facts small{font-size:12px;margin-left:6px}.facts p{margin-top:8px}.result-bar{display:flex;gap:16px;align-items:center;font-size:11px;color:#9a907f;margin-bottom:16px;flex-wrap:wrap}.status{background:#efe2cf;padding:7px 11px;color:#966f3c}.status.better{background:#e7ecdf;color:#617347}.workbench{display:grid;grid-template-columns:minmax(0,1fr) 310px;background:#fffdf8;border:1px solid #e5dfd1}.plot-section{padding:22px 26px;min-width:0}.plot-head{display:flex;justify-content:space-between;align-items:center;gap:16px}.year-tabs{display:flex;gap:20px}.year-tabs button{border:0;background:none;padding:9px 0;font-size:17px;color:#aaa18f;border-bottom:2px solid transparent;cursor:pointer}.year-tabs button.active{color:#576943;border-color:#576943}.year-tabs small{font-size:10px;margin-left:5px}.plant-select{width:135px}.chart-caption,.score-head{display:flex;justify-content:space-between;align-items:center;margin:22px 0 0;gap:12px}.chart-caption span,.score-head span{color:#9b917f;font-size:11px}.growth-chart{height:360px;width:100%}.plot-note{font-size:11px;border-bottom:1px solid #e5dfd1;padding-bottom:20px}.score-table{width:100%;border-collapse:collapse;margin-top:14px;font-size:13px}.score-table th{text-align:right;color:#978e7c;font-weight:400;font-size:11px;padding:10px}.score-table td{padding:13px 10px;text-align:right;border-top:1px solid #eee8dc;font-variant-numeric:tabular-nums}.score-table td:first-child,.score-table th:first-child{text-align:left}.score-table i{display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:9px}.candidate{background:#f2f5eb}.mechanism{border-left:1px solid #e5dfd1;padding:26px;background:#f2f0e5}.mechanism h2{font-size:23px;margin:12px 0 24px}.mechanism ol{padding-left:20px;font-size:13px}.mechanism li{padding-left:7px;margin-bottom:20px}.mechanism li::marker{color:#98a078}.mechanism p{font-size:12px}.mechanism dl{border-top:1px solid #dcd8c8;padding-top:15px}.mechanism dl>div{display:flex;justify-content:space-between;font-size:11px;margin:13px 0}.mechanism dt{color:#908878}.mechanism dd{margin:0;font-variant-numeric:tabular-nums}.bound-note{color:#986932!important}.mechanism-foot{border-top:1px solid #dcd8c8;padding-top:18px}.quality-section{margin-top:28px}.quality-section header{display:flex;justify-content:space-between;align-items:center}.quality-section header span{font-size:12px;color:#948b79}.quality-grid{display:grid;grid-template-columns:repeat(3,1fr);border-top:1px solid #ddd8ca;border-bottom:1px solid #ddd8ca;margin:14px 0}.quality-grid>div{padding:20px 24px;border-right:1px solid #ddd8ca}.quality-grid>div:first-child{padding-left:0}.quality-grid>div:last-child{border:0}.quality-grid b{font-size:13px;font-weight:500}.quality-grid strong{font-size:21px;font-weight:500}.quality-grid small{font-size:11px;color:#9a907e}.quality-section details{font-size:12px;line-height:2;color:#8b8270;padding:12px 0}.quality-section summary{cursor:pointer;color:#617347}.reference-links{display:flex;gap:24px;margin-top:15px}.reference-links a{color:#617347}.hash{font-size:10px;overflow-wrap:anywhere}.empty-state{min-height:260px;display:flex;flex-direction:column;justify-content:center;align-items:center;border:1px dashed #dcd5c3}.notice{padding:14px;background:#f4e7dd;margin-top:18px;color:#986348;font-size:13px}.notice button{background:none;border:0;text-decoration:underline;color:inherit;cursor:pointer}@media(max-width:1150px){.workbench{grid-template-columns:minmax(0,1fr) 270px}.calibration-page{padding:22px}.head-actions{flex-wrap:wrap}.year-tabs{gap:12px}}@media(max-width:850px){.workbench{grid-template-columns:1fr}.mechanism{border-left:0;border-top:1px solid #e5dfd1}.facts{grid-template-columns:repeat(2,1fr);gap:20px}.page-head{flex-wrap:wrap}.quality-grid{grid-template-columns:1fr}}
</style>

