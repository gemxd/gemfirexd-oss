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
package com.gemstone.gemfire.internal;

import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.cache.BytesAndBitsForCompactor;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;

/** HeapDataOutputStream is an OutputStream that also implements DataOutput
 * and stores all data written to it in heap memory.
 * It is always better to use this class instead ByteArrayOutputStream.
 * <p>This class is not thread safe
 *
 *  @author Darrel
 *  @since 5.0.2
 * 
 *
 *
 * @author Eric Zoerner
 * Added boolean flag that when turned on will throw an exception instead of allocating a new
 * buffer. The exception is a BufferOverflowException thrown from expand, and will restore
 * the position to the point at which the flag was set with the disallowExpansion method.
 * Usage Model:
 *              boolean succeeded = true;
 *              stream.disallowExpansion();
 *              try {
 *                DataSerializer.writeObject(obj, stream);
 *              } catch (BufferOverflowException e) {
 *                succeeded = false;
 *              }
 */
public class HeapDataOutputStream extends OutputStream implements
    ObjToByteArraySerializer, VersionedDataStream {
  protected ByteBuffer buffer;
  protected LinkedList<ByteBuffer> chunks = null;

  /** list of chunks that can be reused */
  protected LinkedList reuseChunks = null;
  /**
   * Bit set indicating which chunks can be reused in {@link #chunks}. This will
   * be set if {@link #writeWithByteArrayWrappedConditionally} invocation causes
   * a wrapping of provided byte[].
   */
  protected BitSet nonReusableChunks = null;
  protected boolean canReuseChunks = true;

  protected int size = 0;
  /**
   * True if this stream is currently setup for writing.
   * Once it switches to reading then it must be reset before
   * it can be written again.
   */
  private boolean writeMode = true;
  private boolean ignoreWrites = false; // added for bug 39569 
  private final int MIN_CHUNK_SIZE;
  private boolean disallowExpansion = false;
  private ExpansionExceptionGenerator expansionException = null;
  private int memoPosition;
  private int offset;
  private Version version;

  public static interface ExpansionExceptionGenerator {
    public Error newExpansionException(String method);
  }

  private static final int INITIAL_CAPACITY = 1024;

  public HeapDataOutputStream() {
    this(INITIAL_CAPACITY, null);
  }

  public HeapDataOutputStream(Version version) {
    this(INITIAL_CAPACITY, version);
  }

  /**
   * Create a HeapDataOutputStream optimized to contain just the specified string.
   * The string will be written to this stream encoded as utf.
   */
  public HeapDataOutputStream(String s) {
    int maxStrBytes;
    if (ASCII_STRINGS) {
      maxStrBytes = s.length();
    } else {
      maxStrBytes = s.length()*3;
    }
    this.MIN_CHUNK_SIZE = INITIAL_CAPACITY;
    this.buffer = ByteBuffer.allocate(maxStrBytes);
    writeUTFNoLength(s);
  }

  public HeapDataOutputStream(int allocSize) {
    this(allocSize, null);
  }

  public HeapDataOutputStream(int allocSize, Version version) {
    if (allocSize < 32) {
      this.MIN_CHUNK_SIZE = 32;
    } else {
      this.MIN_CHUNK_SIZE = allocSize;
    }
    this.buffer = ByteBuffer.allocate(allocSize);
    this.version = version;
  }

  /**
   * Construct a HeapDataOutputStream which uses the byte array provided as its
   * underlying ByteBuffer
   * 
   * @param bytes
   */
  public HeapDataOutputStream(byte[] bytes) {
    int len = bytes.length;
    if (len <= 0) {
      throw new IllegalArgumentException("The byte array must not be empty");
    }
    if (len > 32) {
      this.MIN_CHUNK_SIZE = len;
    } else {
      this.MIN_CHUNK_SIZE = 32;
    }
    this.buffer = ByteBuffer.wrap(bytes);
    // cannot reuse buffers with this constructor
    this.canReuseChunks = false;
  }

  /**
   * Construct a HeapDataOutputStream which uses the byte array provided as its
   * underlying ByteBuffer from given offset and with provided length.
   */
  public HeapDataOutputStream(byte[] bytes, int offset, int length) {
    if (length <= 0) {
      throw new IllegalArgumentException("The byte array must not be empty");
    }
    if (length > 32) {
      this.MIN_CHUNK_SIZE = length;
    }
    else {
      this.MIN_CHUNK_SIZE = 32;
    }
    this.buffer = ByteBuffer.wrap(bytes, offset, length);
    this.offset = offset;
    // cannot reuse buffers with this constructor
    this.canReuseChunks = false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Version getVersion() {
    return this.version;
  }

  public final void markForReuse() {
    if (this.canReuseChunks) {
      this.nonReusableChunks = new BitSet();
    }
    else {
      throw new IllegalArgumentException("cannot reuse wrapped buffers");
    }
  }

  /*throw an exception instead of allocating a new
    * buffer. The exception is a BufferOverflowException thrown from expand, and will restore
    * the position to the point at which the flag was set with the disallowExpansion method.
    * @param ee the exception to throw if expansion is needed
    */
  public void disallowExpansion(ExpansionExceptionGenerator ee) {
    this.disallowExpansion = true;
    this.expansionException = ee;
    this.memoPosition = this.buffer.position();
  }

  /** write the low-order 8 bits of the given int */
  @Override
  public final void write(int b) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(1);
    buffer.put((byte)b);
  }

  protected final void ensureCapacity(int amount) {
    int remainingSpace = this.buffer.capacity() - this.buffer.position();
    if (amount > remainingSpace) {
      expand(amount);
    }
  }

  protected void expand(int amount) {
    if (this.disallowExpansion) {
      this.buffer.position(this.memoPosition);
      this.ignoreWrites = true;
      throw this.expansionException.newExpansionException("expand");
    }

    final ByteBuffer oldBuffer = this.buffer;
    if (this.chunks == null) {
      this.chunks = new LinkedList<ByteBuffer>();
    }    
    oldBuffer.flip(); // now ready for reading
    this.size += oldBuffer.remaining();
    this.chunks.add(oldBuffer);
    if (amount < MIN_CHUNK_SIZE) {
      amount = MIN_CHUNK_SIZE;
    }
    if (this.reuseChunks != null && this.reuseChunks.size() > 0) {
      ByteBuffer bb = (ByteBuffer)this.reuseChunks.getLast();
      if (bb.capacity() >= amount) {
        this.reuseChunks.removeLast();
        this.buffer = bb;
      }
      else {
        this.buffer = null;
        Iterator<?> chunkIter = this.reuseChunks.iterator();
        int numToBeReclaimed = 0;
        if (this.reuseChunks.size() > 10) {
          // reclaim smaller chunks to avoid growing indefinitely
          numToBeReclaimed = this.reuseChunks.size() - 10;
        }
        while (chunkIter.hasNext()) {
          bb = (ByteBuffer)chunkIter.next();
          if (bb.capacity() >= amount) {
            this.buffer = bb;
            chunkIter.remove();
            break;
          }
          else if (numToBeReclaimed-- > 0) {
            chunkIter.remove();
          }
        }
        if (this.buffer == null) {
          this.buffer = ByteBuffer.allocate(amount);
        }
      }
    }
    else {
      this.buffer = ByteBuffer.allocate(amount);
    }
  }

  private final void checkIfWritable() {
    if (!this.writeMode) {
      throw new IllegalStateException(LocalizedStrings.HeapDataOutputStream_NOT_IN_WRITE_MODE.toLocalizedString());
    }
  }
 
  /** override OutputStream's write() */
  @Override
  public  void write(byte[] source, int offset, int len) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    int remainingSpace = this.buffer.capacity() - this.buffer.position();
    if (remainingSpace < len) {
      this.buffer.put(source, offset, remainingSpace);
      offset += remainingSpace;
      len -= remainingSpace;
      ensureCapacity(len);
    }
    this.buffer.put(source, offset, len);
  }

  public final int size() {
    if (this.writeMode) {
      return this.size + this.buffer.position() - this.offset;
    } else {
      return this.size;
    }
  }

  /**
   * Free up any unused memory
   */
  public final void trim() {
    finishWriting();
    if (this.buffer.limit() < this.buffer.capacity()) {
      // buffer is less than half full so allocate a new one and copy it in
      ByteBuffer bb = ByteBuffer.allocate(this.buffer.limit());
      bb.put(this.buffer);
      bb.flip();  // now ready for reading
      this.buffer = bb;
    }
    this.reuseChunks = null;
  }

  public final void writeWithByteArrayWrappedConditionally(byte[] source,
      int offset, int len) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    this.expand(MIN_CHUNK_SIZE);

    // Asif:
    // let us expand first so that current byte buffer goes into the list
    // and a new current byte buffer is created. We than place the wrapped
    // ByteBuffer into the list
    ByteBuffer temp = ByteBuffer.wrap(source, offset, len);
    // Slicing is needed so that other functions like consolidateChunk etc work
    // correctly
    temp = temp.slice();
    temp.limit(len);
    // Hide this buffer in the linked list so that it is not used for any
    // further writes as we want it to be immutable & it is possible that
    // capacity is > limit
    // i.e len does not cover the end of the source.
    this.chunks.add(temp);
    // mark this chunk as non-reusable
    if (this.nonReusableChunks != null) {
      this.nonReusableChunks.set(this.chunks.size() - 1);
    }
    this.size += len;
  }

  private final void consolidateChunks() {
    if (this.chunks != null) {
      final int size = size();
      ByteBuffer newBuffer = ByteBuffer.allocate(size);
      int newBufPos = 0;
      for (ByteBuffer bb: this.chunks) {
        newBuffer.put(bb);
        newBufPos += bb.position();
        newBuffer.position(newBufPos); // works around JRockit 1.4.2.04 bug
      }
      this.chunks = null;
      if (this.nonReusableChunks != null) {
        this.nonReusableChunks.clear();
      }
      newBuffer.put(this.buffer);
      newBufPos += this.buffer.position();
      newBuffer.position(newBufPos); // works around JRockit 1.4.2.04 bug
      this.buffer = newBuffer;
      this.buffer.flip(); // now ready for reading
    }
  }

  /**
   * Prepare the contents for sending again
   */
  public final void rewind() {
    finishWriting();
    this.size = 0;
    if (this.chunks != null) {
      for (ByteBuffer bb: this.chunks) {
        bb.rewind();
        size += bb.remaining();
      }
    }
    this.buffer.rewind();
    size += this.buffer.remaining();
  }

  /**
   * Clear the contents for reuse of this HeapDataOutputStream.
   */
  public final void clearForReuse() {
    resetInternal();
    if (this.chunks != null) {
      if (this.nonReusableChunks != null) {
        if (this.nonReusableChunks.isEmpty()) {
          for (Object chunk : this.chunks) {
            ((ByteBuffer)chunk).clear();
          }
          this.reuseChunks = this.chunks;
        }
        else {
          int index = 0;
          this.reuseChunks = new LinkedList();
          for (Object chunk : this.chunks) {
            if (!this.nonReusableChunks.get(index++)) {
              ((ByteBuffer)chunk).clear();
              this.reuseChunks.add(chunk);
            }
          }
          this.nonReusableChunks.clear();
        }
      }
      this.chunks = null;
    }
  }

  public final void reset() {
    resetInternal();
    this.chunks = null;
    if (this.nonReusableChunks != null) {
      this.nonReusableChunks.clear();
    }
  }

  private void resetInternal() {
    this.size = 0;
    this.buffer.clear();
    this.writeMode = true;
    this.ignoreWrites = false;
    this.disallowExpansion = false;
    this.expansionException = null;
  }

  @Override
  public void flush() {
    // noop
  }
  public void finishWriting() {
    if (this.writeMode) {
      this.ignoreWrites = false;
      this.writeMode = false;
      this.buffer.flip();      
      this.size += this.buffer.remaining();
    }
  }
  @Override
  public void close() {
    reset();
  }

  // @todo darrel: add a method that returns a list of ByteBuffer

  /** gets the contents of this stream as s ByteBuffer, ready for reading.
   * The stream should not be written to past this point until it has been reset.
   */
  public final ByteBuffer toByteBuffer() {
    finishWriting();
    consolidateChunks();
    return this.buffer;
  }
  /** gets the contents of this stream as a byte[].
   * The stream should not be written to past this point until it has been reset.
   */
  public final byte[] toByteArray() {
    ByteBuffer bb = toByteBuffer();
    if (bb.hasArray() && bb.arrayOffset() == 0
        && bb.limit() == bb.capacity()) {
      return bb.array();
    } else {
      // create a new buffer of just the right size and copy the old buffer into it
      ByteBuffer tmp = ByteBuffer.allocate(bb.remaining());
      tmp.put(bb);
      tmp.flip();
      this.buffer = tmp;
      return this.buffer.array();
    }
  }
  
  
  /**
   * Writes this stream to the wrapper object of BytesAndBitsForCompactor type. The
   * byte array retrieved from the HeapDataOutputStream is set in the wrapper
   * object. The byte array may be partially filled. The valid length of data in
   * the byte array is set in the wrapper. It is assumed that the
   * HeapDataOutputStream is appropriately seeded with a byte array from the
   * wrapper. However the filled byte array may or may not be the same as that
   * used for seeding , depending upon whether the data got accommodated in the
   * original byte buffer or not.
   * 
   * @param wrapper
   */
  //Asif
  public void sendTo(BytesAndBitsForCompactor wrapper, byte userBits) {
    ByteBuffer bb = toByteBuffer();
    if (bb.hasArray() && bb.arrayOffset() == 0) {
      wrapper.setData(bb.array(), userBits, bb.limit(), true /* is Reusable */);
    }
    else {
      // create a new buffer of just the right size and copy the old buffer into
      // it
      ByteBuffer tmp = ByteBuffer.allocate(bb.remaining());
      tmp.put(bb);
      tmp.flip();
      this.buffer = tmp;
      byte[] bytes = this.buffer.array();
      wrapper.setData(bytes, userBits, bytes.length, true /* is Reusable */);
    }
  }
  
  /**
   * Write this stream to the specified channel. Call multiple times until size returns zero to make sure all bytes in the stream have been written.
   * @return the number of bytes written, possibly zero.
   * @throws IOException if channel is closed, not yet connected, or some other I/O error occurs.
   */
  public final int sendTo(SocketChannel chan) throws IOException {
    finishWriting();
    if (size() == 0) {
      return 0;
    }
    int result;
    if (this.chunks != null) {
      ByteBuffer[] bufs = new ByteBuffer[this.chunks.size()+1];
      bufs = this.chunks.toArray(bufs);
      bufs[this.chunks.size()] = this.buffer;
      result = (int)chan.write(bufs);
    } else {
      result = chan.write(this.buffer);
    }
    this.size -= result;
    return result;
  }

  public  final void sendTo(SocketChannel chan, ByteBuffer out) throws IOException {
    finishWriting();
    if (size() == 0) {
      return;
    }
    if (this.chunks != null) {
      for (ByteBuffer bb: this.chunks) {
        sendChunkTo(bb, chan, out);
      }
    }
    sendChunkTo(this.buffer, chan, out);
  }

  /**
   * sends the data from "in" by writing it to "sc" through "out" (out is used
   * to chunk to data and is probably a direct memory buffer).
   */
  private final void sendChunkTo(ByteBuffer in, SocketChannel sc, ByteBuffer out) throws IOException {
    int bytesSent = in.remaining();
    final int OUT_MAX = out.capacity();
    out.clear();
    final byte[] bytes = in.array();
    int off = in.arrayOffset() + in.position();
    int len = bytesSent;
    while (len > 0) {
      int bytesThisTime = len;
      if (bytesThisTime > OUT_MAX) {
        bytesThisTime = OUT_MAX;
      }
      out.put(bytes, off, bytesThisTime);
      off += bytesThisTime;
      len -= bytesThisTime;
      out.flip();
      while (out.remaining() > 0) {
        sc.write(out);
      }
      out.clear();
    }
    in.position(in.limit());
    this.size -= bytesSent;
  }

  /**
   * Write the contents of this stream to the byte buffer.
   * @throws BufferOverflowException if out is not large enough to contain all of
   * our data.
   */
  public final void sendTo(ByteBuffer out) {
    finishWriting();
    if (out.remaining() < size()) {
      throw new BufferOverflowException();
    }
    if (this.chunks != null) {
      for (ByteBuffer bb: this.chunks) {
        int bytesToWrite = bb.remaining();
        if (bytesToWrite > 0) {
          out.put(bb);
          this.size -= bytesToWrite;
        }
      }
    }
    {
      ByteBuffer bb = this.buffer;
      int bytesToWrite = bb.remaining();
      if (bytesToWrite > 0) {
        out.put(bb);
        this.size -= bytesToWrite;
      }
    }
  }

  /**
   * Write the contents of this stream to the given byte array.
   * 
   * @throws BufferOverflowException
   *           if buffer is not large enough to contain all of our data.
   */
  public final int sendTo(byte[] buffer, int offset) {
    finishWriting();
    int bytesWritten = 0;
    if (buffer.length < (offset + size())) {
      throw new BufferOverflowException();
    }
    if (this.chunks != null) {
      Iterator<?> it = this.chunks.iterator();
      while (it.hasNext()) {
        ByteBuffer bb = (ByteBuffer)it.next();
        int bytesToWrite = bb.remaining();
        if (bytesToWrite > 0) {
          bb.get(buffer, offset, bytesToWrite);
          this.size -= bytesToWrite;
          offset += bytesToWrite;
          bytesWritten += bytesToWrite;
        }
      }
    }
    ByteBuffer bb = this.buffer;
    int bytesToWrite = bb.remaining();
    if (bytesToWrite > 0) {
      bb.get(buffer, offset, bytesToWrite);
      this.size -= bytesToWrite;
      bytesWritten += bytesToWrite;
    }
    return bytesWritten;
  }

  /**
   * Write the contents of this stream to the specified stream.
   */
  public final void sendTo(OutputStream out) throws IOException {
    finishWriting();
    if (this.chunks != null) {
      for (ByteBuffer bb: this.chunks) {
        int bytesToWrite = bb.remaining();
        if (bytesToWrite > 0) {
          if (bb.hasArray()) {
            out.write(bb.array(), bb.arrayOffset()+bb.position(), bytesToWrite);
            bb.position(bb.limit());
          } else { // fix for bug 43007
            byte[] bytes = new byte[bytesToWrite];
            bb.get(bytes);
            out.write(bytes);
          }
          this.size -= bytesToWrite;
        }
      }
    }
    {
      ByteBuffer bb = this.buffer;
      int bytesToWrite = bb.remaining();
      if (bytesToWrite > 0) {
        if (bb.hasArray()) {
          out.write(bb.array(), bb.arrayOffset()+bb.position(), bytesToWrite);
          bb.position(bb.limit());
        } else {
          byte[] bytes = new byte[bytesToWrite];
          bb.get(bytes);
          out.write(bytes);
        }
        this.size -= bytesToWrite;
      }
    }
  }
  /**
   * Returns an input stream that can be used to read the contents that
   * where written to this output stream.
   */
  public final InputStream getInputStream() {
    return new HDInputStream();
  }
  private final class HDInputStream extends InputStream {
    private Iterator<ByteBuffer> chunkIt;
    private ByteBuffer bb;

    public HDInputStream() {
      finishWriting();
      if (HeapDataOutputStream.this.chunks != null) {
        this.chunkIt = HeapDataOutputStream.this.chunks.iterator();
        nextChunk();
      } else {
        this.chunkIt = null;
        this.bb = HeapDataOutputStream.this.buffer;
      }
    }
    private void nextChunk() {
      if (this.chunkIt != null) {
        if (this.chunkIt.hasNext()) {
          this.bb = this.chunkIt.next();
        } else {
          this.chunkIt = null;
          this.bb = HeapDataOutputStream.this.buffer;
        }
      } else {
        this.bb = null; // EOF
      }
    }

    @Override
    public int available() {
      return size();
    }
    @Override
    public int read() {
      if (available() <= 0) {
        return -1;
      } else {
        int remaining = this.bb.limit() - this.bb.position();
        while (remaining == 0) {
          nextChunk();
          remaining = this.bb.limit() - this.bb.position();
        }
        consume(1);
        return this.bb.get() & 0xFF; // fix for bug 37068
      }
    }
    @Override
    public int read(byte[] dst, int off, int len) {
      if (available() <= 0) {
        return -1;
      } else {
        int readCount = 0;
        while (len > 0 && this.bb != null) {
          if (this.bb.limit() == this.bb.position()) {
            nextChunk();
          } else {
            int remaining = this.bb.limit() - this.bb.position();
            int bytesToRead = len;
            if (len > remaining) {
              bytesToRead = remaining;
            }
            this.bb.get(dst, off, bytesToRead);
            off += bytesToRead;
            len -= bytesToRead;
            readCount += bytesToRead;
          }
        }
        consume(readCount);
        return readCount;
      }
    }
    @Override
    public long skip(long n) {
      int remaining = size();
      if (remaining <= n) {
        // just skip over bytes remaining
        this.chunkIt = null;
        this.bb = null;
        consume(remaining);
        return remaining;
      } else {
        long skipped = 0;
        do {
          long skipsRemaining = n - skipped;
          skipped += chunkSkip(skipsRemaining);
        } while (skipped != n);
        return n;
      }
    }
    private long chunkSkip(long n) {
      int remaining = this.bb.limit() - this.bb.position();
      if (remaining <= n) {
        // skip this whole chunk
        this.bb.position(this.bb.limit());
        nextChunk();
        consume(remaining);
        return remaining;
      } else {
        // skip over just a part of this chunk
        this.bb.position(this.bb.position()+(int)n);
        consume((int)n);
        return n;
      }
    }

    private void consume(int c) {
      HeapDataOutputStream.this.size -= c;
    }
      
  }
  /**
   * Write the contents of this stream to the specified stream.
   * <p>Note this implementation is exactly the same as writeTo(OutputStream)
   * but they do not both implement a common interface.
   */
  public final void sendTo(DataOutput out) throws IOException {
    finishWriting();
    if (this.chunks != null) {
      for (ByteBuffer bb: this.chunks) {
        int bytesToWrite = bb.remaining();
        if (bytesToWrite > 0) {
          if (bb.hasArray()) {
            out.write(bb.array(), bb.arrayOffset()+bb.position(), bytesToWrite);
            bb.position(bb.limit());
          } else {
            byte[] bytes = new byte[bytesToWrite];
            bb.get(bytes);
            out.write(bytes);
          }
          this.size -= bytesToWrite;
        }
      }
    }
    {
      ByteBuffer bb = this.buffer;
      int bytesToWrite = bb.remaining();
      if (bytesToWrite > 0) {
        if (bb.hasArray()) {
          out.write(bb.array(), bb.arrayOffset()+bb.position(), bytesToWrite);
          bb.position(bb.limit());
        } else {
          byte[] bytes = new byte[bytesToWrite];
          bb.get(bytes);
          out.write(bytes);
        }
        this.size -= bytesToWrite;
      }
    }
  }

  // DataOutput methods
  /**
     * Writes a <code>boolean</code> value to this output stream.
     * If the argument <code>v</code>
     * is <code>true</code>, the value <code>(byte)1</code>
     * is written; if <code>v</code> is <code>false</code>,
     * the  value <code>(byte)0</code> is written.
     * The byte written by this method may
     * be read by the <code>readBoolean</code>
     * method of interface <code>DataInput</code>,
     * which will then return a <code>boolean</code>
     * equal to <code>v</code>.
     *
     * @param      v   the boolean to be written.
     */
  public final void writeBoolean(boolean v) {
    write(v ? 1 : 0);
  }

  /**
     * Writes to the output stream the eight low-
     * order bits of the argument <code>v</code>.
     * The 24 high-order bits of <code>v</code>
     * are ignored. (This means  that <code>writeByte</code>
     * does exactly the same thing as <code>write</code>
     * for an integer argument.) The byte written
     * by this method may be read by the <code>readByte</code>
     * method of interface <code>DataInput</code>,
     * which will then return a <code>byte</code>
     * equal to <code>(byte)v</code>.
     *
     * @param      v   the byte value to be written.
     */
  public final void writeByte(int v) {
    write(v);
  }

  /**
     * Writes two bytes to the output
     * stream to represent the value of the argument.
     * The byte values to be written, in the  order
     * shown, are: <p>
     * <pre><code>
     * (byte)(0xff &amp; (v &gt;&gt; 8))
     * (byte)(0xff &amp; v)
     * </code> </pre> <p>
     * The bytes written by this method may be
     * read by the <code>readShort</code> method
     * of interface <code>DataInput</code> , which
     * will then return a <code>short</code> equal
     * to <code>(short)v</code>.
     *
     * @param      v   the <code>short</code> value to be written.
     */
  public final void writeShort(int v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(2);
    buffer.putShort((short)v);
  }

  /**
     * Writes a <code>char</code> value, wich
     * is comprised of two bytes, to the
     * output stream.
     * The byte values to be written, in the  order
     * shown, are:
     * <p><pre><code>
     * (byte)(0xff &amp; (v &gt;&gt; 8))
     * (byte)(0xff &amp; v)
     * </code></pre><p>
     * The bytes written by this method may be
     * read by the <code>readChar</code> method
     * of interface <code>DataInput</code> , which
     * will then return a <code>char</code> equal
     * to <code>(char)v</code>.
     *
     * @param      v   the <code>char</code> value to be written.
     */
  public final void writeChar(int v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(2);
    buffer.putChar((char)v);
  }

  /**
     * Writes an <code>int</code> value, which is
     * comprised of four bytes, to the output stream.
     * The byte values to be written, in the  order
     * shown, are:
     * <p><pre><code>
     * (byte)(0xff &amp; (v &gt;&gt; 24))
     * (byte)(0xff &amp; (v &gt;&gt; 16))
     * (byte)(0xff &amp; (v &gt;&gt; &#32; &#32;8))
     * (byte)(0xff &amp; v)
     * </code></pre><p>
     * The bytes written by this method may be read
     * by the <code>readInt</code> method of interface
     * <code>DataInput</code> , which will then
     * return an <code>int</code> equal to <code>v</code>.
     *
     * @param      v   the <code>int</code> value to be written.
     */
  public final void writeInt(int v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(4);
    buffer.putInt(v);
  }

  /**
     * Writes a <code>long</code> value, which is
     * comprised of eight bytes, to the output stream.
     * The byte values to be written, in the  order
     * shown, are:
     * <p><pre><code>
     * (byte)(0xff &amp; (v &gt;&gt; 56))
     * (byte)(0xff &amp; (v &gt;&gt; 48))
     * (byte)(0xff &amp; (v &gt;&gt; 40))
     * (byte)(0xff &amp; (v &gt;&gt; 32))
     * (byte)(0xff &amp; (v &gt;&gt; 24))
     * (byte)(0xff &amp; (v &gt;&gt; 16))
     * (byte)(0xff &amp; (v &gt;&gt;  8))
     * (byte)(0xff &amp; v)
     * </code></pre><p>
     * The bytes written by this method may be
     * read by the <code>readLong</code> method
     * of interface <code>DataInput</code> , which
     * will then return a <code>long</code> equal
     * to <code>v</code>.
     *
     * @param      v   the <code>long</code> value to be written.
     */
  public final void writeLong(long v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(8);
    buffer.putLong(v);
  }
  
  /**
   * Reserves space in the output for a long
   * and returns a LongUpdater than can be used
   * to update this particular long.
   * @return the LongUpdater that allows the long to be updated
   */
  public final LongUpdater reserveLong() {
    if (this.ignoreWrites) return null;
    checkIfWritable();
    ensureCapacity(8);
    LongUpdater result = new LongUpdater(this.buffer);
    buffer.putLong(0L);
    return result;
  }
  
  public static class LongUpdater {
    private final ByteBuffer bb;
    private final int pos;
    public LongUpdater(ByteBuffer bb) {
      this.bb = bb;
      this.pos = bb.position();
    }
    public void update(long v) {
      this.bb.putLong(this.pos, v);
    }
  }

  /**
     * Writes a <code>float</code> value,
     * which is comprised of four bytes, to the output stream.
     * It does this as if it first converts this
     * <code>float</code> value to an <code>int</code>
     * in exactly the manner of the <code>Float.floatToIntBits</code>
     * method  and then writes the <code>int</code>
     * value in exactly the manner of the  <code>writeInt</code>
     * method.  The bytes written by this method
     * may be read by the <code>readFloat</code>
     * method of interface <code>DataInput</code>,
     * which will then return a <code>float</code>
     * equal to <code>v</code>.
     *
     * @param      v   the <code>float</code> value to be written.
     */
  public final void writeFloat(float v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(4);
    buffer.putFloat(v);
  }

  /**
     * Writes a <code>double</code> value,
     * which is comprised of eight bytes, to the output stream.
     * It does this as if it first converts this
     * <code>double</code> value to a <code>long</code>
     * in exactly the manner of the <code>Double.doubleToLongBits</code>
     * method  and then writes the <code>long</code>
     * value in exactly the manner of the  <code>writeLong</code>
     * method. The bytes written by this method
     * may be read by the <code>readDouble</code>
     * method of interface <code>DataInput</code>,
     * which will then return a <code>double</code>
     * equal to <code>v</code>.
     *
     * @param      v   the <code>double</code> value to be written.
     */
  public final void writeDouble(double v) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(8);
    buffer.putDouble(v);
  }

  /**
     * Writes a string to the output stream.
     * For every character in the string
     * <code>s</code>,  taken in order, one byte
     * is written to the output stream.  If
     * <code>s</code> is <code>null</code>, a <code>NullPointerException</code>
     * is thrown.<p>  If <code>s.length</code>
     * is zero, then no bytes are written. Otherwise,
     * the character <code>s[0]</code> is written
     * first, then <code>s[1]</code>, and so on;
     * the last character written is <code>s[s.length-1]</code>.
     * For each character, one byte is written,
     * the low-order byte, in exactly the manner
     * of the <code>writeByte</code> method . The
     * high-order eight bits of each character
     * in the string are ignored.
     *
     * @param      str the string of bytes to be written.
     */
  public final void writeBytes(String str) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    int strlen = str.length();
    if (strlen > 0) {
      ensureCapacity(strlen);
      // I know this is a deprecated method but it is PERFECT for this impl.
      if (this.buffer.hasArray()) {
        // I know this is a deprecated method but it is PERFECT for this impl.
        int pos = this.buffer.position();
        str.getBytes(0, strlen, this.buffer.array(), this.buffer.arrayOffset() + pos);
        this.buffer.position(pos+strlen);
      } else {
        byte[] bytes = new byte[strlen];
        str.getBytes(0, strlen, bytes, 0);
        this.buffer.put(bytes);
      }
//       for (int i = 0 ; i < len ; i++) {
//         this.buffer.put((byte)s.charAt(i));
//       }
    }
  }

  /**
     * Writes every character in the string <code>s</code>,
     * to the output stream, in order,
     * two bytes per character. If <code>s</code>
     * is <code>null</code>, a <code>NullPointerException</code>
     * is thrown.  If <code>s.length</code>
     * is zero, then no characters are written.
     * Otherwise, the character <code>s[0]</code>
     * is written first, then <code>s[1]</code>,
     * and so on; the last character written is
     * <code>s[s.length-1]</code>. For each character,
     * two bytes are actually written, high-order
     * byte first, in exactly the manner of the
     * <code>writeChar</code> method.
     *
     * @param      s   the string value to be written.
     */
  public final void writeChars(String s) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    int len = s.length();
    if (len > 0) {
      ensureCapacity(len*2);
      for (int i=0; i < len; i++) {
        this.buffer.putChar(s.charAt(i));
      }
    }
  }

  /**
   * Use -Dgemfire.ASCII_STRINGS=true if all String instances contain
   * ASCII characters. Setting this to true gives a performance improvement.
   */
  private static final boolean ASCII_STRINGS = Boolean.getBoolean("gemfire.ASCII_STRINGS");
  
  /**
     * Writes two bytes of length information
     * to the output stream, followed
     * by the Java modified UTF representation
     * of  every character in the string <code>s</code>.
     * If <code>s</code> is <code>null</code>,
     * a <code>NullPointerException</code> is thrown.
     * Each character in the string <code>s</code>
     * is converted to a group of one, two, or
     * three bytes, depending on the value of the
     * character.<p>
     * If a character <code>c</code>
     * is in the range <code>&#92;u0001</code> through
     * <code>&#92;u007f</code>, it is represented
     * by one byte:<p>
     * <pre>(byte)c </pre>  <p>
     * If a character <code>c</code> is <code>&#92;u0000</code>
     * or is in the range <code>&#92;u0080</code>
     * through <code>&#92;u07ff</code>, then it is
     * represented by two bytes, to be written
     * in the order shown:<p> <pre><code>
     * (byte)(0xc0 | (0x1f &amp; (c &gt;&gt; 6)))
     * (byte)(0x80 | (0x3f &amp; c))
     *  </code></pre>  <p> If a character
     * <code>c</code> is in the range <code>&#92;u0800</code>
     * through <code>uffff</code>, then it is
     * represented by three bytes, to be written
     * in the order shown:<p> <pre><code>
     * (byte)(0xe0 | (0x0f &amp; (c &gt;&gt; 12)))
     * (byte)(0x80 | (0x3f &amp; (c &gt;&gt;  6)))
     * (byte)(0x80 | (0x3f &amp; c))
     *  </code></pre>  <p> First,
     * the total number of bytes needed to represent
     * all the characters of <code>s</code> is
     * calculated. If this number is larger than
     * <code>65535</code>, then a <code>UTFDataFormatException</code>
     * is thrown. Otherwise, this length is written
     * to the output stream in exactly the manner
     * of the <code>writeShort</code> method;
     * after this, the one-, two-, or three-byte
     * representation of each character in the
     * string <code>s</code> is written.<p>  The
     * bytes written by this method may be read
     * by the <code>readUTF</code> method of interface
     * <code>DataInput</code> , which will then
     * return a <code>String</code> equal to <code>s</code>.
     *
     * @param      str   the string value to be written.
     */
  public final void writeUTF(String str) throws UTFDataFormatException {
    if (this.ignoreWrites) return;
    checkIfWritable();
    if (ASCII_STRINGS) {
      writeAsciiUTF(str, true);
    } else {
      writeFullUTF(str, true, true);
    }
  }
  private final void writeAsciiUTF(String str, boolean encodeLength) throws UTFDataFormatException {
    int strlen = str.length();
    if (encodeLength && strlen > 65535) {
      throw new UTFDataFormatException();
    }

      int maxLen = strlen;
      if (encodeLength) {
        maxLen += 2;
      }
      ensureCapacity(maxLen);

    if (encodeLength) {
      this.buffer.putShort((short)strlen);
    }
    if (this.buffer.hasArray()) {
      // I know this is a deprecated method but it is PERFECT for this impl.
      int pos = this.buffer.position();
      str.getBytes(0, strlen, this.buffer.array(), this.buffer.arrayOffset() + pos);
      this.buffer.position(pos+strlen);
    } else {
      for (int i = 0 ; i < strlen ; i++) {
        this.buffer.put((byte)str.charAt(i));
      }
//       byte[] bytes = new byte[strlen];
//       str.getBytes(0, strlen, bytes, 0);
//       this.buffer.put(bytes);
    }
  }

  /**
   * The logic used here is based on java's DataOutputStream.writeUTF() from 
   * the version 1.6.0_10.
   * The reader code should use the logic similar to DataOutputStream.readUTF() 
   * from the version 1.6.0_10 to decode this properly.
   */
  public final void writeFullUTF(String str, boolean encodeLength,
      boolean useShortLen) throws UTFDataFormatException {
    int strlen = str.length();
    if (encodeLength && useShortLen && strlen > 65535) {
      throw new UTFDataFormatException();
    }
    // make room for worst case space 3 bytes for each char and 2 for len
    {
      int maxLen = (strlen*3);
      if (encodeLength) {
        maxLen += 2;
      }
      ensureCapacity(maxLen);
    }
    int utfSizeIdx = this.buffer.position();
    if (encodeLength) {
      // skip bytes reserved for length
      if (useShortLen) {
        this.buffer.position(utfSizeIdx + 2);
      } else {
        this.buffer.position(utfSizeIdx + 4);
      }
    }
    for (int i = 0; i < strlen; i++) {
      int c = str.charAt(i);
      if ((c >= 0x0001) && (c <= 0x007F)) {
        this.buffer.put((byte)c);
      } else if (c > 0x07FF) {
        this.buffer.put((byte) (0xE0 | ((c >> 12) & 0x0F)));
        this.buffer.put((byte) (0x80 | ((c >>  6) & 0x3F)));
        this.buffer.put((byte) (0x80 | ((c >>  0) & 0x3F)));
      } else {
        this.buffer.put((byte) (0xC0 | ((c >>  6) & 0x1F)));
        this.buffer.put((byte) (0x80 | ((c >>  0) & 0x3F)));
      }
    }
    int utfLen = this.buffer.position() - utfSizeIdx;
    if (encodeLength) {
      if (useShortLen) {
        utfLen -= 2;
        if (utfLen > 65535) {
          // act as if we wrote nothing to this buffer
          this.buffer.position(utfSizeIdx);
          throw new UTFDataFormatException();
        }
        this.buffer.putShort(utfSizeIdx, (short)utfLen);
      } else {
        utfLen -= 4;
        this.buffer.putInt(utfSizeIdx, utfLen);
      }
    }
  }

  /**
   * Same as {@link #writeUTF} but it does not encode the length in the
   * first two bytes and allows strings longer than 65k to be encoded.
   */
  public void writeUTFNoLength(String str) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    try {
      if (ASCII_STRINGS) {
        writeAsciiUTF(str, false);
      } else {
        writeFullUTF(str, false, false);
      }
    } catch (UTFDataFormatException ex) {
      // this shouldn't happen since we did not encode the length
      throw new IllegalStateException(LocalizedStrings.HeapDataOutputStream_UNEXPECTED_0.toLocalizedString(ex));
    }
  }

  /**
   * Writes the given object to this stream as a byte array.
   * The byte array is produced by serializing v. The serialization
   * is done by calling DataSerializer.writeObject.
   */
  public void writeAsSerializedByteArray(Object v) throws IOException {
    if (this.ignoreWrites) return;
    checkIfWritable();
    ensureCapacity(5);
    if (v instanceof HeapDataOutputStream) {
      HeapDataOutputStream other = (HeapDataOutputStream)v;
      InternalDataSerializer.writeArrayLength(other.size(), this);
      other.sendTo((OutputStream)this);
      other.rewind();
    } else {
      ByteBuffer sizeBuf = this.buffer;
      int sizePos = sizeBuf.position();
      sizeBuf.position(sizePos+5);
      final int preArraySize = size();
      DataSerializer.writeObject(v, this);
      int arraySize = size() - preArraySize;
      sizeBuf.put(sizePos, InternalDataSerializer.INT_ARRAY_LEN);
      sizeBuf.putInt(sizePos+1, arraySize);
    }
  }

  /**
   * Write a byte buffer to this HeapDataOutputStream,
   * 
   * the contents of the buffer between the position and the limit
   * are copied to the output stream.
   */
  public void write(ByteBuffer source) {
    if (this.ignoreWrites) return;
    checkIfWritable();
    int remainingSpace = this.buffer.capacity() - this.buffer.position();
    if (remainingSpace < source.remaining()) {
      int oldLimit = source.limit();
      source.limit(source.position() + remainingSpace);
      this.buffer.put(source);
      source.limit(oldLimit);
      ensureCapacity(source.remaining());
    }
    this.buffer.put(source);
  }

  @Override
  public String toString() {
    return this.version == null ? super.toString() : (super.toString() + " ("
        + this.version + ')');
  }
}
