import axios from 'axios';
import {Session} from '/@/utils/storage';
import type {M3Frame,M3Score} from './index';

export interface M3LiveAlert {
 code:string;title:string;level:string;value:number;threshold:number;unit:string;
 durationMinutes:number;advice:string;basis:string;estimatedInput:boolean;origin:string;
}
export interface M3LiveEvent extends Partial<M3LiveAlert> {
 type:string;at:string;plantId?:string;beforeCm?:number;observedCm?:number;afterCm?:number;
 measurementWeight?:number;innovationCm?:number;reviewRequired?:boolean;
}
export interface M3LiveFrame extends M3Frame {
 scenario?:M3Scenario;
 at:string;cursor:number;receivedAt:string;predictedHeightCm:number;
 updates:M3LiveEvent[];
 growth:{lai:number;wTotal:number;wFruit:number;fruitSetRate:number;fruitCount:number;
   singleFruitWeightG:number;stage:string;temperatureFactor:number;co2Factor:number;waterFactor:number};
 leafReference:{laiRaw:number|null;ldwRaw:number|null;assimilated:false};
 risk:{vpdKpa:number;dewPointC:number;dewPointMarginC:number;highTemperatureMinutes24h:number;
   highHumidityMinutes24h:number;highVpdMinutes24h:number;heatDegreeHours24h:number;windowMinutes:number;
   riskLevel:string;alerts:M3LiveAlert[]};
}
export interface M3LiveRun {
 playback:{playing:boolean;stepCount:number;waitingForAi:boolean;restored:boolean;persistenceError?:string;checkpointIntervalSeconds?:number};
 runId:string;year:number;cursor:number;totalSlots:number;finished:boolean;source:string;
 parameterVersion:string;observationsSha256:string;updateCount:number;failure?:string;
 current:M3LiveFrame;frames:M3LiveFrame[];events:M3LiveEvent[];assumptions:string[];
 scores:Record<'openLoop'|'prior'|'posterior',M3Score>;parameters:Record<string,unknown>;
}
const client=axios.create({baseURL:'/api/m3/live',timeout:45000});
client.interceptors.response.use(response=>response,error=>Promise.reject(new Error(error.response?.status===401?'登录状态已失效，请重新登录。':'大棚运行服务暂不可用，请稍后重新连接。')));
async function call<T>(url:string,method:'get'|'post'|'delete',data?:unknown):Promise<T>{
 const r=await client.request({url,method,data,headers:Session.get('token')?{Authorization:Session.get('token')}:{}});
 if(String(r.data.code)!=='0')throw new Error(r.data.msg||'在线运行不可用');
 return r.data.data as T;
}
export const startM3Live=(year:number)=>call<M3LiveRun>('/start','post',{year});
export const stepM3Live=(runId:string,expectedCursor:number,stepCount:number)=>call<M3LiveRun>('/'+runId+'/step','post',{expectedCursor,stepCount});
export const getM3Live=(runId:string,compact=false,afterCursor?:number)=>{
 const query=compact?'?compact=true':typeof afterCursor==='number'&&afterCursor>=0?'?afterCursor='+Math.floor(afterCursor):'';
 return call<M3LiveRun>('/'+runId+query,'get');
};
export const deleteM3Live=(runId:string)=>call<null>('/'+runId,'delete');
export const setM3Playback=(runId:string,playing:boolean,stepCount=1)=>call<M3LiveRun>('/'+runId+'/playback','post',{playing,stepCount});
export interface M3Agronomy {
 soilMoistureVwcPct:number;waterStressFactor:number;waterUsedL:number;drainageL:number;
 evapotranspirationL:number;wetExposureMinutes:number;diseaseConditions:Array<{code:string;title:string;level:string;continuousExposureMinutes:number;cumulativeExposureMinutes:number;condition:string;review:string;origin:string;validatedProbability:false}>;
 dryExposureMinutes:number;continuousWetMinutes:number;continuousDryMinutes:number;canopyWetnessProxy:number;canopyWetMinutes:number;rootCondition:string;diseaseConditionLevel:string;riskLevel:string;
 [key:string]:unknown;
}
export interface M3Scenario {
 farmTaskId?:string;
 agronomy?:M3Agronomy;shadowAgronomy?:M3Agronomy;resources?:Record<string,unknown>;effects?:Array<Record<string,unknown>>;
 runId:string;version:number;seed:number;tick:number;at:string;source:string;autoEvents:boolean;autoActuation:boolean;pending:boolean;
 environment:M3LiveFrame['environment']&{ppfd:number;windMps:number;rainMmH:number;absoluteHumidityGm3:number};
 withoutIntervention:M3Scenario['environment'];risk:M3LiveFrame['risk'];shadowRisk:M3LiveFrame['risk'];
 devices:Record<string,boolean>;deviceDuty:Record<string,number>;deviceHealth:Record<string,boolean>;
 growth:{plantHeightCm:number;referenceHeightCm:number;withoutInterventionHeightCm:number;lai:number;wTotal:number;wFruit:number;stage:string};
 weather:null|{id:string;type:string;title:string;startedAt:string;endsAt:string;windMps:number;rainMmH:number};
 decision:{status:string;requestId?:string;question?:string;error?:string;constraints?:string[];executedAt?:string;
   plan?:{summary:string;reason:string;expected:string;check:string;actions:Array<{device:string;duty:number;durationSteps:number}>;references:Array<{number:number;title:string;url:string;text:string}>};feedback?:{temperatureDifferenceC:number;humidityDifferencePct:number}};
 timeline:Array<{type:string;message:string;at:string;tick:number}>;trends:Array<{at:string;temperatureC:number;airHumidityPct:number;shadowTemperatureC:number;shadowHumidityPct:number;vpdKpa:number}>;assumptions:string[];
 parameters?:Array<{name:string;value:number;unit:string;source:string;calibrated:boolean}>;
 responseModelVersion?:string;actionEffects?:string;
}
export const commandM3Scenario=(id:string,operation:string,input:unknown)=>call<M3LiveRun>('/'+id+'/scenario/'+operation,'post',input);
