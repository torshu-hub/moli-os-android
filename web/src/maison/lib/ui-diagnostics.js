// Closed numeric protocol consumed by the Android trial report. No URLs,
// identifiers, measurements, exception messages or DOM content cross it.
export const UI_TOPIC = { devices:1, system:2, netatmo:3, script:4, assistant:5 };
export const UI_STATE = { loading:1, ready:2, http:3, timeout:4, invalid:5, render:6, transport:7, empty:8, stale:9 };
const previous=new Map();
export function reportUi(topic,state,a=0,b=0) {
  if(!Number.isInteger(topic)||topic<1||topic>5||!Number.isInteger(state)||state<1||state>9)return;
  a=Math.min(4096,Math.max(0,Math.floor(Number(a)||0)));b=Math.min(4096,Math.max(0,Math.floor(Number(b)||0)));
  const key=`${state} ${a} ${b}`,old=previous.get(topic),now=Date.now();
  if(old?.key===key&&now-old.at<60000)return;
  previous.set(topic,{key,at:now});
  console.info(`MOLI_DIAG ${topic} ${state} ${a} ${b}`);
}
export function watchUiErrors() {
  const failed=()=>reportUi(UI_TOPIC.script,UI_STATE.render);
  addEventListener('error',failed);addEventListener('unhandledrejection',failed);
  return ()=>{removeEventListener('error',failed);removeEventListener('unhandledrejection',failed);};
}

export class ReadFailure extends Error { constructor(code){super('local_read_failed');this.code=code;} }
/** Hard deadline covers headers AND a stalled body; never display raw errors. */
export async function readJson(path,{timeout=8000,valid=()=>true,fetcher=fetch}={}) {
  const controller=new AbortController();let timer;
  try {
    return await Promise.race([
      (async()=>{
        const response=await fetcher(path,{signal:controller.signal});
        if(!response.ok)throw new ReadFailure(UI_STATE.http);
        let body;try{body=await response.json();}catch{throw new ReadFailure(UI_STATE.invalid);}
        if(!valid(body))throw new ReadFailure(UI_STATE.invalid);
        return body;
      })(),
      new Promise((_,reject)=>{timer=setTimeout(()=>{reject(new ReadFailure(UI_STATE.timeout));controller.abort();},timeout);}),
    ]);
  }catch(error){throw error instanceof ReadFailure?error:new ReadFailure(UI_STATE.transport);}
  finally{clearTimeout(timer);}
}
