import { computed, ref, shallowRef } from 'vue';
import { defineStore } from 'pinia';
import { getM3Live, startM3Live, setM3Playback, type M3LiveRun, type M3LiveFrame, type M3LiveEvent } from '/@/api/m3/live';
import { createFarmTask, getFarmTask, updateFarmTask, addFarmEvidence, addFarmTurn, addFarmAction, addFarmObservation, updateFarmAction,
 type FarmTask, type FarmEvidence, type FarmTurn, type FarmAction, type FarmObservationInput } from '/@/api/agent/tasks';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const eventKey = (e: M3LiveEvent) => JSON.stringify([e.type,e.at,e.plantId,e.code,e.level,e.beforeCm,e.afterCm]);
const savedRun = () => sessionStorage.getItem('m3ActiveRun') || localStorage.getItem('m3ActiveRun') || '';
const savedTask = (runId: string) => localStorage.getItem('farmTask:' + (runId || 'independent')) || '';

/** Owns the selected run and the single read-only polling loop. The server owns the simulation clock. */
export const useGreenhouseStore = defineStore('greenhouse', () => {
 const run = shallowRef<M3LiveRun | null>(null), task = shallowRef<FarmTask | null>(null);
 const activeRunId = ref(savedRun()), busy = ref(false), error = ref('');
 const selectedTaskId = ref(savedTask(activeRunId.value));
 const taskId = computed(() => selectedTaskId.value || task.value?.id || '');
 const frames = computed(() => run.value?.frames || []), events = computed(() => run.value?.events || []);
 let subscribers = 0, timer: ReturnType<typeof setTimeout> | undefined, polling: Promise<void> | undefined;
 // Task selection can change without changing the greenhouse run.
 let selection = 0, taskSelection = 0, playbackGeneration = 0, taskQueue: Promise<unknown> = Promise.resolve(), lastTaskRead = 0;
 const runReads = new Map<string, Promise<void>>();
 const taskCreations = new Map<string, Promise<FarmTask>>();

 function rememberTask(value: FarmTask) {
  if(task.value?.id===value.id&&Date.parse(value.updatedAt)<Date.parse(task.value.updatedAt))return;
  task.value = value; selectedTaskId.value = value.id;
  localStorage.setItem('farmTask:' + (value.simulationRunId || 'independent'), value.id);
 }
 function acceptRun(value: M3LiveRun, replace = false): boolean {
  if (!value?.runId || !value.current) return false;
  if (!replace && activeRunId.value && value.runId !== activeRunId.value) return false;
  const previous = run.value?.runId === value.runId ? run.value : null;
  if (previous && (value.cursor < previous.cursor || (value.current.scenario?.version ?? 0) < (previous.current.scenario?.version ?? 0))) return false;
  if (activeRunId.value !== value.runId) { selection++; taskSelection++; task.value = null; selectedTaskId.value = savedTask(value.runId); }
  activeRunId.value = value.runId;
  sessionStorage.setItem('m3ActiveRun', value.runId); localStorage.setItem('m3ActiveRun', value.runId);
  const mergedFrames = new Map<number, M3LiveFrame>();
  for (const f of previous?.frames || []) mergedFrames.set(f.cursor, f);
  for (const f of value.frames || []) if (f.cursor <= value.cursor) mergedFrames.set(f.cursor, f);
  mergedFrames.set(value.current.cursor, value.current);
  const mergedEvents = new Map<string, M3LiveEvent>();
  for (const e of [...(previous?.events || []), ...(value.events || []), ...(value.current.updates || [])]) mergedEvents.set(eventKey(e), e);
  run.value = { ...value, frames: [...mergedFrames.values()].sort((a,b) => a.cursor-b.cursor), events: [...mergedEvents.values()] };
  error.value=value.failure?'计算已停止：'+value.failure:value.playback?.persistenceError?'运行存档失败：'+value.playback.persistenceError:'';
  return true;
 }
 async function attachTask(id: string): Promise<FarmTask> {
  const previousTask = task.value, previousId = selectedTaskId.value;
  if (id !== selectedTaskId.value) { taskSelection++; selectedTaskId.value = id; task.value = null; }
  const generation = selection, taskGeneration = taskSelection;
  try {
   const value = await getFarmTask(id);
   if (generation !== selection || taskGeneration !== taskSelection) throw new Error('当前农情任务已切换，请重新读取');
   if (value.simulationRunId && activeRunId.value && value.simulationRunId !== activeRunId.value) throw new Error('任务属于另一轮大棚运行，请先打开对应大棚');
   rememberTask(value); return value;
  } catch(e) {
   if (generation === selection && taskGeneration === taskSelection && id !== previousId) {
    taskSelection++; selectedTaskId.value = previousId; task.value = previousTask;
   }
   throw e;
  }
 }
 async function refreshTask(): Promise<FarmTask | null> {
  const id = selectedTaskId.value || task.value?.id; if (!id) return null;
  return attachTask(id);
 }
 async function refreshRun(full = false): Promise<void> {
  const id = activeRunId.value, generation = selection, playback = playbackGeneration; if (!id) return;
  const key=id+':'+generation;
  if(runReads.has(key))return runReads.get(key)!;
  // A compact device/playback receipt may advance current.cursor without the frames in between.
  // Only request after the last contiguous frame, so a missed interval is always fetched.
  let contiguous=-1;
  for(const frame of run.value?.frames||[]){if(frame.cursor===contiguous+1)contiguous=frame.cursor;else if(frame.cursor>contiguous+1)break;}
  const read=(async()=>{
   try {
    const value = await getM3Live(id,false,!full&&contiguous>=0?contiguous:undefined);
    if (id !== activeRunId.value || generation !== selection || playback !== playbackGeneration) return;
    acceptRun(value);
    if (Date.now() - lastTaskRead > 5000) { lastTaskRead = Date.now(); await refreshTask(); }
   } catch(e) { if (id === activeRunId.value && generation === selection && playback===playbackGeneration) error.value = message(e); }
  })();
  runReads.set(key,read);
  try{await read;}finally{if(runReads.get(key)===read)runReads.delete(key);}
 }
 async function attachRun(id: string): Promise<void> {
  if (!id) return;
  if (id !== activeRunId.value) { selection++; taskSelection++; run.value = null; task.value = null; selectedTaskId.value = savedTask(id); activeRunId.value = id; }
  await refreshRun(!run.value);
 }
 async function start(year = 2025): Promise<M3LiveRun> {
  if (busy.value) throw new Error('上一项大棚操作尚未结束');
  busy.value = true; error.value = '';
  try {
   playbackGeneration++;
   if(run.value) {
    try { await setM3Playback(run.value.runId,false,run.value.playback?.stepCount||1); }
    catch(e) {
     // A rejected obsolete checkpoint has no live clock to pause. Keep its archive and allow a fresh run.
     if(!/存档.*(?:不能恢复|不匹配|不存在|无效)|参数版本已变化|数据版本已变化/.test(message(e))) throw e;
    }
   }
   const value = await startM3Live(year); acceptRun(value, true); await ensureTask(); return run.value!; }
  catch(e) { error.value = message(e); throw e; } finally { busy.value = false; }
 }
 async function setPlayback(playing: boolean, stepCount?: number): Promise<void> {
  const id = activeRunId.value; if (!id) return;
  const count = stepCount ?? run.value?.playback?.stepCount ?? 1;
  if (![1,12,48].includes(count)) throw new Error('不支持的回放步长');
  playbackGeneration++;
  busy.value = true; error.value = '';
  try { const value = await setM3Playback(id, playing, count); if (id === activeRunId.value) acceptRun(value); }
  catch(e) { error.value = message(e); throw e; } finally { busy.value = false; }
 }
 function schedule() {
  if (timer || !subscribers) return;
  timer = setTimeout(async () => { timer = undefined; polling = refreshRun(); await polling; polling = undefined; schedule(); }, 1000);
 }
 function subscribe(): () => void {
  subscribers++; if (!polling) { polling = refreshRun(); void polling.finally(() => { polling = undefined; schedule(); }); }
  let subscribed = true;
  return () => { if (!subscribed) return; subscribed = false; subscribers--; if (!subscribers && timer) { clearTimeout(timer); timer = undefined; } };
 }
 async function ensureTask(question?: string): Promise<FarmTask> {
  const id = activeRunId.value, generation = selection, taskGeneration = taskSelection;
  const ensureCurrent = () => { if (generation !== selection || taskGeneration !== taskSelection) throw new Error('农情任务已切换，请在当前任务重新操作'); };
  if (task.value && task.value.id === selectedTaskId.value && (task.value.simulationRunId || '') === id) {
   if (question?.trim() && question.trim() !== task.value.question) {
    const value = await updateFarmTask(task.value.id, { question: question.trim() });
    ensureCurrent(); rememberTask(value); return value;
   }
   return task.value;
  }
  // Deduplicate creation within one selection; a new run must not await an old selection.
  const key = id + ':' + generation + ':' + taskGeneration, pending = taskCreations.get(key);
  if (pending) { await pending; ensureCurrent(); return ensureTask(question); }
  const creating = (async () => {
   const existing = selectedTaskId.value || savedTask(id);
   let value = existing ? await getFarmTask(existing) : await createFarmTask({ crop: '番茄', title: '番茄农情管理', question: question?.trim() || '', simulationRunId: id || undefined });
   ensureCurrent();
   if ((value.simulationRunId || '') !== id) throw new Error('任务属于另一轮大棚运行，请先打开对应大棚');
   if (question?.trim() && value.question !== question.trim()) value = await updateFarmTask(value.id, { question: question.trim() });
   ensureCurrent(); rememberTask(value); return value;
  })();
  taskCreations.set(key, creating);
  try { return await creating; } finally { if (taskCreations.get(key) === creating) taskCreations.delete(key); }
 }
 function mutate(operation: (id: string) => Promise<FarmTask>): Promise<FarmTask> {
  const id = task.value?.id === selectedTaskId.value ? task.value?.id : undefined, generation = selection, taskGeneration = taskSelection;
  const ensureCurrent = () => { if (generation !== selection || taskGeneration !== taskSelection) throw new Error('农情任务已切换，请在当前任务重新操作'); };
  const next = taskQueue.catch(() => undefined).then(async () => {
   ensureCurrent();
   const target = id || (await ensureTask()).id;
   ensureCurrent();
   const value = await operation(target);
   ensureCurrent();
   if (value.id !== target || value.id !== selectedTaskId.value) throw new Error('农情任务已切换，请在当前任务重新操作');
   rememberTask(value);
   return value;
  });
  taskQueue = next; return next;
 }
 const updateTask = (input: Parameters<typeof updateFarmTask>[1]) => mutate(id => updateFarmTask(id, input));
 const addEvidence = (input: FarmEvidence) => mutate(id => addFarmEvidence(id, input));
 const addTurn = (input: FarmTurn) => mutate(id => addFarmTurn(id, input));
 const addAction = (input: FarmAction) => mutate(id => addFarmAction(id, input));
 const addObservation = (input: FarmObservationInput) => mutate(id => addFarmObservation(id, input));
 const setActionStatus = (id: string, status: string) => mutate(taskId => updateFarmAction(taskId, id, status));
 const linkedQuery = () => ({ mode: 'm3', ...(activeRunId.value ? { liveRun: activeRunId.value } : {}), ...(taskId.value ? { taskId: taskId.value } : {}) });
 return { run, task, taskId, activeRunId, frames, events, busy, error, attachRun, acceptRun, start, setPlayback, subscribe,
  refreshRun, attachTask, ensureTask, refreshTask, updateTask, addEvidence, addTurn, addAction, addObservation, setActionStatus, linkedQuery };
});
