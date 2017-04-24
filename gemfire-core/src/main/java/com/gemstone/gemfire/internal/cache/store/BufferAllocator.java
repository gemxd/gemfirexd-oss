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

import java.io.Closeable;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;

/**
 * Allocate, release and expand ByteBuffers (in-place if possible).
 */
public abstract class BufferAllocator implements Closeable {

  /**
   * Allocate a new ByteBuffer of given size.
   */
  public abstract ByteBuffer allocate(int size);

  /**
   * Clears the memory to be zeros immediately after allocation.
   */
  public abstract void clearPostAllocate(ByteBuffer buffer);

  /**
   * Clear the given portion of the buffer setting it with zeros.
   */
  public final void clearBuffer(ByteBuffer buffer, int position, int numBytes) {
    UnsafeHolder.getUnsafe().setMemory(baseObject(buffer), baseOffset(buffer) +
        position, numBytes, (byte)0);
  }

  /**
   * Get the base object of the ByteBuffer for raw reads/writes by Unsafe API.
   */
  public abstract Object baseObject(ByteBuffer buffer);

  /**
   * Get the base offset of the ByteBuffer for raw reads/writes by Unsafe API.
   */
  public abstract long baseOffset(ByteBuffer buffer);

  /**
   * Return the data as a heap byte array. Use of this should be minimal
   * when no other option exists.
   */
  public byte[] toBytes(ByteBuffer buffer) {
    final int bufferSize = buffer.remaining();
    return ClientSharedUtils.toBytesCopy(buffer, bufferSize, bufferSize);
  }

  /**
   * Return a ByteBuffer either copying from, or sharing the given heap bytes.
   */
  public abstract ByteBuffer fromBytes(byte[] bytes, int offset, int length);

  /**
   * Return a ByteBuffer either sharing data of given ByteBuffer
   * if its type matches, or else copying from the given ByteBuffer.
   */
  public ByteBuffer transfer(ByteBuffer buffer) {
    final int position = buffer.position();
    final ByteBuffer newBuffer = allocate(buffer.limit() - position);
    newBuffer.put(buffer);
    buffer.position(position);
    newBuffer.rewind();
    return newBuffer;
  }

  /**
   * Expand given ByteBuffer to new capacity.
   *
   * @return the new expanded ByteBuffer
   */
  public abstract ByteBuffer expand(ByteBuffer buffer, long cursor,
      long startPosition, int required);

  /**
   * For direct ByteBuffers the release method is preferred to eagerly release
   * the memory instead of depending on heap GC which can be delayed.
   */
  public abstract void release(ByteBuffer buffer);

  /**
   * Indicates if this allocator will produce direct ByteBuffers.
   */
  public abstract boolean isDirect();

  /**
   * Any cleanup required at system close.
   */
  @Override
  public abstract void close();

  static int expandedSize(int currentUsed, int required) {
    final long minRequired = (long)currentUsed + required;
    // double the size
    final int newLength = (int)Math.min(Math.max((currentUsed * 3) >>> 1L,
        minRequired), Integer.MAX_VALUE - 1);
    if (newLength >= minRequired) {
      return newLength;
    } else {
      throw new IndexOutOfBoundsException("Cannot allocate more than " +
          newLength + " bytes but required " + minRequired);
    }
  }

  static int checkBufferSize(long size) {
    if (size >= 0 && size < Integer.MAX_VALUE) {
      return (int)size;
    } else {
      throw new IndexOutOfBoundsException("Invalid size/index = " + size +
          ". Max allowed = " + (Integer.MAX_VALUE - 1) + '.');
    }
  }
}
