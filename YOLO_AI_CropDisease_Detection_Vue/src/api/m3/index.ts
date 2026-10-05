import axios from 'axios';
import { Session } from '/@/utils/storage';

export interface M3Environment {
 at: string; temperatureC: number; airHumidityPct: number; co2Ppm: number;
 lightRaw: number; soilMoistureVwcPct: number; estimated: boolean; origin: string;
 observedAt: string | null; source?: Record<string, unknown>;
}
export interface M3PlantPoint {
 plantId: string; originalHeightCm: number; correctedHeightCm: number;
 linearHeightCm: number; observedHeightCm: number | null;
}
export interface M3Frame extends Omit<M3PlantPoint,'plantId'> {
 date: string; gdd: number; split: string; measurementCount: number;
 plants: M3PlantPoint[]; environment: M3Environment;
}
export interface M3Score {
 n: number; dateCount: number; plantCount: number; mae: number | null; rmse: number | null;
 bias: number | null; residuals: {date:string;plantId:string;errorCm:number}[];
}
export interface M3Season {
 year: number; role: string; startDate: string; endDate: string;
 cohort: {plantIds:string[];cultivar:string;treatment:string;mappingEvidence:Record<string,unknown>};
 quality: {heightRecords:number;selectedHeightRecords:number;plantsWithHeight:number;measurementDates:string[];
 inputCoverage:{totalSlots:number;observedCoveragePct:number;referenceSlots:number;meanSlots:number}};
 series: M3Frame[]; environment: M3Environment[];
 scores: Record<'original'|'corrected'|'linear',M3Score>;
}
export interface M3Result {
 schemaVersion:number;version:string;createdAt:string;status:string;improvementPct:number|null;
 parameters: Record<string,number|string|boolean|number[]|Record<string,number>>;
 quality:{totalHeightRecords:number;measurementDates:number};
 seasons:M3Season[]; notes:string[]; assumptions:string[]; sourceUrl:string;paperUrl:string;
 observationsSha256:string;algorithmReferences:string[];sources:{file:string;sha256:string;bytes:number}[];
}
export interface M3ReplayState {frame:M3Frame;heightCm:number;year:number;track:string;lai?:number}
export interface M3Summary {
 quality:{totalHeightRecords:number;measurementDates:number};
 seasons:M3Season[];assumptions:string[];sourceUrl:string;paperUrl:string;observationsSha256:string;
}
const client=axios.create({baseURL:'/api/m3',timeout:180000});
async function request<T>(path:string,method:'get'|'post'='get'):Promise<T> {
 const response=await client.request({url:path,method,headers:Session.get('token')?{Authorization:Session.get('token')}:{}});
 const payload=response.data;
 if(String(payload.code)!=='0')throw new Error(payload.msg||payload.message||'M3接口不可用');
 return payload.data as T;
}
export const getM3Summary=()=>request<M3Summary>('/observations');
export const getM3Result=()=>request<M3Result>('/result');
export const calibrateM3=()=>request<M3Result>('/calibrate','post');
export const formatM3Number=(n:unknown,digits=2)=>typeof n==='number'&&Number.isFinite(n)?n.toFixed(digits):'—';
