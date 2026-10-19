// The reviewed deployment's existing environment configuration is deliberately retained verbatim.
import{readFile}from'node:fs/promises';import{cloudApi,ENV_ID,FUNCTION_NAME}from'./cloud-tools.mjs';
if(process.argv[2]!=='--deploy')throw Error('Use --deploy for reviewed code publication.');
let r=cloudApi('scf','GetFunction',{Namespace:ENV_ID,FunctionName:FUNCTION_NAME,ShowCode:'FALSE'});
if(r.Status!=='Active')throw Error('Function is not ready for code deployment.');
const variables=JSON.stringify(r.Environment?.Variables);if(!variables)throw Error('Cannot verify existing configuration.');
const zip=await readFile(new URL('../build/nikon-preset-library.zip',import.meta.url));
cloudApi('scf','UpdateFunctionCode',{Namespace:ENV_ID,FunctionName:FUNCTION_NAME,ZipFile:zip.toString('base64')});
for(let i=0;i<30;i++){await new Promise(resolve=>setTimeout(resolve,5000));r=cloudApi('scf','GetFunction',{Namespace:ENV_ID,FunctionName:FUNCTION_NAME,ShowCode:'FALSE'});if(r.Status==='Active'){if(JSON.stringify(r.Environment?.Variables)!==variables)throw Error('Configuration changed during code publication');console.log('Cloud code Active; existing account, proxy and service configuration retained.');process.exit(0);}if(!['Updating','Publishing','Creating'].includes(r.Status))throw Error('Unexpected cloud deployment state.');}
throw Error('Code publication still pending; inspect status before retry.');
