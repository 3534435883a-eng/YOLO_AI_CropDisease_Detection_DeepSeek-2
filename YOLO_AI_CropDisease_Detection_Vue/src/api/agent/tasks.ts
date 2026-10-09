import axios from 'axios';
import { Session } from '/@/utils/storage';

export interface FarmEvidence {
 id?: string; type: string; label: string; source: string; imageUrl?: string;
 candidates?: unknown[]; details?: unknown; createdAt?: string;
}
export interface FarmTurn {
 id?: string; role: 'USER' | 'ASSISTANT' | string; content: string; requestId?: string;
 sessionId?: string; sources?: unknown[]; context?: unknown; createdAt?: string;
 status?: string;
}
export interface FarmAction {
 id?: string; key?: string; type: 'HUMAN' | 'SIMULATION'; title: string; detail?: string;
 status: string; reviewCondition?: string; source?: string; requestId?: string; createdAt?: string;
}
export interface FarmTask {
 id: string; title: string; crop: string; question: string; simulationRunId?: string;
 createdAt: string; updatedAt: string; evidence: FarmEvidence[]; turns: FarmTurn[];
 actions: FarmAction[]; observations: unknown[];
}
export interface FarmObservationInput { id?:string;note:string;actionId?:string;modelAt?:string; }
export type FarmTaskInput = Partial<Pick<FarmTask, 'title' | 'crop' | 'question' | 'simulationRunId' | 'observations'>>;
const client = axios.create({ baseURL: '/api/agent/tasks', timeout: 45000 });
client.interceptors.response.use(response=>response,error=>Promise.reject(new Error(error.response?.status===401?'登录状态已失效，请重新登录。':'农情任务服务暂不可用，请稍后重新连接。')));
async function call<T>(url: string, method: 'get' | 'post' | 'patch', data?: unknown): Promise<T> {
 const response = await client.request({ url, method, data, headers: Session.get('token') ? { Authorization: Session.get('token') } : {} });
 if (String(response.data.code) !== '0') throw new Error(response.data.msg || '农情任务暂不可用');
 return response.data.data as T;
}
export const createFarmTask = (input: FarmTaskInput) => call<FarmTask>('', 'post', input);
export const getFarmTask = (id: string) => call<FarmTask>('/' + encodeURIComponent(id), 'get');
export const updateFarmTask = (id: string, input: FarmTaskInput) => call<FarmTask>('/' + encodeURIComponent(id), 'patch', input);
export const addFarmEvidence = (id: string, input: FarmEvidence) => call<FarmTask>('/' + encodeURIComponent(id) + '/evidence', 'post', input);
export const addFarmTurn = (id: string, input: FarmTurn) => call<FarmTask>('/' + encodeURIComponent(id) + '/turns', 'post', input);
export const addFarmAction = (id: string, input: FarmAction) => call<FarmTask>('/' + encodeURIComponent(id) + '/actions', 'post', input);
export const addFarmObservation = (id: string, input: FarmObservationInput) => call<FarmTask>('/' + encodeURIComponent(id) + '/observations', 'post', input);
export const updateFarmAction = (id: string, actionId: string, status: string) => call<FarmTask>('/' + encodeURIComponent(id) + '/actions/' + encodeURIComponent(actionId), 'patch', { status });
export const farmTaskReportUrl = (id: string) => '/api/agent/tasks/' + encodeURIComponent(id) + '/report';
export async function downloadFarmTaskReport(id: string): Promise<void> {
 const response = await client.get('/' + encodeURIComponent(id) + '/report', { responseType: 'blob', headers: Session.get('token') ? { Authorization: Session.get('token') } : {} });
 const url = URL.createObjectURL(response.data), a = document.createElement('a');
 a.href = url; a.download = '农情管理报告-' + id.slice(0, 8) + '.md'; a.click();
 setTimeout(() => URL.revokeObjectURL(url), 1000);
}
