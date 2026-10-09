<template>
 <div class="environment-page">
  <header class="page-toolbar"><div><p class="eyebrow">M3历史观测 / 当前虚拟场景</p><h2>番茄温室环境监测</h2><p class="subtitle">{{ greenhouse.run?.current.at.replace('T',' ') || '尚未接入当前大棚' }}</p></div><div class="toolbar-actions"><el-button :loading="greenhouse.busy" @click="greenhouse.refreshRun(true)">刷新状态</el-button><el-button type="primary" @click="openTwin()">进入当前大棚</el-button></div></header>
  <GreenhouseContext />
  <el-alert v-if="greenhouse.error" title="当前状态读取失败" :description="greenhouse.error" type="error" :closable="false" show-icon class="service-alert" />
  <el-alert v-else-if="!greenhouse.run" title="尚未接入大棚运行" description="进入大棚接入M3回放后，各模块显示同一运行的环境和设备。" type="info" :closable="false" show-icon class="service-alert" />
  <section class="metrics-grid" aria-label="环境指标"><article v-for="metric in metrics" :key="metric.code" class="metric-item"><div class="metric-heading"><i :class="`iconfontjs ${metric.icon}`"></i><span>{{ metric.label }}</span></div><strong>{{ metric.value }}</strong><small>{{ metric.hint }}</small></article></section>
  <section class="content-grid"><div class="panel device-panel"><div class="panel-header"><div><p class="eyebrow">执行层</p><h3>虚拟设备协同</h3></div><el-tag effect="plain" type="info">仅模拟执行</el-tag></div><div class="device-grid"><article v-for="device in devices" :key="device.code" class="device-item"><div><div class="device-name">{{ device.name }}</div><p>{{ device.enabled ? '执行中 · 功率 '+Math.round(device.duty*100)+'%' : '虚拟待机' }}</p><el-tag size="small" :type="device.healthy?'success':'danger'">{{ device.healthy?'可用':'故障' }}</el-tag></div><div class="device-control"><el-button link type="primary" @click="openTwin(device.code)">在大棚查看作用 ↗</el-button></div></article></div><p v-if="!devices.length" class="empty-copy">当前没有设备快照。</p></div>
   <aside class="panel strategy-panel"><p class="eyebrow">AI处置与反馈</p><h3>当前方案</h3><p class="strategy-copy">{{ scenario?.decision.plan?.summary || '等待当前任务的分析与处置方案。' }}</p><el-tag effect="plain" :type="riskLevel==='HIGH'?'danger':riskLevel==='MEDIUM'?'warning':'info'">{{ riskLabel }}</el-tag><p class="strategy-copy">{{ scenario?.decision.plan?.check || '环境风险与根区响应属于模型推演；叶片症状需要人工复查。' }}</p><el-divider /><p class="disclaimer">环境读数为M3历史回放基础上的虚拟干预结果。根区水分为简化模型估计，未作为现场传感器实测。</p></aside>
  </section>
 </div>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {useGreenhouseStore} from '/@/stores/greenhouse';
