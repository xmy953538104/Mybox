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
  console.log('native selection and public-summary self-checks passed');
  process.exit(0);
}
if(process.platform!=='linux') throw Error('Cloud Linux required');
const [input, directory, packageRoot]=process.argv.slice(2);
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
  inputExecuted:false,selection:'JNI_OnLoad then provider-returned internal direct callees, breadth first, depth <= 3',
  dossierLimit:12,completeRequestedInvestigation:false,selectedDossiers:[],pendingInternalCallees:null,closeComplete:false};
let sequence=0, opened=false;
async function call(name,args,timeout=180000) {
  const remaining=deadline-Date.now();
  if(remaining<1000 && name!=='close_binary') throw Error('Investigation deadline reached');
  const reply=await client.callTool({name,arguments:args},{timeout:name==='close_binary'?timeout:Math.min(timeout,remaining),resetTimeoutOnProgress:false});
  const evidence=reply.structuredContent;
  save(String(++sequence).padStart(3,'0')+'-'+name+'.json',reply);
  if(reply.isError) throw Error('REA tool failed');
  if(name==='open_binary' || name==='close_binary') {
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
  await call('list_procedures',{});
  const queue=[{procedure:'JNI_OnLoad',depth:0}],seen=new Set();
  while(queue.length && progress.selectedDossiers.length<12) {
    const item=queue.shift();
    if(seen.has(item.procedure)) continue;
    seen.add(item.procedure);
    const evidence=await call('analyze_function',{procedure:item.procedure});
    const r=evidence.normalized_result;
    if(!r?.procedure || !Array.isArray(r.callees)) throw Error('Missing function dossier');
    progress.selectedDossiers.push(publicResult(evidence,item.depth));
    seen.add(r.procedure.address);
    if(item.depth<3) for(const p of r.callees.filter(internal))
      if(!seen.has(p.address) && !queue.some(x=>x.procedure===p.address)) queue.push({procedure:p.address,depth:item.depth+1});
  }
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
