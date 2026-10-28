// Android WebViews need not implement Array.at/findLast. No polyfill or upgrade
// of the device's browser is required to render the system graph.
export function systemFacts(sys) {
  const perf=Array.isArray(sys?.perf)?sys.perf:[];
  let idle;
  for(let i=perf.length-1;i>=0;i--)if(!perf[i]?.clients?.total){idle=perf[i];break;}
  return {last:perf[perf.length-1],idle};
}
export const validSystem = s => s&&Array.isArray(s.drivers)&&Array.isArray(s.bricks)&&Array.isArray(s.surfaces)&&Array.isArray(s.perf)&&s.stats&&typeof s.stats==='object';
