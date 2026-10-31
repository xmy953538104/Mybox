/** Cloud-only, bounded REA/Ghidra JNI investigation. Never executes the input ELF. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';

const SHA='daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a';
function internal(p) {
  return !!p && typeof p.address==='string' && /^(?:0x)?[0-9a-f]+$/i.test(p.address)
    && p.classification?.external===false && p.classification?.thunk===false
    && p.body?.available===true && p.body?.contains_entry===true
    && Array.isArray(p.body.ranges) && p.body.ranges.length>0;
}


function calleeTarget(p,index) {
  if(internal(p)) return p.address;
  if(p?.classification?.external!==false || p.classification.thunk!==true) return null;
  const seen=new Set();
  let target=p.classification.thunk_target;
  for(let hops=0;hops<4;hops++) {
    if(!/^0x[0-9a-f]+$/.test(target || '') || seen.has(target)) return null;
    seen.add(target);
    const row=index.get(target),c=row?.procedure;
    if(!row || c?.external!==false) return null;
    if(c.thunk===false) return row.address;
    target=c.thunk_target;
  }
  return null;
}

function oversizedIdentity(reply,name) {
  if(name!=='analyze_function' || !reply.isError) return null;
  for(const c of reply.content || []) {
    if(c.type!=='text') continue;
    let j;try{j=JSON.parse(c.text);}catch{continue;}
    const e=j.error, limits=e?.details?.reported_limits;
    if(e?.code==='resource_constraint' && e.details?.resource==='transport' && limits?.constraint==='receive-buffer'
      && /^ev_[0-9a-f]{64}$/.test(limits.evidence_id || '')) return limits.evidence_id;
  }
  return null;
}

function publicResult(e, depth) {
  const r=e.normalized_result;
  return {evidenceId: e.evidence_id, depth, hasPseudocode: typeof r?.pseudocode==='string' && r.pseudocode.length>0,
    assemblyCount:Array.isArray(r?.assembly)?r.assembly.length:null,
    directCalleeCount:Array.isArray(r?.callees)?r.callees.length:null,
    unresolvedCallCount:Array.isArray(r?.unresolved_calls)?r.unresolved_calls.length:null,
    bodyAvailable:r?.procedure?.body?.available===true,
    limitationCount:Array.isArray(e.limitations)?e.limitations.length:null};
}
if(process.argv.includes('--self-test')) {
  const p={address:'00017f84',classification:{external:false,thunk:false},body:{available:true,contains_entry:true,ranges:[{start:'00017f84',end:'00017f88'}]}};
  assert(internal(p));
  for(const bad of [{...p,address:'bad;command'}, {...p,classification:{external:true,thunk:false}},
    {...p,classification:{external:false,thunk:true}}, {...p,body:{...p.body,available:false}},
    {...p,body:{...p.body,contains_entry:false}}, {...p,body:{...p.body,ranges:[]}}]) assert(!internal(bad));
  assert(!JSON.stringify(publicResult({evidence_id:'ev_test',normalized_result:{pseudocode:'PRIVATE_CANARY',procedure:p,callees:[p] }, limitations:[]},0)).includes('PRIVATE_CANARY'));
  const id='ev_'+'a'.repeat(64);
  const oversized={isError:true,content:[{type:'text',text:JSON.stringify({error:{code:'resource_constraint',details:{resource:'transport',reported_limits:{constraint:'receive-buffer',evidence_id:id}}}})}]};
  assert.equal(oversizedIdentity(oversized,'analyze_function'),id);
  assert.equal(oversizedIdentity(oversized,'open_binary'),null);
  assert.equal(oversizedIdentity({isError:true,content:[{type:'text',text:'PRIVATE_CANARY'}]},'analyze_function'),null);
  const index=new Map([['0x10',{address:'0x10',procedure:{external:false,thunk:false}}],['0x20',{address:'0x20',procedure:{external:true,thunk:false}}]]);
  const t={classification:{external:false,thunk:true,thunk_target:'0x10'}};
  assert.equal(calleeTarget(t,index),'0x10');
  assert.equal(calleeTarget({...t,classification:{...t.classification,thunk_target:'0x20'}},index),null);
  assert.equal(calleeTarget({...t,classification:{...t.classification,thunk_target:'EXTERNAL:0x1'}},index),null);
  assert.equal(calleeTarget({...t,classification:{...t.classification,thunk_target:'0x30'}},index),null);
  console.log('native selection, internal PLT targets, oversized-response recovery, and public-summary self-checks passed');
  process.exit(0);
}
if(process.platform!=='linux') throw Error('Cloud Linux required');
const [input, directory, packageRoot, locatorPath]=process.argv.slice(2);
if(!input || !directory || !packageRoot || ![input,directory,packageRoot].every(path.isAbsolute)) throw Error('Absolute paths required');
fs.mkdirSync(directory,{recursive:true,mode:0o700});
const save=(name,value)=>fs.writeFileSync(path.join(directory,name),JSON.stringify(value,null,2)+'\n',{flag:'wx',mode:0o600});
if(createHash('sha256').update(fs.readFileSync(input)).digest('hex')!==SHA) throw Error('Source identity mismatch');
const req=createRequire(path.join(packageRoot,'package.json'));
const {Client}=await import(pathToFileURL(req.resolve('@modelcontextprotocol/client')).href);
const {StdioClientTransport}=await import(pathToFileURL(req.resolve('@modelcontextprotocol/client/stdio')).href);
const transport=new StdioClientTransport({command:process.execPath,
  args:[path.join(packageRoot,'scripts/rea.mjs'),'--mcp'],env:{...process.env},stderr:'pipe',maxBufferSize:64*1024*1024});
const stderr=fs.createWriteStream(path.join(directory,'rea-stderr.log'),{flags:'wx',mode:0o600});
transport.stderr.pipe(stderr);
const client=new Client({name:'mybox-native-investigation',version:'1.0.0'});
const started=Date.now(), deadline=started+1200000;
const progress={schema:1,sourceSha256:SHA,reaVersion:'6.7.0',ghidraVersion:'12.1.4',functionalAcceptance:false,
  inputExecuted:false,selection:'Verified JNI entries and provider-resolved internal PLT targets, depth <= 4; constructor coverage retained in run 41193323036',
  dossierLimit:128,largeEvidenceFileExports:0,resolvedInternalThunkTargets:0,completeRequestedInvestigation:false,selectedDossiers:[],pendingInternalCallees:null,closeComplete:false};
let sequence=0, opened=false;
async function call(name,args,timeout=180000) {
  const remaining=deadline-Date.now();
  if(remaining<1000 && name!=='close_binary') throw Error('Investigation deadline reached');
  const reply=await client.callTool({name,arguments:args},{timeout:name==='close_binary'?timeout:Math.min(timeout,remaining),resetTimeoutOnProgress:false});
  let evidence=reply.structuredContent;
  save(String(++sequence).padStart(3,'0')+'-'+name+'.json',reply);
  if(reply.isError) {
    const identity=oversizedIdentity(reply,name);
    if(!identity) throw Error('REA tool failed');
    const file=path.join(directory,'large-evidence.bundle.json');
    await call('export_evidence_bundle',{path:file,overwrite:true},120000);
    const bundle=JSON.parse(fs.readFileSync(file,'utf8'));
    const matches=bundle.records?.filter(e=>e.evidence_id===identity);
    if(matches?.length!==1) throw Error('Oversized Evidence unavailable in complete file export');
    evidence=matches[0];
    if(evidence.subject?.digest?.sha256!==SHA) throw Error('Exported Evidence source identity mismatch');
    save('recovered-'+String(sequence).padStart(3,'0')+'-analyze_function.json',{structuredContent:evidence});
    progress.largeEvidenceFileExports++;
  }
  if(name==='open_binary' || name==='close_binary' || name==='export_evidence_bundle') {
    if(!evidence || !Object.hasOwn(evidence,'result')) throw Error('Missing REA lifecycle result');
    if(name==='open_binary' && (evidence.result?.sha256!==SHA || evidence.result?.format!=='elf' || evidence.result?.architecture!=='arm64')) throw Error('Provider source identity mismatch');
    return evidence.result;
  }
  if(!/^ev_[0-9a-f]{64}$/.test(evidence?.evidence_id || '') || evidence.operation!==name) throw Error('Missing or failed REA Evidence');
  return evidence;
}
try {
  await client.connect(transport,{timeout:30000});
  await call('open_binary',{path:input,provider_id:'ghidra'},930000); opened=true;
  const inventory=await call('list_procedures',{});
  const locators=JSON.parse(fs.readFileSync(locatorPath,'utf8'));
  if(locators.sourceSha256!==SHA || !Array.isArray(locators.entries)) throw Error('Invalid source locator identity');
  const entry=inventory.normalized_result.filter(p=>p.value==='JNI_OnLoad');
  if(entry.length!==1 || !/^0x[0-9a-f]+$/.test(entry[0].address)) throw Error('JNI_OnLoad provider entry unavailable');
  const mapping=await call('address_to_file_offset',{address:entry[0].address});
  if(mapping.normalized_result.file_offset!==locators.onloadFileOffset) throw Error('OnLoad source mapping mismatch');
  const delta=BigInt(entry[0].address)-BigInt(locators.onloadSourceVa);
  const index=new Map(inventory.normalized_result.map(p=>[p.address,p]));
  locators.entries=locators.entries.filter(p=>p.kind==="jni-registration");
  const thunkEdges=[];
  const queue=[{procedure:entry[0].address,depth:0}],seen=new Set();
  progress.entryLocatorCount=locators.entries.length;
  progress.missingProviderEntries=0;progress.verifiedEntryMappings=0;
  const locatorResults=[];
  for(const p of locators.entries) {
    const address='0x'+(BigInt(p.sourceVa)+delta).toString(16), row=index.get(address);
    if(!row || row.procedure?.external!==false || row.procedure?.thunk!==false) {
      progress.missingProviderEntries++;
      locatorResults.push({...p,providerAddress:address,providerEntryAvailable:false});
      continue;
    }
    const m=await call('address_to_file_offset',{address});
    if(m.normalized_result.file_offset!==p.sourceFileOffset) throw Error('Native entry source mapping mismatch');
    progress.verifiedEntryMappings++;
    locatorResults.push({...p,providerAddress:address,providerEntryAvailable:true,mappingEvidence:m.evidence_id});
    if(!queue.some(q=>q.procedure===row.address)) queue.push({procedure:row.address,depth:0});
  }
  save('verified-entry-locators.json',locatorResults);
  while(queue.length && progress.selectedDossiers.length<128) {
    const item=queue.shift();
    if(seen.has(item.procedure)) continue;
    seen.add(item.procedure);
    const evidence=await call('analyze_function',{procedure:item.procedure});
    const r=evidence.normalized_result;
    if(!r?.procedure || !Array.isArray(r.callees)) throw Error('Missing function dossier');
    progress.selectedDossiers.push(publicResult(evidence,item.depth));
    seen.add(r.procedure.address);
    if(item.depth<4) for(const p of r.callees) {
      const target=calleeTarget(p,index);
      if(!target) continue;
      if(p.classification.thunk===true) thunkEdges.push({source:r.procedure.address,via:p.address,target});
      if(!seen.has(target) && !queue.some(x=>x.procedure===target)) queue.push({procedure:target,depth:item.depth+1});
    }
  }
  save('resolved-internal-thunk-edges.json',thunkEdges);
  progress.resolvedInternalThunkTargets=new Set(thunkEdges.map(e=>e.target)).size;
  progress.pendingInternalCallees=queue.length;
  progress.completeRequestedInvestigation=true;
} catch(error) {
  save('analysis-error.json',{name:error?.name, message:String(error?.message),stack:error?.stack});
  progress.failurePhase='rea-static-analysis';
  process.exitCode=1;
} finally {
  if(opened) {
    try {await call('close_binary',{snapshot_path:path.join(directory,'native-session.snapshot.json')},90000); progress.closeComplete=true;}
    catch(error) {
      save('close-error.json',{message:String(error?.message)});
      try {await call('close_binary',{},30000);progress.closeComplete=true;} catch {}
      process.exitCode=1;
    }
  }
  try {await client.close();} catch {process.exitCode=1;}
  stderr.end();
  progress.elapsedMs=Date.now()-started;
  save('public-summary.json',progress);
}
