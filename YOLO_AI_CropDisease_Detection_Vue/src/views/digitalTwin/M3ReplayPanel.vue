<template>
 <aside class="m3-replay">
  <header><span>三年观测 / 生长回放</span><button @click="router.push('/modelCalibration')">完整对照 ↗</button></header>
  <p v-if="error" class="error">{{ error }}</p>
  <template v-if="result && season && current">
   <div class="selection"><select v-model.number="year" @change="reset"><option v-for="s in result.seasons" :key="s.year" :value="s.year">{{ s.year }} · {{ s.role==='holdout'?'保留评价':'校准' }}</option></select><select v-model="track" @change="reset"><option value="corrected">修正模拟</option><option value="original">原模拟</option><option value="observed">M3原始测量</option></select></div>
   <div class="height"><strong>{{ fmt(height,1) }}<small>cm</small></strong><span>批次均值 · {{ current.date }}</span></div>
   <p class="data-note">{{ track==='observed'?'所选日期有原始株高测量':'冻结参数预测，未在观测日期重置状态' }}</p>
   <dl><div><dt>气温</dt><dd>{{ fmt(current.environment.temperatureC,1) }} °C</dd></div><div><dt>相对湿度</dt><dd>{{ fmt(current.environment.airHumidityPct,1) }} %</dd></div><div><dt>CO₂</dt><dd>{{ fmt(current.environment.co2Ppm,0) }} ppm</dd></div><div><dt>累计热量</dt><dd>{{ fmt(current.gdd,1) }} °Cd</dd></div></dl>
   <div class="timeline"><button @click="toggle">{{ playing?'暂停':'播放' }}</button><input v-model.number="index" aria-label="M3回放日期" type="range" min="0" :max="frames.length-1" @input="pause"/><span>{{ index+1 }}/{{ frames.length }}</span></div>
   <small class="origin">{{ current.environment.estimated?'环境含估计值':'主测点环境' }} · {{ current.environment.origin }}</small>
   <p class="limit">株高用于所选批次的代表植株展示；整棚840株不等于样本量。叶果、设备为示意，设备关闭不表示原试验设备状态。</p>
  </template>
  <p v-else-if="!error">正在载入已保存的M3结果…</p>
 </aside>
</template>
<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,onDeactivated,ref,watch} from 'vue';
import {useRouter} from 'vue-router';
import {getM3Result,formatM3Number as fmt,type M3Result,type M3ReplayState} from '/@/api/m3';
const emit=defineEmits<{(e:'frame',value:M3ReplayState):void}>();
const router=useRouter(),result=ref<M3Result|null>(null),error=ref(''),year=ref(2025),index=ref(0),track=ref('corrected'),playing=ref(false);
const season=computed(()=>result.value?.seasons.find(s=>s.year===year.value));
const frames=computed(()=>season.value?.series.filter(f=>track.value!=='observed'||f.observedHeightCm!==null)||[]);
const current=computed(()=>frames.value[index.value]);
const height=computed(()=>{const f=current.value;return f?(track.value==='corrected'?f.correctedHeightCm:track.value==='original'?f.originalHeightCm:f.observedHeightCm):null;});
let timer:ReturnType<typeof setInterval>|undefined;
function pause(){playing.value=false;if(timer)clearInterval(timer);timer=undefined;}
function reset(){pause();index.value=0;}
function toggle(){if(playing.value){pause();return;}if(index.value>=frames.value.length-1)index.value=0;playing.value=true;timer=setInterval(()=>{if(index.value>=frames.value.length-1){pause();return;}index.value++;},700);}
function push(){if(current.value&&height.value!==null)emit('frame',{frame:current.value,heightCm:height.value,year:year.value,track:track.value});}
async function reload(){pause();error.value='';try{result.value=await getM3Result();reset();push();}catch(e){error.value=e instanceof Error?e.message:String(e);result.value=null;}}
watch([current,height],push);onMounted(reload);onDeactivated(pause);onBeforeUnmount(pause);defineExpose({reload,pause});
</script>
<style scoped>
.m3-replay{position:absolute;top:116px;left:20px;width:310px;padding:22px;background:rgba(255,253,245,.96);border:1px solid #dbd5c2;box-shadow:0 12px 40px #363b2420;z-index:9;color:#4c523d;border-radius:3px}.m3-replay header{display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:600;margin-bottom:19px}.m3-replay button{cursor:pointer;background:#e9eddf;color:#5f7148;border:0;padding:6px 9px;font-size:11px}.selection{display:flex;gap:7px}.selection select{border:1px solid #dcd8c8;padding:7px;background:#fffdf7;color:#706b5c;font-size:11px;max-width:150px}.height{margin:25px 0 14px}.height strong{font-size:40px;font-weight:500;display:block;line-height:1.2}.height small{font-size:13px;margin-left:8px}.height span,.data-note{font-size:11px;color:#928775}.data-note{margin:15px 0;line-height:1.6}.m3-replay dl{border-top:1px solid #e3ddcc;border-bottom:1px solid #e3ddcc;padding:10px 0}.m3-replay dl>div{display:flex;justify-content:space-between;font-size:12px;margin:10px 0}.m3-replay dt{color:#9b927e}.m3-replay dd{margin:0}.timeline{display:flex;align-items:center;gap:9px;margin-top:18px}.timeline input{width:150px;accent-color:#6d7d4b}.timeline span{font-size:10px;color:#99907b}.origin{display:block;margin-top:15px;color:#8b826e;font-size:10px}.limit{font-size:10px;line-height:1.7;color:#9a907e;margin-top:13px}.error{color:#a16949;font-size:12px}
</style>
