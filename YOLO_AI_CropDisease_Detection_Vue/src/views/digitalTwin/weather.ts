import * as THREE from 'three';
import {GREENHOUSE_LAYOUT,roofHeightAt} from './greenhouseLayout';
export interface WeatherState {rainMmH:number;windMps:number;cloud:number}
/** Batched visual weather. Roof collision keeps particles outside the enclosure. */
export class WeatherEffects {
 private group=new THREE.Group();private rainGeometry=new THREE.BufferGeometry();private windGeometry=new THREE.BufferGeometry();
 private rainMaterial=new THREE.LineBasicMaterial({color:0xa6b6ba,transparent:true,opacity:.35,depthWrite:false});
 private windMaterial=new THREE.LineBasicMaterial({color:0xc2cec1,transparent:true,opacity:.3,depthWrite:false});
 private rain=new THREE.LineSegments(this.rainGeometry,this.rainMaterial);private wind=new THREE.LineSegments(this.windGeometry,this.windMaterial);
 private positions=new Float32Array(1200*6);private windPositions=new Float32Array(20*6);
 private target:WeatherState={rainMmH:0,windMps:0,cloud:0};private reduced=window.matchMedia('(prefers-reduced-motion: reduce)');
 constructor(private scene:THREE.Scene){
  this.rainGeometry.setAttribute('position',new THREE.BufferAttribute(this.positions,3));this.windGeometry.setAttribute('position',new THREE.BufferAttribute(this.windPositions,3));
  this.rain.frustumCulled=false;this.wind.frustumCulled=false;this.group.add(this.rain,this.wind);scene.add(this.group);
  for(let i=0;i<1200;i++)this.reset(i,true);
  for(let i=0;i<20;i++){const j=i*6;this.windPositions[j]=(i/20-.5)*(GREENHOUSE_LAYOUT.length+14);this.windPositions[j+1]=roofHeightAt(0)+1+i%3;this.windPositions[j+2]=(i%4-1.5)*(GREENHOUSE_LAYOUT.width/3+3);this.windPositions[j+3]=this.windPositions[j]-2;this.windPositions[j+4]=this.windPositions[j+1]+.12;this.windPositions[j+5]=this.windPositions[j+2];}
 }
 apply(state?:WeatherState){this.target=state??{rainMmH:0,windMps:0,cloud:0};}
 private reset(i:number,initial=false){const j=i*6,seed=((i*16807+7919)%2147483647)/2147483647;
  this.positions[j]=((i*37%1200)/1200-.5)*(GREENHOUSE_LAYOUT.length+20);
  this.positions[j+2]=((i*61%1200)/1200-.5)*(GREENHOUSE_LAYOUT.width+20);
  const inside=Math.abs(this.positions[j])<GREENHOUSE_LAYOUT.length/2&&Math.abs(this.positions[j+2])<GREENHOUSE_LAYOUT.width/2;
  const floor=inside?roofHeightAt(this.positions[j+2])+.25:0;
  this.positions[j+1]=initial?floor+((i*47%1200)/1200)*(roofHeightAt(0)+12-floor):roofHeightAt(0)+10+seed*3;
  this.positions[j+3]=this.positions[j]-.08;this.positions[j+4]=this.positions[j+1]+.55;this.positions[j+5]=this.positions[j+2];
 }
 update(dt:number,quality:'high'|'medium'|'low'){
  this.rain.visible=this.target.rainMmH>.1;this.wind.visible=this.target.windMps>5;
  const count=quality==='high'?1200:quality==='medium'?650:260;
  this.rainGeometry.setDrawRange(0,Math.round(count*Math.min(1,this.target.rainMmH/20))*2);
  this.rainMaterial.opacity=.2+Math.min(.3,this.target.rainMmH/160);
  if(!this.reduced.matches){for(let i=0;i<count;i++){
   const j=i*6;this.positions[j]+=this.target.windMps*.35*dt;this.positions[j+1]-=11*dt;
   const x=this.positions[j],z=this.positions[j+2],inside=Math.abs(x)<GREENHOUSE_LAYOUT.length/2&&Math.abs(z)<GREENHOUSE_LAYOUT.width/2;
   if(this.positions[j+1]<(inside?roofHeightAt(z)+.3:0)||x>GREENHOUSE_LAYOUT.length/2+10)this.reset(i);
   this.positions[j+3]=this.positions[j]-this.target.windMps*.02;this.positions[j+4]=this.positions[j+1]+.5;this.positions[j+5]=this.positions[j+2];
  }
  for(let i=0;i<20;i++){const j=i*6;this.windPositions[j]+=this.target.windMps*.7*dt;if(this.windPositions[j]>GREENHOUSE_LAYOUT.length/2+8)this.windPositions[j]=-GREENHOUSE_LAYOUT.length/2-8;
   this.windPositions[j+3]=this.windPositions[j]-2;this.windPositions[j+4]=this.windPositions[j+1]+.12;this.windPositions[j+5]=this.windPositions[j+2];}}
  this.rainGeometry.attributes.position.needsUpdate=true;this.windGeometry.attributes.position.needsUpdate=true;
 }
 dispose(){this.scene.remove(this.group);this.rainGeometry.dispose();this.windGeometry.dispose();this.rainMaterial.dispose();this.windMaterial.dispose();}
}
