import { spawnSync } from 'node:child_process';

export function runSync(command,args,options={}) {
  if(process.platform==='win32' && /\.(bat|cmd)$/i.test(command)) {
    // cmd /s removes the outer quote pair. Keep a separate pair around every
    // argument so a checkout containing spaces or Chinese characters works.
    const quote=value=>'"'+String(value).replaceAll('"','""')+'"';
    return spawnSync(process.env.ComSpec || 'cmd.exe',
      ['/d','/s','/c','"'+[command,...args].map(quote).join(' ')+'"'],
      {...options,windowsVerbatimArguments:true,windowsHide:true});
  }
  return spawnSync(command,args,{...options,windowsHide:true});
}
