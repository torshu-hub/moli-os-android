import {test} from 'node:test';
import assert from 'node:assert/strict';
import {merossCards,freshEquipmentSample} from './android-equipment.js';
const devices=Object.fromEntries([1,2,3,4].map(n=>{
 const instance=`android-meross-${String(n).padStart(32,'0')}`;
 return [`${instance}:${String(n+10).padStart(32,'0')}`,{id:`${instance}:${String(n+10).padStart(32,'0')}`,instance,model:'MSS310',native_name:`Fixture ${n}`,points:[{key:'on',access:{read:true,write:n===1}}]}];
}));
test('missing home projection uses published names and capabilities without granting writes',()=>{
 const cards=merossCards({},devices);assert.equal(cards.length,4);assert.equal(cards.filter(c=>c.manual).length,1);
 assert.deepEqual(cards.map(c=>c.name),['Fixture 1','Fixture 2','Fixture 3','Fixture 4']);
 assert.equal(cards[0].uuid,'00000000000000000000000000000011');
 assert.equal(merossCards({},{}).length,0);assert.equal(merossCards({meross:[]},devices).length,0);
});
test('explicit disabled configuration stays disabled and labels do not replace identity',()=>{
 const first=Object.values(devices)[0],id=first.instance.slice('android-meross-'.length);
 const data={...first,label:{name:'Fixture renamed'}};
 const cards=merossCards({meross:[{id,enabled:true,name:'Fixture old',manual_control:false,uuid:'00000000000000000000000000000011'}]},{[data.id]:data});
 assert.equal(cards[0].name,'Fixture renamed');assert.equal(cards[0].instance,first.instance);assert.equal(cards[0].manual,false);
 assert.equal(merossCards({meross:[{id,enabled:false}]},devices).length,0);
});
test('primary state and power require current samples; false and zero remain real values',()=>{
 const device={online:true},driver={status:{state:'running'}};
 for(const value of [false,0,12.5])assert.equal(freshEquipmentSample({value,ts:1000},device,driver,true,2000),true);
 for(const sample of [{value:true,ts:0},{value:true,ts:3000},{value:true,ts:1000},{value:null,ts:70000}])assert.equal(freshEquipmentSample(sample,device,driver,true,70000),false);
 assert.equal(freshEquipmentSample({value:true,ts:1000},{online:false},driver,true,2000),false);
 assert.equal(freshEquipmentSample({value:true,ts:1000},device,driver,false,2000),false);
});

test('zero, one, two and four manual controls stay independent with a separate critical-use label',()=>{
 for(const count of [0,1,2,4]){
  const rows=Object.values(devices).map((d,n)=>({id:d.instance.slice(15),name:d.native_name,enabled:true,manual_control:n<count,critical_load:n===3}));
  const cards=merossCards({meross:rows},devices);
  assert.equal(cards.length,4);assert.equal(cards.filter(c=>c.manual).length,count);
  assert.equal(cards[3].critical,true);assert.ok(cards.every(c=>c.criticalKnown));
  assert.deepEqual(cards.map(c=>c.instance),Object.values(devices).map(d=>d.instance));
 }
 assert.ok(merossCards({},devices).every(c=>!c.criticalKnown));
});
