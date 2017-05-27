/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.ObjToByteArraySerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;

/**
 * GfxdHeapDataOutputStream extends {@link HeapDataOutputStream} from which it
 * derives most of its functionality. The only difference is in the
 * write(bytes[] source, int offset, int len) which wraps the source byte[]
 * passed for creating the internal ByteBuffer. The instance of this class can
 * be used if the source byte[] array is immutable in terms of its data , a
 * guarantee which an application may be able to offer. The benefit gained is in
 * preventing the copying of data from source byte[] to internal byte buffer.
 * 
 * @author Asif
 */
public final class GfxdHeapDataOutputStream extends HeapDataOutputStream
    implements ObjToByteArraySerializer {

  static final int MIN_SIZE = Integer.getInteger(
      "gemfirexd.heap-output-stream-size", 512);

  final GfxdHeapThresholdListener thresholdListener;

  final String query;

  final boolean wrapBytes;

  // private final int byteArrayWrapLength;
  public GfxdHeapDataOutputStream(
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final boolean wrapBytes, final Version v) {
    this(MIN_SIZE, thresholdListener, query, wrapBytes, v);
  }

  public GfxdHeapDataOutputStream(final int minSize,
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final boolean wrapBytes, final Version v) {
    super(minSize, v);
    this.thresholdListener = thresholdListener;
    this.query = query;
    this.wrapBytes = wrapBytes;
    markForReuse();
  }

  @Override
  public final void write(byte[] source, int offset, int len) {
    // if there is enough space in current byte buffer, then use that instead
    // of always wrapping to avoid one additional allocation
    if (this.wrapBytes && this.buffer.remaining() < len) {
      this.writeWithByteArrayWrappedConditionally(source, offset, len);
    }
    else {
      super.write(source, offset, len);
    }
  }

  public final void writeNoWrap(byte[] source, int offset, int len) {
    super.write(source, offset, len);
  }

  public final void copyMemory(final Object src, long srcOffset, int length) {
    final sun.misc.Unsafe unsafe = UnsafeHolder.getUnsafe();

    // require that buffer is a heap byte[] one
    ByteBuffer buffer = this.buffer;
    byte[] dst = buffer.array();
    int offset = buffer.arrayOffset();
    int pos = buffer.position();
    // copy into as available space first
    final int remainingSpace = buffer.capacity() - pos;
    if (remainingSpace < length) {
      UnsafeHolder.copyMemory(src, srcOffset, dst,
          UnsafeHolder.arrayBaseOffset + offset + pos, remainingSpace, unsafe);
      buffer.position(pos + remainingSpace);
      srcOffset += remainingSpace;
      length -= remainingSpace;
      ensureCapacity(length);
      // refresh buffer variables
      buffer = this.buffer;
      dst = buffer.array();
      offset = buffer.arrayOffset();
      pos = buffer.position();
    }
    // copy remaining bytes
    UnsafeHolder.copyMemory(src, srcOffset, dst,
        UnsafeHolder.arrayBaseOffset + offset + pos, length, unsafe);
    buffer.position(pos + length);
  }

  @Override
  protected final void expand(int amount) {
    Misc.checkMemoryRuntime(thresholdListener, query, amount);
    super.expand(amount);
  }

  public final byte[] toByteArrayCopy() {
    final byte[] bytes = new byte[size()];
    sendTo(bytes, 0);
    return bytes;
  }
}
