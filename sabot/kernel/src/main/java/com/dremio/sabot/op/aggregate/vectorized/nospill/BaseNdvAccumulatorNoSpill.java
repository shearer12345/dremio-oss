/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.op.aggregate.vectorized.nospill;


import org.apache.arrow.memory.BufferManager;
import org.apache.arrow.memory.util.MemoryUtil;
import org.apache.arrow.util.Preconditions;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VariableWidthVector;

import com.dremio.exec.expr.fn.hll.StatisticsAggrFunctions;
import com.dremio.sabot.exec.context.SlicedBufferManager;
import com.dremio.sabot.op.common.ht2.LBlockHashTableNoSpill;
import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.hll.HllSketch;
import com.yahoo.sketches.hll.TgtHllType;

/**
 * A base accumulator for HLL/NDV operator
 */
abstract class BaseNdvAccumulatorNoSpill implements AccumulatorNoSpill {

  /**
   * holds an array of memory addresses, each is backing memory for a single sketch or actual HllSketch objects. AccumHolder is created for each chunk
   */
  public static class HllAccumHolder {
    private boolean reduceNdvHeap;
    private long[] accumAddresses;
    private HllSketch[] accumObjs;

    static final int sketchSize = HllSketch.getMaxUpdatableSerializationBytes(StatisticsAggrFunctions.HLL_ACCURACY, TgtHllType.HLL_8);

    public HllAccumHolder(int count /* number of sketch objects in this holder */, final SlicedBufferManager bufManager, boolean reduceNdvHeap) {
      this.reduceNdvHeap = reduceNdvHeap;
      if (reduceNdvHeap) {
        accumAddresses = new long[count];
      } else {
        accumObjs = new HllSketch[count];
      }

      for (int i = 0; i < count; ++i) {
        final long accumAddr = bufManager.getManagedBufferSliced(sketchSize).memoryAddress();
        /* Initialize backing memory for the sketch. HllSketch memset first getMaxUpdatableSerializationBytes() bytes. */
        HllSketch sketch = new HllSketch(StatisticsAggrFunctions.HLL_ACCURACY, TgtHllType.HLL_8,
                                         WritableMemory.wrap(MemoryUtil.directBuffer(accumAddr, sketchSize)));
        if (reduceNdvHeap) {
          accumAddresses[i] = accumAddr;
        } else {
          accumObjs[i] = sketch;
        }
      }
    }

    public int getAccumsSize() {
      return reduceNdvHeap ? accumAddresses.length : accumObjs.length;
    }

    public HllSketch getAccumSketch(int accumIndex) {
      Preconditions.checkArgument(accumIndex < getAccumsSize());
      return reduceNdvHeap ? HllSketch.writableWrap(WritableMemory.wrap(MemoryUtil.directBuffer(accumAddresses[accumIndex], sketchSize))) : accumObjs[accumIndex];
    }
  }

  protected final boolean reduceNdvHeap;
  protected final FieldVector input;
  protected final FieldVector output;
  protected HllAccumHolder[] accumulators;
  protected final SlicedBufferManager bufManager;

  public BaseNdvAccumulatorNoSpill(FieldVector input, FieldVector output, BufferManager bufferManager, boolean reduceNdvHeap) {
    this.input = input;
    this.output = output;
    initArrs(0);
    bufManager = (SlicedBufferManager)bufferManager;
    this.reduceNdvHeap = reduceNdvHeap;
  }

  FieldVector getInput(){
    return input;
  }

  private void initArrs(int size){
    this.accumulators = new HllAccumHolder[size];
  }

  @Override
  public void resized(int newCapacity) {
    final int oldBatches = accumulators.length;
    final int currentCapacity = oldBatches * LBlockHashTableNoSpill.MAX_VALUES_PER_BATCH;
    if(currentCapacity >= newCapacity){
      return;
    }

    // save old references.
    final HllAccumHolder[] oldAccumulators = this.accumulators;

    final int newBatches = (int) Math.ceil( newCapacity / (LBlockHashTableNoSpill.MAX_VALUES_PER_BATCH * 1.0d) );
    initArrs(newBatches);

    System.arraycopy(oldAccumulators, 0, this.accumulators, 0, oldBatches);

    for(int i = oldAccumulators.length; i < newBatches; i++){
      accumulators[i] = new HllAccumHolder(LBlockHashTableNoSpill.MAX_VALUES_PER_BATCH, bufManager, reduceNdvHeap);
    }
  }

  @Override
  public void output(int batchIndex) {
    HllAccumHolder ah = accumulators[batchIndex];
    int batchSize = ah.getAccumsSize();

    int total_size = 0;
    for (int i = 0; i < batchSize; ++i) {
      HllSketch sketch = ah.getAccumSketch(i);
      total_size += sketch.getCompactSerializationBytes();
    }

    ((VariableWidthVector) output).allocateNew(total_size, LBlockHashTableNoSpill.MAX_VALUES_PER_BATCH);
    VarBinaryVector outVec = (VarBinaryVector) output;

    for (int i = 0; i < batchSize; ++i) {
      HllSketch sketch = ah.getAccumSketch(i);
      byte[] ba = sketch.toCompactByteArray();
      outVec.setSafe(i, ba, 0, ba.length);
    }
  }

  /*
   * bufferManger is being closed as part of OperatorContext close
   */
  @SuppressWarnings("unchecked")
  @Override
  public void close() throws Exception { }

}
