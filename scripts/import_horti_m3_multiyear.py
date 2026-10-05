"""Read original M3 tables; keep missing values and generate provenance-tagged drivers."""
import argparse, csv, hashlib, io, json, math, re
from collections import defaultdict, Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
import openpyxl

FIELDS = ['temperatureC', 'airHumidityPct', 'co2Ppm', 'lightRaw', 'soilMoistureVwcPct']
PREFIX = ['空气温度', '相对湿度', '二氧化碳', '光照', '土壤水分']
SELECTED = {2023: ['714', '715', '716'], 2024: [f'CK1{i}' for i in range(1,7)], 2025: [f'CK1{i}' for i in range(1,7)]}
CORE = {'temperatureC':(-40,60), 'airHumidityPct':(0,100), 'co2Ppm':(150,5000), 'lightRaw':(0,200000)}

def num(v):
    try:
        n=float(v)
        return n if math.isfinite(n) else None
    except (ValueError,TypeError): return None

def read_csv(p):
    b=p.read_bytes()
    try: text=b.decode('utf-8-sig'); enc='utf-8-sig'
    except UnicodeDecodeError: text=b.decode('gb18030'); enc='gb18030'
    return list(csv.DictReader(io.StringIO(text))), enc

def stamp(v):
    if isinstance(v, datetime): return v
    if not isinstance(v,str): return None
    for fmt in ['%Y/%m/%d %H:%M','%Y/%m/%d %H:%M:%S','%Y-%m-%d %H:%M:%S']:
        try: return datetime.strptime(v,fmt)
        except ValueError: pass
    return None

