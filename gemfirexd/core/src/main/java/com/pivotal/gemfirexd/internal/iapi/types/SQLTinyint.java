/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLTinyint

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.types;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.NumberDataType;
import com.pivotal.gemfirexd.internal.iapi.types.NumberDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;

import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * SQLTinyint satisfies the DataValueDescriptor
 * interfaces (i.e., OrderableDataType). It implements a tinyint column, 
 * e.g. for storing a column value; it can be specified
 * when constructed to not allow nulls. Nullability cannot be changed
 * after construction, as it affects the storage size and mechanism.
 * <p>
 * Because OrderableDataType is a subtype of ValueColumn,
 * SQLTinyint can play a role in either a ValueColumn/Row
 * or a OrderableDataType/Row, interchangeably.
 * <p>
 * We assume the store has a flag for nullness of the value,
 * and simply return a 0-length array for the stored form
 * when the value is null.
 */
public final class SQLTinyint
	extends NumberDataType
{

	/*
	 * constants
	 */
	static final int TINYINT_LENGTH = 1;

	/*
	 * object state
	 */
	private byte value;
	private boolean isnull;

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLTinyint.class);

    public int estimateMemoryUsage()
    {
        return BASE_MEMORY_USAGE;
    }

	/*
	 * class interface
	 */

	/*
	 * Constructors
	 */

	/**
	 * No-arg constructor, required by Formattable.
	 * This constructor also gets used when we are
     * allocating space for a byte.
	 */
	public SQLTinyint() 
	{
		isnull = true;
	}

	public SQLTinyint(byte val)
	{
		value = val;
	}

	/* This constructor gets used for the getClone() method */
	public SQLTinyint(byte val, boolean isnull)
	{
		value = val;
		this.isnull = isnull;
	}

	//////////////////////////////////////////////////////////////
	//
	// DataValueDescriptor interface
	// (mostly implemented in DataType)
	//
	//////////////////////////////////////////////////////////////

	/** 
	 * @see DataValueDescriptor#getInt 
	 */
	public int	getInt()
	{
		return (int) value;
	}

	/** 
	 * @see DataValueDescriptor#getByte 
	 */
	public byte	getByte() 
	{
		return value;
	}

	/** 
	 * @see DataValueDescriptor#getShort 
	 */
	public short	getShort()
	{
		return (short) value;
	}

	/** 
	 * @see DataValueDescriptor#getLong 
	 */
	public long	getLong()
	{
		return (long) value;
	}

	/** 
	 * @see DataValueDescriptor#getFloat 
	 */
	public float	getFloat()
	{
		return (float) value;
	}

	/** 
	 * @see DataValueDescriptor#getDouble 
	 */
	public double	getDouble()
	{
		return (double) value;
	}

	/** 
	 * @see DataValueDescriptor#getBoolean 
	 */
	public boolean	getBoolean()
	{
		return (value != 0);
	}

	/** 
	 * @see DataValueDescriptor#getString 
	 */
	public String	getString()
	{
		return (isNull()) ? 
					null:
					Byte.toString(value);
	}

	/** 
	 * @see DataValueDescriptor#getLength 
	 */
	public int	getLength()
	{
		return TINYINT_LENGTH;
	}

	/** 
	 * @see DataValueDescriptor#getObject 
	 */
	public Object	getObject() 
	{
		return (isNull()) ?
					null:
// GemStone changes BEGIN
					  Integer.valueOf(this.value);
					/* (original derby code) new Integer(value); */
// GemStone changes END
	}

	// this is for DataType's error generator
	public String getTypeName()
	{
		return TypeId.TINYINT_NAME;
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */


	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() 
	{
		return StoredFormatIds.SQL_TINYINT_ID;
	}

	/**
	 * @see Storable#isNull
	 */
	public boolean isNull()
	{
		return isnull;
	}

	public void writeExternal(ObjectOutput out) throws IOException {

		// never called when value is null
		if (SanityManager.DEBUG)
			SanityManager.ASSERT(! isNull());

		out.writeByte(value);
	}

	/** @see java.io.Externalizable#readExternal */
	public void readExternal(ObjectInput in) throws IOException {

		value = in.readByte();
		isnull = false;
	}
	public void readExternalFromArray(ArrayInputStream in) throws IOException {

		value = in.readByte();
		isnull = false;
	}


	/**
	 * @see Storable#restoreToNull
	 *
	 */
	public void restoreToNull()
	{
		value = 0;
		isnull = true;
	}

	/** @exception StandardException		Thrown on error */
	protected int typeCompare(DataValueDescriptor arg) throws StandardException
	{
		/* neither are null, get the value */
		int thisValue, otherValue;

		/* Do comparisons with ints to avoid overflow problems */
		thisValue = this.getInt();
		otherValue = arg.getInt();
		if (thisValue == otherValue)
			return 0;
		else if (thisValue > otherValue)
			return 1;
		else
			return -1;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		return new SQLTinyint(value, isnull);
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLTinyint();
	}

	/** 
	 * @see DataValueDescriptor#setValueFromResultSet 
	 *
	 * @exception SQLException		Thrown on error
	 */
	public void setValueFromResultSet(ResultSet resultSet, int colNumber,
									  boolean isNullable)
		throws SQLException
	{
			value = resultSet.getByte(colNumber);
			isnull = (isNullable && resultSet.wasNull());
	}
	/**
		Set the value into a PreparedStatement.

		@exception SQLException Error setting value in PreparedStatement
	*/
	public final void setInto(PreparedStatement ps, int position) throws SQLException {

		if (isNull()) {
			ps.setNull(position, java.sql.Types.TINYINT);
			return;
		}

		ps.setByte(position, value);
	}
	/**
		Set this value into a ResultSet for a subsequent ResultSet.insertRow
		or ResultSet.updateRow. This method will only be called for non-null values.

		@exception SQLException thrown by the ResultSet object
		@exception StandardException thrown by me accessing my value.
	*/
	public final void setInto(ResultSet rs, int position) throws SQLException, StandardException {
		rs.updateByte(position, value);
	}


	/**
		@exception StandardException thrown if string not accepted
	 */
	public void setValue(String theValue)
		throws StandardException
	{
		if (theValue == null)
		{
			value = 0;
			isnull = true;
		}
		else
		{
		    try {
		        value = Byte.valueOf(theValue.trim()).byteValue();
			} catch (NumberFormatException nfe) {
			    throw invalidFormat();
			}
			isnull = false;
		}
	}


	public void setValue(byte theValue)
	{
		value = theValue;
		isnull = false;
	}

	/**
		@exception StandardException if outsideRangeForTinyint
	 */
	public void setValue(short theValue) throws StandardException
	{
		if (theValue > Byte.MAX_VALUE || theValue < Byte.MIN_VALUE)
			throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT", (String)null);
		value = (byte)theValue;
		isnull = false;
	}

	/**
		@exception StandardException if outsideRangeForTinyint
	 */
	public void setValue(int theValue) throws StandardException
	{
		if (theValue > Byte.MAX_VALUE || theValue < Byte.MIN_VALUE)
			throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT", (String)null);
		value = (byte)theValue;
		isnull = false;
	}

	/**
		@exception StandardException if outsideRangeForTinyint
	 */
	public void setValue(long theValue) throws StandardException
	{
		if (theValue > Byte.MAX_VALUE || theValue < Byte.MIN_VALUE)
			throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT", (String)null);
		value = (byte)theValue;
		isnull = false;
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void setValue(float theValue) throws StandardException
	{
		theValue = NumberDataType.normalizeREAL(theValue);

		if (theValue > Byte.MAX_VALUE || theValue < Byte.MIN_VALUE)
			throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT", (String)null);

		float floorValue = (float)Math.floor(theValue);

		value = (byte)floorValue;
		isnull = false;

	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void setValue(double theValue) throws StandardException
	{
		theValue = NumberDataType.normalizeDOUBLE(theValue);

		if (theValue > Byte.MAX_VALUE || theValue < Byte.MIN_VALUE)
			throw outOfRange();

		double floorValue = Math.floor(theValue);

		value = (byte)floorValue;
		isnull = false;
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 */
	public void setValue(boolean theValue)
	{
		value = theValue?(byte)1:(byte)0;
		isnull = false;
	}

	
	protected void setFrom(DataValueDescriptor theValue) throws StandardException {

		setValue(theValue.getByte());
	}


	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
	{
		return TypeId.TINYINT_PRECEDENCE;
	}

	/*
	** SQL Operators
	*/

	/**
	 * The = operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the =
	 * @param right			The value on the right side of the =
	 *
	 * @return	A SQL boolean value telling whether the two parameters are equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue equals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() == right.getByte());
	}

	/**
	 * The <> operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <>
	 * @param right			The value on the right side of the <>
	 *
	 * @return	A SQL boolean value telling whether the two parameters
	 *			are not equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue notEquals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() != right.getByte());
	}

	/**
	 * The < operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <
	 * @param right			The value on the right side of the <
	 *
	 * @return	A SQL boolean value telling whether the first operand is less
	 *			than the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue lessThan(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() < right.getByte());
	}

	/**
	 * The > operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >
	 * @param right			The value on the right side of the >
	 *
	 * @return	A SQL boolean value telling whether the first operand is greater
	 *			than the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue greaterThan(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() > right.getByte());
	}

	/**
	 * The <= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <=
	 * @param right			The value on the right side of the <=
	 *
	 * @return	A SQL boolean value telling whether the first operand is less
	 *			than or equal to the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue lessOrEquals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() <= right.getByte());
	}

	/**
	 * The >= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >=
	 * @param right			The value on the right side of the >=
	 *
	 * @return	A SQL boolean value telling whether the first operand is greater
	 *			than or equal to the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.getByte() >= right.getByte());
	}





	/**
	 * This method implements the * operator for "tinyint * tinyint".
	 *
	 * @param left	The first value to be multiplied
	 * @param right	The second value to be multiplied
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLTinyint containing the result of the multiplication
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue times(NumberDataValue left,
							NumberDataValue right,
							NumberDataValue result)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLTinyint();
		}

		if (left.isNull() || right.isNull())
		{
			result.setToNull();
			return result;
		}

		/*
		** Java does not check for overflow with integral types. We have to
		** check the result ourselves.
		**
		** The product of 2 bytes is an int, so we check to see if the product
		** is in the range of values for a byte.
		*/
		int product = left.getByte() * right.getByte();
		result.setValue(product);
		return result;
	}


	/**
		mod(tinyint, tinyint)
	*/

	public NumberDataValue mod(NumberDataValue dividend,
							 NumberDataValue divisor,
							 NumberDataValue result)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLTinyint();
		}

		if (dividend.isNull() || divisor.isNull())
		{
			result.setToNull();
			return result;
		}

		/* Catch divide by 0 */
		byte byteDivisor = divisor.getByte();
		if (byteDivisor == 0)
		{
			throw StandardException.newException(SQLState.LANG_DIVIDE_BY_ZERO);
		}

		result.setValue(dividend.getByte() % byteDivisor);
		return result;
	}
	/**
	 * This method implements the unary minus operator for tinyint.
	 *
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLTinyint containing the result of the division
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue minus(NumberDataValue result)
									throws StandardException
	{
		if (result == null)
		{
			result = new SQLTinyint();
		}

		if (this.isNull())
		{
			result.setToNull();
			return result;
		}

		int operandValue = this.getByte();

		result.setValue(-operandValue);
		return result;
	}

    /**
     * This method implements the isNegative method.
     *
     * @return  A boolean.  If this.value is negative, return true.
     *          For positive values or null, return false.
     */

    protected boolean isNegative()
    {
        return !isNull() && value < 0;
    }

	/*
	 * String display of value
	 */

	public String toString()
	{
		if (isNull())
			return "NULL";
		else
			return Byte.toString(value);
	}


	/*
	 * Hash code
	 */
	public int hashCode()
	{
		return (int) value;
	}
// GemStone changes BEGIN

  @Override
  public final void toData(final DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      out.writeByte(this.value);
      return;
    }
   this.writeNullDVD(out);
  }

  @Override
  public final void fromDataForOptimizedResultHolder(final DataInput dis)
      throws IOException, ClassNotFoundException {
    this.value = dis.readByte();
    this.isnull = false;
  }

  @Override
  public final void toDataForOptimizedResultHolder(final DataOutput dos)
      throws IOException {
    assert !isNull();
    dos.writeByte(this.value);
  }

  @Override
  public final int getLengthInBytes(DataTypeDescriptor dtd) {
    if (!isNull()) {
      return 1;
    }
    return 0;
  }

  @Override
  public final int writeBytes(final byte[] outBytes, final int offset,
      DataTypeDescriptor dtd) {
    outBytes[offset] = this.value;
    return 1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    assert columnWidth == 1: columnWidth;
    this.value = inBytes[offset];
    return 1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final UnsafeWrapper unsafe, long memOffset,
      final int columnWidth, ByteSource bs) {
    assert columnWidth == 1: columnWidth;
    this.value = unsafe.getByte(memOffset);
    return 1;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    assert !isNull();
    return ResolverUtils.addByteToBucketHash(this.value, hash,
        getTypeFormatId());
  }

  static final byte getAsByte(final byte[] inBytes, int offset) {
    return inBytes[offset];
  }

  static final byte getAsByte(final UnsafeWrapper unsafe, long memOffset) {
    return unsafe.getByte(memOffset);
  }

  @Override
  public byte getTypeId() {
    return DSCODE.BYTE;
  }
// GemStone changes END
}
