/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package io.snappydata.thrift;

import java.nio.ByteBuffer;
import java.util.*;
import javax.annotation.Generated;

import com.gemstone.gemfire.internal.shared.ByteBufferReference;
import io.snappydata.thrift.common.TProtocolDirectBinary;
import io.snappydata.thrift.common.ThriftUtils;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TBaseHelper;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;
import org.apache.thrift.scheme.TupleScheme;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-02-07")
public class BlobChunk implements org.apache.thrift.TBase<BlobChunk, BlobChunk._Fields>, java.io.Serializable, Cloneable, Comparable<BlobChunk> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("BlobChunk");

  private static final org.apache.thrift.protocol.TField CHUNK_FIELD_DESC = new org.apache.thrift.protocol.TField("chunk", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField LAST_FIELD_DESC = new org.apache.thrift.protocol.TField("last", org.apache.thrift.protocol.TType.BOOL, (short)2);
  private static final org.apache.thrift.protocol.TField LOB_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("lobId", org.apache.thrift.protocol.TType.I64, (short)3);
  private static final org.apache.thrift.protocol.TField OFFSET_FIELD_DESC = new org.apache.thrift.protocol.TField("offset", org.apache.thrift.protocol.TType.I64, (short)4);
  private static final org.apache.thrift.protocol.TField TOTAL_LENGTH_FIELD_DESC = new org.apache.thrift.protocol.TField("totalLength", org.apache.thrift.protocol.TType.I64, (short)5);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new BlobChunkStandardSchemeFactory());
    schemes.put(TupleScheme.class, new BlobChunkTupleSchemeFactory());
  }

  public ByteBuffer chunk; // required
  public boolean last; // required
  public long lobId; // optional
  public long offset; // optional
  public long totalLength; // optional

  /**
   * Transient field to explicitly release reference to ByteBuffer after write.
   */
  private transient ByteBufferReference chunkReference;

  public BlobChunk(ByteBufferReference reference, boolean last) {
    this();
    this.chunkReference = reference;
    this.last = last;
    setLastIsSet(true);
  }

  public int size() {
    final ByteBufferReference reference = this.chunkReference;
    if (reference != null) {
      return reference.size();
    } else {
      return this.chunk.remaining();
    }
  }

  public ByteBuffer getChunkRetain() {
    final ByteBufferReference reference = this.chunkReference;
    if (reference != null) {
      return reference.getBufferRetain();
    } else {
      return this.chunk;
    }
  }

  public void releaseChunk() {
    final ByteBufferReference reference = this.chunkReference;
    if (reference != null && reference.needsRelease()) {
      reference.release();
    }
  }

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    CHUNK((short)1, "chunk"),
    LAST((short)2, "last"),
    LOB_ID((short)3, "lobId"),
    OFFSET((short)4, "offset"),
    TOTAL_LENGTH((short)5, "totalLength");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // CHUNK
          return CHUNK;
        case 2: // LAST
          return LAST;
        case 3: // LOB_ID
          return LOB_ID;
        case 4: // OFFSET
          return OFFSET;
        case 5: // TOTAL_LENGTH
          return TOTAL_LENGTH;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __LAST_ISSET_ID = 0;
  private static final int __LOBID_ISSET_ID = 1;
  private static final int __OFFSET_ISSET_ID = 2;
  private static final int __TOTALLENGTH_ISSET_ID = 3;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.LOB_ID,_Fields.OFFSET,_Fields.TOTAL_LENGTH};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.CHUNK, new org.apache.thrift.meta_data.FieldMetaData("chunk", org.apache.thrift.TFieldRequirementType.REQUIRED,
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    tmpMap.put(_Fields.LAST, new org.apache.thrift.meta_data.FieldMetaData("last", org.apache.thrift.TFieldRequirementType.REQUIRED,
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.BOOL)));
    tmpMap.put(_Fields.LOB_ID, new org.apache.thrift.meta_data.FieldMetaData("lobId", org.apache.thrift.TFieldRequirementType.OPTIONAL,
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.OFFSET, new org.apache.thrift.meta_data.FieldMetaData("offset", org.apache.thrift.TFieldRequirementType.OPTIONAL,
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.TOTAL_LENGTH, new org.apache.thrift.meta_data.FieldMetaData("totalLength", org.apache.thrift.TFieldRequirementType.OPTIONAL,
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(BlobChunk.class, metaDataMap);
  }

  public BlobChunk() {
  }

  public BlobChunk(
    ByteBuffer chunk,
    boolean last)
  {
    this();
    this.chunk = chunk;
    this.last = last;
    setLastIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BlobChunk(BlobChunk other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetChunk()) {
      this.chunk = ByteBuffer.wrap(other.getChunk());
    }
    this.last = other.last;
    this.lobId = other.lobId;
    this.offset = other.offset;
    this.totalLength = other.totalLength;
  }

  public BlobChunk deepCopy() {
    return new BlobChunk(this);
  }

  @Override
  public void clear() {
    this.chunk = null;
    setLastIsSet(false);
    this.last = false;
    setLobIdIsSet(false);
    this.lobId = 0;
    setOffsetIsSet(false);
    this.offset = 0;
    setTotalLengthIsSet(false);
    this.totalLength = 0;
  }

  public byte[] getChunk() {
    ByteBuffer chunk = getChunkRetain();
    if (!TBaseHelper.wrapsFullArray(chunk)) {
      // replace with byte array wrapper
      this.chunk = chunk = ByteBuffer.wrap(ThriftUtils.toBytes(chunk));
      releaseChunk();
    }
    return chunk.array();
  }

  public BlobChunk setChunk(byte[] chunk) {
    this.chunk = chunk == null ? (ByteBuffer)null : ByteBuffer.wrap(Arrays.copyOf(chunk, chunk.length));
    return this;
  }

  public BlobChunk setChunk(ByteBuffer chunk) {
    this.chunk = ByteBuffer.wrap(ThriftUtils.toBytes(chunk));
    return this;
  }

  public void unsetChunk() {
    this.chunk = null;
  }

  /** Returns true if field chunk is set (has been assigned a value) and false otherwise */
  public boolean isSetChunk() {
    return this.chunk != null || this.chunkReference != null;
  }

  public void setChunkIsSet(boolean value) {
    if (!value) {
      this.chunk = null;
    }
  }

  public boolean isLast() {
    return this.last;
  }

  public BlobChunk setLast(boolean last) {
    this.last = last;
    setLastIsSet(true);
    return this;
  }

  public void unsetLast() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __LAST_ISSET_ID);
  }

  /** Returns true if field last is set (has been assigned a value) and false otherwise */
  public boolean isSetLast() {
    return EncodingUtils.testBit(__isset_bitfield, __LAST_ISSET_ID);
  }

  public void setLastIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __LAST_ISSET_ID, value);
  }

  public long getLobId() {
    return this.lobId;
  }

  public BlobChunk setLobId(long lobId) {
    this.lobId = lobId;
    setLobIdIsSet(true);
    return this;
  }

  public void unsetLobId() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __LOBID_ISSET_ID);
  }

  /** Returns true if field lobId is set (has been assigned a value) and false otherwise */
  public boolean isSetLobId() {
    return EncodingUtils.testBit(__isset_bitfield, __LOBID_ISSET_ID);
  }

  public void setLobIdIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __LOBID_ISSET_ID, value);
  }

  public long getOffset() {
    return this.offset;
  }

  public BlobChunk setOffset(long offset) {
    this.offset = offset;
    setOffsetIsSet(true);
    return this;
  }

  public void unsetOffset() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __OFFSET_ISSET_ID);
  }

  /** Returns true if field offset is set (has been assigned a value) and false otherwise */
  public boolean isSetOffset() {
    return EncodingUtils.testBit(__isset_bitfield, __OFFSET_ISSET_ID);
  }

  public void setOffsetIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __OFFSET_ISSET_ID, value);
  }

  public long getTotalLength() {
    return this.totalLength;
  }

  public BlobChunk setTotalLength(long totalLength) {
    this.totalLength = totalLength;
    setTotalLengthIsSet(true);
    return this;
  }

  public void unsetTotalLength() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __TOTALLENGTH_ISSET_ID);
  }

  /** Returns true if field totalLength is set (has been assigned a value) and false otherwise */
  public boolean isSetTotalLength() {
    return EncodingUtils.testBit(__isset_bitfield, __TOTALLENGTH_ISSET_ID);
  }

  public void setTotalLengthIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __TOTALLENGTH_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case CHUNK:
      if (value == null) {
        unsetChunk();
      } else {
        setChunk((ByteBuffer)value);
      }
      break;

    case LAST:
      if (value == null) {
        unsetLast();
      } else {
        setLast((Boolean)value);
      }
      break;

    case LOB_ID:
      if (value == null) {
        unsetLobId();
      } else {
        setLobId((Long)value);
      }
      break;

    case OFFSET:
      if (value == null) {
        unsetOffset();
      } else {
        setOffset((Long)value);
      }
      break;

    case TOTAL_LENGTH:
      if (value == null) {
        unsetTotalLength();
      } else {
        setTotalLength((Long)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case CHUNK:
      return getChunk();

    case LAST:
      return isLast();

    case LOB_ID:
      return getLobId();

    case OFFSET:
      return getOffset();

    case TOTAL_LENGTH:
      return getTotalLength();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case CHUNK:
      return isSetChunk();
    case LAST:
      return isSetLast();
    case LOB_ID:
      return isSetLobId();
    case OFFSET:
      return isSetOffset();
    case TOTAL_LENGTH:
      return isSetTotalLength();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof BlobChunk)
      return this.equals((BlobChunk)that);
    return false;
  }

  public boolean equals(BlobChunk that) {
    if (that == null)
      return false;

    final ByteBuffer thisChunk = getChunkRetain();
    final ByteBuffer thatChunk = that.getChunkRetain();
    boolean this_present_chunk = thisChunk != null;
    boolean that_present_chunk = thatChunk != null;
    if (this_present_chunk || that_present_chunk) {
      if (!(this_present_chunk && that_present_chunk))
        return false;
      if (!thisChunk.equals(thatChunk))
        return false;
    }

    boolean this_present_last = true;
    boolean that_present_last = true;
    if (this_present_last || that_present_last) {
      if (!(this_present_last && that_present_last))
        return false;
      if (this.last != that.last)
        return false;
    }

    boolean this_present_lobId = true && this.isSetLobId();
    boolean that_present_lobId = true && that.isSetLobId();
    if (this_present_lobId || that_present_lobId) {
      if (!(this_present_lobId && that_present_lobId))
        return false;
      if (this.lobId != that.lobId)
        return false;
    }

    boolean this_present_offset = true && this.isSetOffset();
    boolean that_present_offset = true && that.isSetOffset();
    if (this_present_offset || that_present_offset) {
      if (!(this_present_offset && that_present_offset))
        return false;
      if (this.offset != that.offset)
        return false;
    }

    boolean this_present_totalLength = true && this.isSetTotalLength();
    boolean that_present_totalLength = true && that.isSetTotalLength();
    if (this_present_totalLength || that_present_totalLength) {
      if (!(this_present_totalLength && that_present_totalLength))
        return false;
      if (this.totalLength != that.totalLength)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    ByteBuffer chunk = getChunkRetain();
    boolean present_chunk = chunk != null;
    list.add(present_chunk);
    if (present_chunk)
      list.add(chunk);

    boolean present_last = true;
    list.add(present_last);
    if (present_last)
      list.add(last);

    boolean present_lobId = true && (isSetLobId());
    list.add(present_lobId);
    if (present_lobId)
      list.add(lobId);

    boolean present_offset = true && (isSetOffset());
    list.add(present_offset);
    if (present_offset)
      list.add(offset);

    boolean present_totalLength = true && (isSetTotalLength());
    list.add(present_totalLength);
    if (present_totalLength)
      list.add(totalLength);

    int hash = list.hashCode();
    releaseChunk();
    return hash;
  }

  @Override
  public int compareTo(BlobChunk other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    ByteBuffer thisChunk = getChunkRetain();
    ByteBuffer otherChunk = other.getChunkRetain();
    lastComparison = Boolean.valueOf(thisChunk != null).compareTo(otherChunk != null);
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (thisChunk != null) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(thisChunk, otherChunk);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetLast()).compareTo(other.isSetLast());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLast()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.last, other.last);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetLobId()).compareTo(other.isSetLobId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLobId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.lobId, other.lobId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetOffset()).compareTo(other.isSetOffset());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetOffset()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.offset, other.offset);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetTotalLength()).compareTo(other.isSetTotalLength());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetTotalLength()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.totalLength, other.totalLength);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("BlobChunk(");
    boolean first = true;

    sb.append("chunk:");
    if (!isSetChunk()) {
      sb.append("null");
    } else {
      // change to on-heap buffer if required
      getChunk();
      org.apache.thrift.TBaseHelper.toString(this.chunk, sb);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("last:");
    sb.append(this.last);
    first = false;
    if (isSetLobId()) {
      if (!first) sb.append(", ");
      sb.append("lobId:");
      sb.append(this.lobId);
      first = false;
    }
    if (isSetOffset()) {
      if (!first) sb.append(", ");
      sb.append("offset:");
      sb.append(this.offset);
      first = false;
    }
    if (isSetTotalLength()) {
      if (!first) sb.append(", ");
      sb.append("totalLength:");
      sb.append(this.totalLength);
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (!isSetChunk()) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'chunk' was not present! Struct: " + toString());
    }
    // alas, we cannot check 'last' because it's a primitive and you chose the non-beans generator.
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class BlobChunkStandardSchemeFactory implements SchemeFactory {
    public BlobChunkStandardScheme getScheme() {
      return new BlobChunkStandardScheme();
    }
  }

  private static class BlobChunkStandardScheme extends StandardScheme<BlobChunk> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, BlobChunk struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) {
          break;
        }
        switch (schemeField.id) {
          case 1: // CHUNK
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              if (iprot instanceof TProtocolDirectBinary) {
                struct.chunk = ((TProtocolDirectBinary)iprot).readDirectBinary();
              } else {
                struct.chunk = iprot.readBinary();
              }
              struct.setChunkIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // LAST
            if (schemeField.type == org.apache.thrift.protocol.TType.BOOL) {
              struct.last = iprot.readBool();
              struct.setLastIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // LOB_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.lobId = iprot.readI64();
              struct.setLobIdIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // OFFSET
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.offset = iprot.readI64();
              struct.setOffsetIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // TOTAL_LENGTH
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.totalLength = iprot.readI64();
              struct.setTotalLengthIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      if (!struct.isSetLast()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'last' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, BlobChunk struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      final ByteBuffer chunk = struct.getChunkRetain();
      if (chunk != null) {
        try {
          oprot.writeFieldBegin(CHUNK_FIELD_DESC);
          oprot.writeBinary(chunk);
          oprot.writeFieldEnd();
        } finally {
          struct.releaseChunk();
        }
      }
      oprot.writeFieldBegin(LAST_FIELD_DESC);
      oprot.writeBool(struct.last);
      oprot.writeFieldEnd();
      if (struct.isSetLobId()) {
        oprot.writeFieldBegin(LOB_ID_FIELD_DESC);
        oprot.writeI64(struct.lobId);
        oprot.writeFieldEnd();
      }
      if (struct.isSetOffset()) {
        oprot.writeFieldBegin(OFFSET_FIELD_DESC);
        oprot.writeI64(struct.offset);
        oprot.writeFieldEnd();
      }
      if (struct.isSetTotalLength()) {
        oprot.writeFieldBegin(TOTAL_LENGTH_FIELD_DESC);
        oprot.writeI64(struct.totalLength);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class BlobChunkTupleSchemeFactory implements SchemeFactory {
    public BlobChunkTupleScheme getScheme() {
      return new BlobChunkTupleScheme();
    }
  }

  private static class BlobChunkTupleScheme extends TupleScheme<BlobChunk> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, BlobChunk struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      final ByteBuffer chunk = struct.getChunkRetain();
      try {
        oprot.writeBinary(chunk);
      } finally {
        struct.releaseChunk();
      }
      oprot.writeBool(struct.last);
      BitSet optionals = new BitSet();
      if (struct.isSetLobId()) {
        optionals.set(0);
      }
      if (struct.isSetOffset()) {
        optionals.set(1);
      }
      if (struct.isSetTotalLength()) {
        optionals.set(2);
      }
      oprot.writeBitSet(optionals, 3);
      if (struct.isSetLobId()) {
        oprot.writeI64(struct.lobId);
      }
      if (struct.isSetOffset()) {
        oprot.writeI64(struct.offset);
      }
      if (struct.isSetTotalLength()) {
        oprot.writeI64(struct.totalLength);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, BlobChunk struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.chunk = iprot.readBinary();
      struct.setChunkIsSet(true);
      struct.last = iprot.readBool();
      struct.setLastIsSet(true);
      BitSet incoming = iprot.readBitSet(3);
      if (incoming.get(0)) {
        struct.lobId = iprot.readI64();
        struct.setLobIdIsSet(true);
      }
      if (incoming.get(1)) {
        struct.offset = iprot.readI64();
        struct.setOffsetIsSet(true);
      }
      if (incoming.get(2)) {
        struct.totalLength = iprot.readI64();
        struct.setTotalLengthIsSet(true);
      }
    }
  }

}