def bin_time(v):
    b=v.replace(minute=(v.minute//30)*30, second=0, microsecond=0)
    return b+timedelta(minutes=30) if b<v else b

def save(p,value):
    b=json.dumps(value,ensure_ascii=False,allow_nan=False,indent=2).encode('utf-8')
    t=p.with_suffix('.tmp'); t.write_bytes(b); t.replace(p)
    return hashlib.sha256(b).hexdigest()

def run(directory):
    root=directory/'tables'/'2023-2025 Tomato dataset'
    status=json.loads((directory/'download-status.json').read_text(encoding='utf-8-sig'))
    if not status.get('checksumVerified') or status.get('stage')!='complete': raise ValueError('Archive checksum required')
    sources=[{'file':p.relative_to(root).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
             for p in sorted(root.rglob('*')) if p.is_file()]
    seasons=[]
    for year in [2023,2024,2025]:
        growth=[]; duplicates=[]; hashes=set()
        for p in sorted((root/str(year)).rglob('*.csv')):
            if '/raw_data/' not in p.as_posix() or not any(s in p.as_posix() for s in ['/grow_index/','/growth_index/']): continue
            sha=hashlib.sha256(p.read_bytes()).hexdigest()
            if sha in hashes: duplicates.append(p.relative_to(root).as_posix()); continue
            hashes.add(sha); rows,enc=read_csv(p)
            for line,row in enumerate(rows,2):
                date=(datetime.strptime(row['日期'],'%Y/%m/%d') if year==2023 else datetime.strptime(p.stem,'%Y%m%d')).date().isoformat()
                plant=row.get('Pid',row.get('编号','')).strip()
                for col,metric,unit in [('高度' if year==2023 else '株高','plantHeightCm','cm'),('LAI','canopyLai','instrument basis'),('LDW','canopyLdw','g; instrument estimate')]:
                    value=num(row.get(col)); flags=[]
                    if value is None: flags.append('MISSING_OR_NONFINITE')
                    elif value<0 or (metric=='plantHeightCm' and (value<=0 or value>600)): flags.append('OUT_OF_RANGE')
                    growth.append({'year':year,'plantId':plant,'observedDate':date,'metric':metric,'value':value,
                                   'unit':unit,'timePrecision':'date','selected':plant in SELECTED[year],'qualityFlags':flags,
                                   'source':{'file':p.relative_to(root).as_posix(),'row':line,'column':col,'encoding':enc},'raw':row})
        dates=sorted({r['observedDate'] for r in growth})
        begin=datetime.fromisoformat(dates[0]).replace(hour=12); end=datetime.fromisoformat(dates[-1]).replace(hour=12)
        streams=defaultdict(dict); conflicts=[]; dup_count=0
        def add(key,t,values,source,raw):
            nonlocal dup_count
            if t is None or not begin-timedelta(days=1)<=t<=end+timedelta(days=1): return
            issues=[k for k,(lo,hi) in CORE.items() if values.get(k) is None or not lo<=values[k]<=hi]
            v={'observedAt':t.isoformat()+'+08:00',**values,'sensorId':key,'qualityFlags':issues,'source':source,'raw':raw}
            old=streams[key].get(t)
            if old:
                dup_count+=1
                if any(old.get(k)!=v.get(k) for k in FIELDS):
                    old['qualityFlags'].append('CONFLICTING_DUPLICATE')
                    conflicts.append({'sensorId':key,'at':v['observedAt'],'source':source})
            else: streams[key][t]=v
        sensor_files=[p for p in sorted((root/str(year)).rglob('*')) if p.is_file() and '/raw_data/sensor_data/' in p.as_posix()]
        sensor_hashes=set()
        for p in sensor_files:
            sha=hashlib.sha256(p.read_bytes()).hexdigest()
            if sha in sensor_hashes: continue
            sensor_hashes.add(sha)
            if p.suffix=='.csv':
                rows,enc=read_csv(p); match=re.search(r'处理(\d+)',p.stem)
                if not match: continue
                key='legacy-sensor-'+match.group(1)
                for line,row in enumerate(rows,2):
                    values={k:num(row.get(c)) for k,c in zip(FIELDS,PREFIX)}
                    add(key,stamp(row.get('时间')),values,{'file':p.relative_to(root).as_posix(),'row':line,'columns':dict(zip(FIELDS,PREFIX)),'encoding':enc},row)
            elif p.suffix=='.xlsx':
                book=openpyxl.load_workbook(p,read_only=True,data_only=True)
                for sheet in book:
                    rows=sheet.iter_rows(values_only=True); header=list(next(rows,()))
                    labels=[str(h)[len('空气温度'):] for h in header if isinstance(h,str) and h.startswith('空气温度')]
                    for line,row in enumerate(rows,2):
                        if not row: continue
                        t=stamp(row[0])
                        for label in labels:
                            columns=[pre+label for pre in PREFIX]
                            columns[-1]='土壤水分'+re.sub(r'(前|后|中)$','',label)
                            if not all(c in header for c in columns[:4]): continue
                            key='ck-back' if '对照' in label and label.endswith('后') else 'ck-main' if '对照' in label else 'region:'+label
                            vals={k:num(row[header.index(c)]) if c in header and header.index(c)<len(row) else None for k,c in zip(FIELDS,columns)}
                            add(key,t,vals,{'file':p.relative_to(root).as_posix(),'sheet':sheet.title,'row':line,'columns':dict(zip(FIELDS,columns))},dict(zip(FIELDS,[vals[k] for k in FIELDS])))
                book.close()
        mapping={}
        if year==2023:
            primary='legacy-sensor-5'
            # Verify the README CK region by independent climate fields already joined in the original growth table.
            comparisons=[]
            for date in dates:
                rows=[r for r in growth if r['plantId']=='714' and r['observedDate']==date and r['metric']=='plantHeightCm']
                raw=rows[0]['raw']
                env=[r for t,r in streams[primary].items() if t.date().isoformat()==date]
                errors={}
                for key,col in zip(FIELDS,PREFIX):
                    a=[r[key] for r in env if r[key] is not None]; target=num(raw.get(col))
                    if a and target is not None: errors[key]=abs(sum(a)/len(a)-target)
                comparisons.append({'date':date,'absoluteMeanDifference':errors,'matches':bool(errors) and all(x<0.002 for x in errors.values())})
            matches=sum(r['matches'] for r in comparisons)
            if matches<6: raise ValueError('2023 CK sensor mapping failed: '+str(comparisons))
            mapping={'basis':'README processCK 714-716 + original joined environmental daily means','primary':primary,'matchedDates':matches,'comparisons':comparisons}
        else:
            primary='ck-back' if streams.get('ck-back') else 'ck-main'
            mapping={'basis':'README variety1/CK naming + explicit contrast2 environmental columns; regional proxy, not individually matched','primary':primary}
        valid_streams={key:{bin_time(t):r for t,r in rows.items() if not r['qualityFlags']} for key,rows in streams.items()}
        priority=[primary]+[k for k in ['ck-back','ck-main'] if k!=primary and k in valid_streams]+sorted(k for k in valid_streams if k not in [primary,'ck-back','ck-main'])
        heights=[r for r in growth if r['metric']=='plantHeightCm' and not r['qualityFlags']]
        selected=[r for r in heights if r['selected']]
        for plant in SELECTED[year]:
            if not any(r['plantId']==plant and r['observedDate']==dates[0] for r in selected): raise ValueError('Initial height missing '+str(year)+plant)
        quality={'measurementDates':dates,'heightRecords':len(heights),'plantsWithHeight':len({r['plantId'] for r in heights}),
                 'selectedHeightRecords':len(selected),'duplicateGrowthFilesExcluded':duplicates,'duplicateSensorRows':dup_count,
                 'conflictingSensorRows':len(conflicts),'conflicts':conflicts[:30],'invalidHeightRecords':sum(r['metric']=='plantHeightCm' and bool(r['qualityFlags']) for r in growth)}
        seasons.append({'year':year,'role':'calibration' if year<2025 else 'holdout','startDate':dates[0],'endDate':dates[-1],
                        'cohort':{'plantIds':SELECTED[year],'cultivar':'广辉201','treatment':'CK','mappingEvidence':mapping},
                        'growth':growth,'quality':quality,'_streams':valid_streams,'_priority':priority,'_begin':begin,'_end':end})
    means=[[[] for _ in FIELDS] for _ in range(48)]
    for s in seasons[:2]:
        for t,r in s['_streams'][s['_priority'][0]].items():
            if s['_begin']<=t<s['_end']:
                slot=t.hour*2+t.minute//30
                for j,k in enumerate(FIELDS):
                    if r[k] is not None: means[slot][j].append(r[k])
    climatology=[[sum(v)/len(v) if v else None for v in row] for row in means]
    for s in seasons:
        driver=[]; coverage=Counter(); t=s['_begin']
        while t<s['_end']:
            chosen=next((s['_streams'][k][t] for k in s['_priority'] if t in s['_streams'][k]),None)
            origin=chosen['sensorId'] if chosen else 'training-2023-2024-intraday-mean'
            estimated=chosen is None or origin!=s['_priority'][0]; slot=t.hour*2+t.minute//30
            vals={}
            for j,k in enumerate(FIELDS):
                val=chosen[k] if chosen and chosen[k] is not None else climatology[slot][j]
                if val is None: raise ValueError('No training climatology for '+k+' '+str(t))
                vals[k]=val
            row={'at':t.isoformat(),**vals,'estimated':estimated,'origin':origin,'observedAt':chosen['observedAt'] if chosen else None,
                 'source':chosen['source'] if chosen else {'basis':'2023/2024 primary regional observations only'}}
            driver.append(row);coverage['measured' if not estimated else 'mean' if chosen is None else 'reference']+=1
            t+=timedelta(minutes=30)
        s['environment']=driver
        s['quality']['inputCoverage']={'totalSlots':len(driver),'observedSlots':coverage['measured'],'referenceSlots':coverage['reference'],'meanSlots':coverage['mean'],'observedCoveragePct':100*coverage['measured']/len(driver),'policy':'REFERENCE_THEN_TRAINING_YEARS_INTRADAY_MEAN'}
        for k in [k for k in s if k.startswith('_')]: del s[k]
    data={'schemaVersion':2,'datasetVersion':'horti-m3-multiyear-v1','importedAt':datetime.now(timezone(timedelta(hours=8))).isoformat(),
          'sourceUrl':'https://zenodo.org/records/17217565','paperUrl':'https://www.nature.com/articles/s41597-026-07074-w',
          'license':'CC BY 4.0','publisherMd5':status.get('expectedMd5','7f1f5e84b5ab0846efefb507dee14c79'),'archiveBytes':28284894698,
          'sources':sources,'seasons':seasons,'quality':{'years':[s['quality'] for s in seasons],'totalHeightRecords':sum(s['quality']['heightRecords'] for s in seasons),'measurementDates':sum(len(s['quality']['measurementDates']) for s in seasons)},
          'assumptions':['Light interpreted as klux, pending source-unit confirmation; solar-spectrum lux to PPFD approximation.',
                         'CK is a regional environmental proxy; not every selected plant is individually sensor-matched.',
                         'Growth date aligned at noon; original measurement time is unknown.',
                         'Missing environment inputs estimated; missing height is never made into an independent observation.',
                         'Soil moisture VWC displayed, crop water stress uses explicit neutral assumption.'],
          'climatology':{'basisYears':[2023,2024],'values':climatology}}
    out=directory/'normalized';out.mkdir(exist_ok=True)
    sha=save(out/'multiyear-observations.json',data)
    save(out/'multiyear-manifest.json',{'schemaVersion':2,'observationsSha256':sha,'sourceCount':len(sources),'quality':data['quality']})
    print(json.dumps({'sha256':sha,'sources':len(sources),'heightRecords':data['quality']['totalHeightRecords'],
                      'seasons':[{'year':s['year'],'quality':s['quality'],'mapping':s['cohort']['mappingEvidence']} for s in seasons]},ensure_ascii=False))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--dataset-dir',type=Path,required=True)
    run(parser.parse_args().dataset_dir.resolve())