import GreenhouseContext from '/@/components/GreenhouseContext.vue';
const route=useRoute(),router=useRouter(),greenhouse=useGreenhouseStore();
const unsubscribe=greenhouse.subscribe();onBeforeUnmount(unsubscribe);
if(typeof route.query.liveRun==='string')void greenhouse.attachRun(route.query.liveRun);
const scenario=computed(()=>greenhouse.run?.current.scenario),environment=computed(()=>scenario.value?.environment||greenhouse.run?.current.environment);
const fmt=(value:unknown,unit:string,digits=1)=>typeof value==='number'&&Number.isFinite(value)?value.toFixed(digits)+unit:'—';
const metrics=computed(()=>[
 {code:'temperature',icon:'icon-daqiwendu',label:'室内温度',value:fmt(environment.value?.temperatureC,' ℃'),hint:scenario.value?'虚拟干预推演':'历史观测回放'},
 {code:'humidity',icon:'icon-kongqishidu_kongqishidu',label:'空气湿度',value:fmt(environment.value?.airHumidityPct,'%'),hint:scenario.value?'虚拟干预推演':'历史观测回放'},
 {code:'soil',icon:'icon-turangshidu',label:'根区体积含水率',value:fmt(scenario.value?.agronomy?.soilMoistureVwcPct,'%'),hint:'根区模型估计 / 参数未现场标定'},
 {code:'co2',icon:'icon-eryanghuatan',label:'CO₂',value:fmt(environment.value?.co2Ppm,' ppm',0),hint:scenario.value?'虚拟干预推演':'历史观测回放'},
 {code:'light',icon:'icon-guangzhaoqiangdu',label:'光照',value:fmt(scenario.value?.environment.ppfd,' μmol/m²/s',0),hint:'光照转换与设备响应估计'},
 {code:'vpd',icon:'icon-huanjingjiance',label:'VPD',value:fmt((scenario.value?.risk||greenhouse.run?.current.risk)?.vpdKpa,' kPa',2),hint:'温湿度计算 / 非直接实测'},
]);
const deviceNames:Record<string,string>={HEATING:'加热',IRRIGATION:'滴灌',VENTILATION:'侧窗',SUPPLEMENTAL_LIGHT:'补光',SHADE:'遮阳',CO2_SUPPLY:'CO₂供给',ROOF_VENT:'天窗',EXHAUST_FAN:'排风',COOLING_PAD:'湿帘',CIRCULATION_FAN:'环流'};
const devices=computed(()=>Object.entries(scenario.value?.devices||{}).map(([code,enabled])=>({code,enabled,name:deviceNames[code]||code,duty:scenario.value?.deviceDuty[code]||0,healthy:scenario.value?.deviceHealth[code]!==false})));
const riskLevel=computed(()=>scenario.value?.risk.riskLevel||greenhouse.run?.current.risk.riskLevel||'');
const riskLabel=computed(()=>({HIGH:'当前环境高风险',MEDIUM:'当前环境需关注',LOW:'当前已配置风险较低'}[riskLevel.value]||'等待风险分析'));
const openTwin=(device?:string)=>router.push({path:'/digitalTwin',query:{...greenhouse.linkedQuery(),...(device?{device}:{})}});
</script>
<style scoped lang="scss">
.environment-page { padding: 28px clamp(20px, 2.5vw, 40px); color: #2c3028; background: #f7f3ea; min-height: calc(100vh - 60px); }
.service-alert { margin: 14px 0; }
.page-toolbar, .panel-header, .toolbar-actions, .device-control { display: flex; align-items: center; }
.page-toolbar { justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.toolbar-actions { flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.greenhouse-select { width: 132px; }
.eyebrow { margin: 0 0 4px; color: #6d7c72; font-size: 12px; }
h2, h3 { margin: 0; letter-spacing: 0; }
h2 { font-family: Georgia, 'Noto Serif SC', serif; font-size: 28px; }
h3 { font-size: 17px; }
.subtitle { margin: 6px 0 0; color: #607067; font-size: 13px; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
.metric-item, .panel { border: 1px solid #ded5c7; border-radius: 3px; background: #fffdf8; }
.metric-item { min-height: 118px; padding: 14px; }
.metric-heading { display: flex; align-items: center; gap: 8px; color: #5a6d60; font-size: 13px; }
.metric-heading i { color: #2d8a54; font-size: 19px; }
.metric-item strong { display: block; margin: 16px 0 5px; font-size: 23px; font-weight: 650; }
.metric-item small { color: #809087; font-size: 12px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .85fr); gap: 16px; }
.panel { padding: 18px; }
.panel-header { justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.device-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.device-item { display: flex; justify-content: space-between; gap: 12px; padding: 13px; border: 1px solid #e2dbcf; border-radius: 3px; background: #faf7f0; }
.empty-copy { margin: 8px 0 0; color: #817969; font-size: 13px; }
.device-name { font-size: 14px; font-weight: 600; }
.device-item p { margin: 5px 0 8px; color: #68786f; font-size: 12px; }
.device-item .el-tag + .el-tag { margin-left: 5px; }
.device-control { flex-direction: column; align-items: flex-end; justify-content: center; gap: 8px; white-space: nowrap; }
.strategy-copy { min-height: 70px; color: #435349; line-height: 1.7; font-size: 14px; }
.risk-row { margin: 15px 0; }
.risk-row span { display: flex; justify-content: space-between; margin-bottom: 7px; color: #617168; font-size: 13px; }
.risk-row b { color: #3d433a; font-variant-numeric: tabular-nums; }
.disclaimer { margin: 0; color: #718178; font-size: 12px; line-height: 1.65; }
@media (max-width: 1000px) { .metrics-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .content-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .environment-page { padding: 14px; } .page-toolbar { align-items: flex-start; flex-direction: column; } .toolbar-actions { justify-content: flex-start; } .metrics-grid, .device-grid { grid-template-columns: 1fr; } .device-item { align-items: center; } }
</style>
