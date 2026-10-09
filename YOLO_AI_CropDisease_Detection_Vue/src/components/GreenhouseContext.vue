<template>
 <aside class="greenhouse-context-strip" aria-label="当前农情任务">
  <div><strong>{{ store.task?.title || '番茄大棚' }}</strong><span v-if="store.run">{{ store.run.current.at.replace('T',' ') }} · {{ store.run.playback?.playing ? '服务器回放中' : '已暂停' }}</span><span v-else>尚未接入大棚运行</span></div>
  <p>{{ store.run ? 'M3历史观测回放 / 虚拟干预与根区状态为模型推演' : '进入大棚接入观测，再关联识别、问答与规划' }}</p>
  <div class="context-links"><button @click="open('/digitalTwin')">当前大棚 ↗</button><button v-if="store.run" @click="open('/agentChat')">关联助手 ↗</button><button v-if="store.task" @click="open('/agentSimulation')">同任务规划 ↗</button></div>
  <small v-if="store.error" class="context-error">{{ store.error }}</small>
 </aside>
</template>
<script setup lang="ts">
import { onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { useGreenhouseStore } from '/@/stores/greenhouse';
const store = useGreenhouseStore(), router = useRouter(), unsubscribe = store.subscribe();
onBeforeUnmount(unsubscribe);
const open = (path: string) => router.push({ path, query: store.linkedQuery() });
</script>
<style scoped>
.greenhouse-context-strip{display:flex;flex-wrap:wrap;align-items:center;gap:10px 20px;padding:13px 18px;margin:0 0 18px;border:1px solid var(--agri-line,#ded2be);background:var(--agri-surface,#fcf8ef);color:var(--agri-ink,#352d27)}.greenhouse-context-strip strong{font-size:13px}.greenhouse-context-strip span{display:block;font-size:11px;color:#76695c;margin-top:5px}.greenhouse-context-strip p{flex:1;margin:0;font-size:11px;color:#76695c;line-height:1.6}.context-links{display:flex;gap:10px}.context-links button{border:0;background:none;padding:5px 0;font:inherit;font-size:11px;color:#586e48;cursor:pointer}.context-error{flex-basis:100%;color:#a45b41}
</style>
