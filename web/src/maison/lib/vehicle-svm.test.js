import test from 'node:test';
import assert from 'node:assert/strict';
import { segments, dewarp } from './vehicle-svm.js';
test('SVM composite respects five actual segment widths and rejects unknown layout', () => {
  assert.deepEqual(segments([60, 10, 10, 10, 20, 10], 60, 10).map((s) => [s.x,s.width]), [[0,10],[10,10],[20,10],[30,10],[40,20]]);
  assert.deepEqual(segments([60,10,12,10,20,10],60,10), []);
  assert.equal(dewarp(new Uint8ClampedArray(400),10,10,null), null);
});
test('dewarp keeps a constant source unchanged at its optical centre', () => {
  const data = new Uint8ClampedArray(10*10*4); for(let i=0;i<data.length;i+=4){data[i]=42;data[i+3]=255;}
  const out=dewarp(data,10,10,150); assert.equal(out[(5*10+5)*4],42); assert.equal(out[(5*10+5)*4+3],255);
});
