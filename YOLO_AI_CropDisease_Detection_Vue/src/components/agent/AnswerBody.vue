<template>
 <div class="answer-body">
  <template v-for="(block,i) in blocks" :key="i">
   <component v-if="block.kind==='heading'" :is="'h'+Math.min(6,(block.level||1)+2)"><InlineText :text="block.text" @cite="emit('cite',$event)" /></component>
   <ul v-else-if="block.kind==='ul'"><li v-for="(line,j) in block.lines" :key="j"><InlineText :text="line" @cite="emit('cite',$event)" /></li></ul>
   <ol v-else-if="block.kind==='ol'"><li v-for="(line,j) in block.lines" :key="j"><InlineText :text="line" @cite="emit('cite',$event)" /></li></ol>
   <div v-else-if="block.kind==='table'" class="table-scroll"><table><thead><tr><th v-for="(cell,j) in block.rows?.[0]" :key="j"><InlineText :text="cell" @cite="emit('cite',$event)" /></th></tr></thead><tbody><tr v-for="(row,j) in block.rows?.slice(1)" :key="j"><td v-for="(cell,k) in row" :key="k"><InlineText :text="cell" @cite="emit('cite',$event)" /></td></tr></tbody></table></div>
   <pre v-else-if="block.kind==='code'"><code>{{ block.text }}</code></pre>
   <blockquote v-else-if="block.kind==='quote'"><InlineText :text="block.text" @cite="emit('cite',$event)" /></blockquote>
   <p v-else><InlineText :text="block.text" @cite="emit('cite',$event)" /></p>
  </template>
 </div>
</template>
<script setup lang="ts">
import {computed,defineComponent,h} from 'vue';
const props=defineProps<{text:string;citations?:Array<{index?:number;number?:number}>}>();
const emit=defineEmits<{(e:'cite',number:number):void}>();
function inline(text:string):Array<ReturnType<typeof h>|string>{
 const out:Array<ReturnType<typeof h>|string>=[],re=/\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^\s)]+)\)|\[(\d{1,3})\]/g;
 let start=0,match:RegExpExecArray|null;
 while((match=re.exec(text))){
  if(match.index>start)out.push(text.slice(start,match.index));
  if(match[1])out.push(h('strong',{},inline(match[1])));
  else if(match[2])out.push(h('code',{},match[2]));
  else if(match[3]){let url='';try{const u=new URL(match[4]);if(['http:','https:'].includes(u.protocol))url=u.href;}catch{}
   out.push(url?h('a',{href:url,target:'_blank',rel:'noopener noreferrer'},match[3]):match[3]);
  }else {const n=Number(match[5]),valid=props.citations?.some(c=>(c.index??c.number)===n);
   out.push(valid?h('button',{type:'button',class:'body-cite','aria-label':'查看来源 '+n,onClick:()=>emit('cite',n)},'['+n+']'):match[0]);}
  start=re.lastIndex;
 }
 if(start<text.length)out.push(text.slice(start));return out;
}
const InlineText=defineComponent({props:{text:{type:String,default:''}},setup:p=>()=>h('span',{},inline(p.text))});
type Block={kind:string;text:string;level?:number;lines?:string[];rows?:string[][]};
const cells=(line:string)=>line.trim().replace(/^\||\|$/g,'').split('|').map(s=>s.trim());
const blocks=computed(()=>{
 const lines=(props.text||'').replace(/\r/g,'').split('\n'),out:Block[]=[];
 for(let i=0;i<lines.length;){const line=lines[i];if(!line.trim()){i++;continue;}
  if(/^\s*```/.test(line)){let text='';i++;while(i<lines.length&&!/^\s*```/.test(lines[i]))text+=lines[i++]+'\n';i++;out.push({kind:'code',text:text.trimEnd()});continue;}
  const head=line.match(/^(#{1,6})\s+(.+)$/);if(head){out.push({kind:'heading',text:head[2],level:head[1].length});i++;continue;}
  if(i+1<lines.length&&line.includes('|')&&/^\s*\|?\s*:?-{3,}/.test(lines[i+1])){const rows=[cells(line)];i+=2;while(i<lines.length&&lines[i].includes('|')&&lines[i].trim())rows.push(cells(lines[i++]));out.push({kind:'table',text:'',rows});continue;}
  const list=line.match(/^\s*(?:([-*+])|\d+[.)])\s+(.+)$/);if(list){const kind=list[1]?'ul':'ol',items:string[]=[];while(i<lines.length){const m=lines[i].match(/^\s*(?:([-*+])|\d+[.)])\s+(.+)$/);if(!m||(m[1]?'ul':'ol')!==kind)break;items.push(m[2]);i++;}out.push({kind,text:'',lines:items});continue;}
  if(/^>\s?/.test(line)){out.push({kind:'quote',text:line.replace(/^>\s?/,'')});i++;continue;}
  const paragraph=[line];i++;while(i<lines.length&&lines[i].trim()&&!/^(#{1,6}\s|\s*[-*+]\s|\s*\d+[.)]\s|\s*```|>)/.test(lines[i])){if(i+1<lines.length&&lines[i].includes('|')&&/^\s*\|?\s*:?-{3,}/.test(lines[i+1]))break;paragraph.push(lines[i++]);}
  out.push({kind:'paragraph',text:paragraph.join('\n')});
 }return out;
});
</script>
<style scoped>
.answer-body{font-size:14px;line-height:1.9;color:inherit;overflow-wrap:anywhere}.answer-body p{white-space:pre-wrap;margin:0 0 14px}.answer-body h3,.answer-body h4,.answer-body h5,.answer-body h6{font-weight:600;line-height:1.5;margin:20px 0 9px;color:#344f3c}.answer-body h3{font-size:18px}.answer-body h4{font-size:16px}.answer-body ul,.answer-body ol{padding-left:23px;margin:8px 0 17px}.answer-body li{padding-left:3px;margin:5px 0}.answer-body blockquote{border-left:3px solid #a7b6a0;margin:12px 0;padding:8px 16px;background:#f0f3eb;color:#657460}.answer-body pre{background:#eeeee6;padding:16px;overflow:auto;line-height:1.65;font-size:12px}.answer-body :deep(code){font-family:Consolas,monospace;background:#f1f0e8;padding:2px 4px}.answer-body :deep(strong){font-weight:600;color:#35523e}.answer-body :deep(a){color:#476c50;text-decoration:underline;text-underline-offset:3px}.answer-body :deep(.body-cite){font:inherit;font-size:11px;vertical-align:super;background:#e8eee2;border:0;color:#476743;border-radius:3px;margin:0 2px;padding:0 4px;cursor:pointer}.answer-body :deep(.body-cite:focus-visible){outline:2px solid #5f7e54;outline-offset:2px}.table-scroll{overflow:auto;margin:14px 0 20px}.answer-body table{border-collapse:collapse;width:100%;font-size:12px}.answer-body th,.answer-body td{text-align:left;border-bottom:1px solid #dedfd2;padding:10px 12px;min-width:80px}.answer-body th{background:#f0f2e9;font-weight:500}.answer-body>:last-child{margin-bottom:0}
</style>
