import test from "node:test";
import assert from "node:assert/strict";
import { boundedCrop, captureError, wholeFrame } from "../app/lib/screen-capture";

test("crop remains inside image including inverted and edge inputs", () => {
  assert.deepEqual(boundedCrop(wholeFrame), wholeFrame);
  for (const input of [{x:-5,y:-2,width:8,height:9}, {x:1,y:1,width:0,height:0}, {x:.8,y:.9,width:.5,height:.8}]) {
    const crop=boundedCrop(input);
    assert.ok(crop.x>=0 && crop.y>=0 && crop.width>0 && crop.height>0);
    assert.ok(crop.x+crop.width<=1.000001 && crop.y+crop.height<=1.000001);
  }
  assert.throws(()=>boundedCrop({...wholeFrame,x:NaN}));
});
test("capture errors do not expose device names or raw private exception messages", () => {
  const cancelled=new Error("private-desktop-title");cancelled.name="NotAllowedError";
  assert.match(captureError(cancelled),/取消/);
  assert.doesNotMatch(captureError(cancelled),/private/);
  assert.doesNotMatch(captureError(new Error("private-location")),/private/);
});
