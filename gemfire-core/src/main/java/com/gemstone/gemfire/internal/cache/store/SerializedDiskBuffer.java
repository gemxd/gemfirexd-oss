/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package com.gemstone.gemfire.internal.cache.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

import com.gemstone.gemfire.internal.cache.DiskId;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.concurrent.unsafe.UnsafeAtomicIntegerFieldUpdater;
import com.gemstone.gemfire.internal.shared.BufferAllocator;
import com.gemstone.gemfire.internal.shared.ByteBufferReference;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;

/**
 * Used for optimized serialization of ByteBuffer data to serialize ByteBuffers
 * directly to an {@link OutputStreamChannel}.
 * <p>
 * It has optional {@link #retain()} and {@link #release()} methods which
 * implementations are required to ensure to be optional. These are present
 * only for eager releases for better off-heap memory efficiency. The
 * following semantics must be followed by all implementations and callers:
 * <ul>
 * <li>The {@link #retain()} and {@link #release()} calls are optional.
 * If no calls are made to those, then it should still release the memory at
 * some point and not lead to memory leaks for off-heap data. Initial
 * creation of the object will normally start the reference count at 1
 * indicating an implicit {@link #retain()}.</li>
 * <li>The {@link #retain()} and {@link #release()} calls are expected to
 * be thread-safe.</li>
 * <li>Caller may invoke {@link #retain()} but not necessarily a corresponding
 * {@link #release()} call and it should still not lead to a leak.</li>
 * <li>If reference count does go down to zero due to {@link #release()} calls
 * then data may be released and no longer available, but accessing it should
 * still never lead to crashes, rather return empty data.</li>
 * <li>If a caller does choose to invoke {@link #release()} then it must
 * have a corresponding {@link #retain()} call else it may lead to
 * premature release of the data and start returning empty data.
 * Likewise the {@link #size()} method is not expected to be consistent with
 * {@link #write} calls if an intervening {@link #release()} call happened to
 * release the underlying buffer due to more {@link #release()}s.</li>
 * </ul>
 */
public abstract class SerializedDiskBuffer extends ByteBufferReference {

  /**
   * Reference count for {@link #retain()} and {@link #release()}.
   */
  protected volatile int refCount = 1;

  protected static final UnsafeAtomicIntegerFieldUpdater<SerializedDiskBuffer>
      refCountUpdate = new UnsafeAtomicIntegerFieldUpdater<>(
      SerializedDiskBuffer.class, "refCount");

  /**
   * Get the current reference count for this object.
   */
  @Override
  public int refCount() {
    return refCountUpdate.get(this);
  }

  /**
   * Explicitly mark the buffer to be retained so it is not released until
   * a corresponding {@link #release()} has been invoked.
   *
   * @return True if the retain was on a valid buffer else false if the
   * underlying data has already been released (and will lead to empty writes).
   */
  @Override
  public boolean retain() {
    while (true) {
      final int refCount = refCountUpdate.get(this);
      if (refCount > 0) {
        if (refCountUpdate.compareAndSet(this, refCount, refCount + 1)) {
          return true;
        }
      } else {
        // already released
        return false;
      }
    }
  }

  /**
   * An optional explicit release of the underlying data. The buffer may no
   * longer be usable after this call and return empty data.
   * <p>
   * NOTE: Implementations should <b>never</b> require this call to be invoked
   * (along with {@link #retain()} and not lead to memory leaks if skipped.
   * Typically this means using NIO DirectByteBuffers for data which will
   * release automatically in the GC cycles when no references remain.
   */
  @Override
  public void release() {
    while (true) {
      final int refCount = refCountUpdate.get(this);
      if (refCount > 0) {
        if (refCountUpdate.compareAndSet(this, refCount, refCount - 1)) {
          if (refCount == 1) {
            // reference count has gone down to zero so release the buffer
            releaseBuffer();
          }
          break;
        }
      } else {
        break;
      }
    }
  }

  protected abstract void releaseBuffer();

  /**
   * For buffers which are stored in region, set its DiskId.
   */
  public abstract void setDiskId(DiskId id, DiskRegionView dr);

  /**
   * Write the underlying data in the buffer fully to the channel.
   * The serialized data is expected to be exactly the same data as
   * {@link com.gemstone.gemfire.DataSerializer#writeObject}.
   */
  public abstract void write(OutputStreamChannel channel) throws IOException;

  /**
   * For direct ByteBuffers, returns the size (in bytes) of the used data in
   * the object including off-heap object overhead. Only required to be
   * implemented for structures that will be stored in region.
   * <p>
   * This must exactly match the sizes as recorded during creation by
   * the {@link BufferAllocator} if it has been used for this buffer.
   */
  public abstract int getOffHeapSizeInBytes();

  protected final void write(OutputStreamChannel channel,
      ByteBuffer buffer) throws IOException {
    final int position = buffer.position();
    while (buffer.hasRemaining()) {
      if (channel.write(buffer) == 0) {
        // wait for a bit before retrying
        LockSupport.parkNanos(ClientSharedUtils.PARK_NANOS_FOR_READ_WRITE);
      }
    }
    // rewind back just in case bytes is to be read again
    if (position == 0) buffer.rewind();
    else buffer.position(position);
  }
}
