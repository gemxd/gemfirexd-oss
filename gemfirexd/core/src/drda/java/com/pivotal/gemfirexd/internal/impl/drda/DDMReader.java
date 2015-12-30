/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.drda.DDMReader

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.impl.drda;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
	The DDMReader is used to read DRDA protocol.   DRDA Protocol is divided into
	three layers corresponding to the DDM three-tier architecture. For each layer,
	their is a DSS (Data Stream Structure) defined.
		Layer A 	Communications management services
		Layer B		Agent services
		Layer C 	Data management services
	<P>
	At layer A are request, reply and data correlation, structure chaining,
	continuation or termination of chains when errors are detected, interleaving
	and multi-leaving request, reply, and data DSSs for multitasking environments.
	For TCP/IP, the format of the DDM envelope is
		2 bytes		Length of the data
		1 byte		'D0' - indicates DDM data
		1 byte		DDM format byte(DSSFMT) - type of DSS(RQSDSS,RPYDSS), whether it is
					chained, information about the next chained DSS
		2 bytes		request correlation identifier
	<P>
	The correlation identifier ties together a request, the request data and the
	reply.  In a chained DSS, each request has a correlation identifier which
	is higher than the previous request (all correlation identifiers must
	be greater than 0).
	<P>
	At layer B are object mapping, object validation and command routing.
	Layer B objects with data 5 bytes less than 32K bytes consist of
		2 bytes		Length
		2 bytes		Type of the object (code point)
		Object data
	Object data is either SCALAR or COLLECTION data.  Scalar data consists of
	a string of bytes formatted as the class description of the object required.
	Collections consist of a set of objects in which the entries in the collection
	are nested within the length/ code point of the collection.
	<P>
	Layer B objects with data >=32763 bytes long format is 
		2 bytes		Length - length of class, length, and extended total length fields
					(high order bit set, indicating >=32763)
		2 bytes		Type of the object (code point)
		n bytes		Extended total length - length of the object
					(n = Length - 4)
		Object data
	<P>
	At layer C are services each class of DDM object provides.

		    |-------------------------------------------|
	Layer C | Specific	|	Specific	|	Specific	|
		    | Commands	|	Replies		| Scalars and	|
			| and their |  and their    | Collections   |
			|-------------------------------------------|----------------|
	Layer B | Commands  |    Reply		| Scalars and   | Communications |
			|			|   Messages	| Collections	|                |
			|-----------|---------------|---------------|----------------|
	Layer A |  RQSDSS   |   RPYDSS		| OBJDSS		| CMNDSS         |
			|           |               |               | Mapped Data    |
			|-----------|---------------|---------------|----------------|
			|                DDM Data Stream Structures                  |
			|------------------------------------------------------------|
			
	DSS's may be chained so that more than one can be transmitted at a time
	to improve performance.
	For more details, see DRDA Volume 3 (Distributed Data Management(DDM)
		Architecture (DDS definition)
*/
class DDMReader
{
	private final static int DEFAULT_BUFFER_SIZE = 32767;
	private final static int MAX_MARKS_NESTING = 10;
	private final static int NO_CODEPOINT = -1;
	private final static int EMPTY_STACK = -1;
	private final static boolean ADJUST_LENGTHS = true;
	private final static boolean NO_ADJUST_LENGTHS = false;
	private final static long MAX_EXTDTA_SIZE= Long.MAX_VALUE;
// GemStone changes BEGIN
	//private static boolean internalTrace = true; // not used
// GemStone changes END

	// magnitude represented in an int array, used in BigDecimal conversion
    private static final int[][] tenRadixMagnitude = {
      { 0x3b9aca00 }, // 10^9
      { 0x0de0b6b3, 0xa7640000 }, // 10^18
      { 0x033b2e3c, 0x9fd0803c, 0xe8000000 }, // 10^27
// GemStone changes BEGIN
      { 0x00c097ce, 0x7bc90715, 0xb34b9f10, 0x00000000 }, // 10^36
      { 0x002cd76f, 0xe086b93c, 0xe2f768a0, 0x0b22a000, 0x00000000 }, // 10^45
      { 0x000a70c3, 0xc40a64e6, 0xc5199909, 0x0b65f67d, 0x92400000, 0x00000000 }, // 10^54
      { 0x00026e4d, 0x30eccc32, 0x15dd8f31, 0x57d27e23, 0xacbdcfe6, 0x80000000,
          0x00000000 }, // 10^63
      { 0x000090e4, 0x0fbeea1d, 0x3a4abc89, 0x55e946fe, 0x31cdcf66, 0xf634e100,
          0x00000000, 0x00000000 }, // 10^72
      { 0x000021bc, 0x2b266d3a, 0x36bf5a68, 0x0a2ecf7b, 0x5c68f7e7, 0xe45589f0,
          0x138a0000, 0x00000000, 0x00000000 }, // 10^81
      { 0x000007da, 0xc3c24a56, 0x71d2f825, 0x5a450203, 0x2e391f32, 0x66bc0c6a,
          0xcdc3fe6e, 0xe4000000, 0x00000000, 0x00000000 }, // 10^90
      { 0x000001d4, 0x2aea2879, 0xf2e44dea, 0x5a13ae34, 0x65277b06, 0x749ce90c,
          0x777839e7, 0x4404a7e8, 0x00000000, 0x00000000, 0x00000000 }, // 10^99
      { 0x0000006d, 0x00f7320d, 0x3846f4f4, 0x0737a410, 0x664a4aeb, 0xa5d5681d,
          0xe0ec69ef, 0xff7c792b, 0x260d1000, 0x00000000, 0x00000000,
          0x00000000 }, // 10^108
      { 0x00000019, 0x61219000, 0x356aa38b, 0x95beb7fa, 0x60e598ac, 0x0017e239,
          0xe8f75e1c, 0x95d8c066, 0x0d55f291, 0xeea00000, 0x00000000,
          0x00000000, 0x00000000 }, // 10^117
      { 0x00000005, 0xe8bb3105, 0x280fdffd, 0xb2872d49, 0xde1ec4c2, 0xa5f54794,
          0x4808d952, 0x63fda6f8, 0x4a475b21, 0x5f2f928a, 0x40000000,
          0x00000000, 0x00000000, 0x00000000 }, // 10^126
      { 0x00000001, 0x6035ce8b, 0x6203d3c8, 0xbd80bb9f, 0xee5cff1f, 0x8098f3ff,
          0xc648603f, 0xb4169ef7, 0xd03b0b89, 0xd0eb8e7f, 0x8d8b9680,
          0x00000000, 0x00000000, 0x00000000, 0x00000000 }, // 10^135
// GemStone changes END
    };

	private DRDAConnThread agent;
	private CcsidManager ccsidManager;

	// data buffer
	private byte[] buffer;
	private int pos;
	private int count;

	// DDM object collection
	// top of stack
	private int topDdmCollectionStack;
	// length of each object in the stack
	private long[] ddmCollectionLenStack;

	// DDM object length
	private long ddmScalarLen;

	// DSS Length
	private int dssLength;

	// DSS is larger than 32762 (continuation bit is set) so DSS is continued
	private boolean dssIsContinued;

	private boolean terminateChainOnErr;

	// next DSS in the chain has the same correlator
	private boolean dssIsChainedWithSameID;

	// next DSS in the chain has a different correlator
	private boolean dssIsChainedWithDiffID;
	
	// correlation id for the current DSS
	private int dssCorrelationID;

	// previous corelation id
	private int prevCorrelationID;

	// current server codepoint
	private int svrcod;

	// trace object of the associated session
	private DssTrace dssTrace;

	// input stream
	private InputStream inputStream;
    
    // State whether doing layer B Streaming or not.
    private boolean doingLayerBStreaming = false;;
    
    // For JMX statistics. Volatile to ensure we 
    // get one complete long, but we don't bother to synchronize, 
    // since this is just statistics.
    
    volatile long totalByteCount = 0;
    final AtomicLong bytesRead = new AtomicLong(0);


	// constructor
	DDMReader (DRDAConnThread agent, DssTrace dssTrace)
	{
		buffer = new byte[DEFAULT_BUFFER_SIZE];
		ddmCollectionLenStack = new long[MAX_MARKS_NESTING];
		initialize(agent, dssTrace);
	}
	/**
	 * This constructor is used for testing the protocol
	 * It is used by TestProto to read the protocol returned by the
	 * server 
	 */
	DDMReader(CcsidManager ccsidManager, InputStream inputStream)
	{
		buffer = new byte[DEFAULT_BUFFER_SIZE];
		ddmCollectionLenStack = new long[MAX_MARKS_NESTING];
		this.ccsidManager = ccsidManager;
		this.inputStream = inputStream;
		initialize(null, null);
		// turn off tracing
// GemStone changes BEGIN
		//internalTrace = false;
// GemStone changes END
	}
	/**
	 * This initializer is used for testing the protocol
	 * It is used by TestProto for the reader it uses
	 */
	protected void initialize(InputStream inputStream)
	{
		this.inputStream = inputStream;
		initialize(null, null);
	}

	/**
	 * Initialize values for this session, the reader is reused so we need to
	 * set null and 0 values
	 */
	protected void initialize(DRDAConnThread agent, DssTrace dssTrace)
  	{
		this.agent = agent;
		if (agent != null)
		{
			ccsidManager = agent.ccsidManager;
			inputStream = agent.getInputStream();
		}
		topDdmCollectionStack = EMPTY_STACK;
    	svrcod = 0;
		pos = 0;
		count = 0;
		ddmScalarLen = 0;
		dssLength = 0;
		prevCorrelationID = DssConstants.CORRELATION_ID_UNKNOWN;
		dssCorrelationID = DssConstants.CORRELATION_ID_UNKNOWN;
		this.dssTrace = dssTrace;
	}

	protected boolean terminateChainOnErr()
	{
		return terminateChainOnErr;
	}

	/**
	 * Next DSS has same correlator as current DSS
	 *
	 * @return true if next DSS has the same correlator as current DSS
	 */
  	protected boolean isChainedWithSameID()
  	{
    	return dssIsChainedWithSameID;
  	}

	/**
	 * Next DSS has different correlator than current DSS
	 *
	 * @return true if next DSS has a different correlator than current DSS
	 */
  	protected boolean isChainedWithDiffID()
  	{
    	return dssIsChainedWithDiffID;
  	}

	/**
	 * Length of current DDM object
	 *
	 * @return length of DDM object
	 */
	protected long getDdmLength()
	{
		return ddmScalarLen;
	}

	/**
	 * Is there more in this DDM object
	 *
	 * @return true if DDM length is > 0
	 */
	protected boolean moreDdmData()
	{
		return ddmScalarLen > 0;
	}

	/**
	 * Is there more in this DDS object
	 *
	 * @return true if DDS length is > 0
	 */
	protected boolean moreDssData()
	{
		return dssLength > 0;
	}

	/** 
	 * Is there more data in the buffer
	 *
	 * @return true if there is more data in the buffer
	 */
	protected boolean moreData()
	{
		return (pos - count) > 0;
	}

	/**
	 * Check for the command protocol
	 *
	 * @return true if this is a command; false otherwise
	 *
	 * @exception DRDProtocolException
	 */
	protected boolean isCmd() throws DRDAProtocolException, java.io.UnsupportedEncodingException
	{
		ensureALayerDataInBuffer(4);
		String val = new String(buffer, 0, 4, NetworkServerControlImpl.DEFAULT_ENCODING);
		return NetworkServerControlImpl.isCmd(val);
	}

	/**
	 * Read DSS header
	 * DSS Header format is 
	 * 	2 bytes	- length
	 *	1 byte	- 'D0'	- indicates DDM data
	 * 	1 byte	- DSS format 
	 *		|---|---------|----------|
	 *		| 0	|  flags  |  type    |
	 *		|---|---------|----------|
	 *		| 0 | 1  2  3 | 4 5 6 7  |
	 *		|---|---------|----------|
	 *		bit 0 - '0'
	 *		bit 1 - '0' - unchained, '1' - chained
	 *		bit 2 - '0'	- do not continue on error, '1' - continue on error
	 *		bit 3 - '0' - next DSS has different correlator, '1' - next DSS has
	 *						same correlator
	 *		type - 1 - Request DSS
	 *			 - 2 - Reply DSS
	 *			 - 3 - Object DSS
	 *			 - 4 - Communications DSS
	 *			 - 5 - Request DSS where no reply is expected
	 *	2 bytes - request correlation id
	 *
	 * @exception DRDProtocolException
	 */
	protected int readDssHeader () throws DRDAProtocolException
	{
		ensureALayerDataInBuffer (6);

		// read out the DSS length
		dssLength = ((buffer[pos] & 0xff) << 8) +
					((buffer[pos + 1] & 0xff) << 0);
		pos += 2;
		// check for the continuation bit and update length as needed.
		if ((dssLength & DssConstants.CONTINUATION_BIT) == 
				DssConstants.CONTINUATION_BIT) 
		{
			dssLength = DssConstants.MAX_DSS_LENGTH;
			dssIsContinued = true;
		}
		else 
		{
			dssIsContinued = false;
		}

		if (dssLength < 6)
			agent.throwSyntaxrm(CodePoint.SYNERRCD_DSS_LESS_THAN_6,
							   DRDAProtocolException.NO_CODPNT_ARG);

		// If the GDS id is not valid, or
		// if the reply is not an RQSDSS nor
		// a OBJDSS, then throw an exception.

		if ((buffer[pos++] & 0xff) != DssConstants.DSS_ID)
			agent.throwSyntaxrm(CodePoint.SYNERRCD_CBYTE_NOT_D0,
							   DRDAProtocolException.NO_CODPNT_ARG);

		int gdsFormatter = buffer[pos++] & 0xff;
		
		if (((gdsFormatter & 0x0F) != DssConstants.DSSFMT_RQSDSS)
			&&((gdsFormatter & 0x0F) != DssConstants.DSSFMT_OBJDSS)) 
		{
			agent.throwSyntaxrm(CodePoint.SYNERRCD_FBYTE_NOT_SUPPORTED,
							   DRDAProtocolException.NO_CODPNT_ARG);
		}

		// Determine if the current DSS is chained with the
		// next DSS, with the same or different request ID.
		if ((gdsFormatter & DssConstants.DSSCHAIN) == DssConstants.DSSCHAIN) 
		{	// on indicates structure chained to next structure
			if ((gdsFormatter & DssConstants.DSSCHAIN_SAME_ID) 
					== DssConstants.DSSCHAIN_SAME_ID) 
			{
				dssIsChainedWithSameID = true;
				dssIsChainedWithDiffID = false;
			}
			else 
			{
				dssIsChainedWithSameID = false;
				dssIsChainedWithDiffID = true;
			}
			if ((gdsFormatter & DssConstants.DSSCHAIN_ERROR_CONTINUE) 
				== DssConstants.DSSCHAIN_ERROR_CONTINUE)
				terminateChainOnErr = false;
			else
				terminateChainOnErr = true;
		}
		else 
		{
			// chaining bit not b'1', make sure DSSFMT same id not b'1'
			if ((gdsFormatter & DssConstants.DSSCHAIN_SAME_ID) 
					== DssConstants.DSSCHAIN_SAME_ID)	
			{  // Next DSS can not have same correlator
				agent.throwSyntaxrm(CodePoint.SYNERRCD_CHAIN_OFF_SAME_NEXT_CORRELATOR,
								   DRDAProtocolException.NO_CODPNT_ARG);
			}
			// chaining bit not b'1', make sure no error continuation
			if ((gdsFormatter & DssConstants.DSSCHAIN_ERROR_CONTINUE) 
				== DssConstants.DSSCHAIN_ERROR_CONTINUE) 
			{ // must be 'do not continue on error'
				agent.throwSyntaxrm(CodePoint.SYNERRCD_CHAIN_OFF_ERROR_CONTINUE,
								   DRDAProtocolException.NO_CODPNT_ARG);
			}

			dssIsChainedWithSameID = false;
			dssIsChainedWithDiffID = false;
		}

		dssCorrelationID =
			((buffer[pos] & 0xff) << 8) +
			((buffer[pos + 1] & 0xff) << 0);
		pos += 2;
		if (agent != null && agent.trace)
			trace("dssLength = " + dssLength + " correlationID = " + dssCorrelationID);

		//check that correlationID is the same as previous
		if (prevCorrelationID != DssConstants.CORRELATION_ID_UNKNOWN && 
			dssCorrelationID != prevCorrelationID)
		{
			agent.throwSyntaxrm(CodePoint.SYNERRCD_CHAIN_OFF_ERROR_CONTINUE,
							   DRDAProtocolException.NO_CODPNT_ARG);
		}
		
		// set up previous correlation id to check that next DSS is correctly
		// formatted
		if (dssIsChainedWithSameID)
			prevCorrelationID = dssCorrelationID;
		else
			prevCorrelationID = DssConstants.CORRELATION_ID_UNKNOWN;

		dssLength -= 6;

		return dssCorrelationID;
	}
	/**
	 * Read Reply DSS
	 * This is used in testing the protocol.  We shouldn't see a reply
	 * DSS when we are servicing DRDA commands
	 *
	 * @exception DRDProtocolException
	 */
	protected void readReplyDss() throws DRDAProtocolException
	{
		ensureALayerDataInBuffer (6);

		// read out the DSS length
		dssLength = ((buffer[pos++] & 0xff) << 8) +
					((buffer[pos++] & 0xff) << 0);

		// check for the continuation bit and update length as needed.
		if ((dssLength & DssConstants.CONTINUATION_BIT) == 
				DssConstants.CONTINUATION_BIT) 
		{
			dssLength = DssConstants.MAX_DSS_LENGTH;
			dssIsContinued = true;
		}
		else 
		{
			dssIsContinued = false;
		}

		if (dssLength < 6)
			agent.throwSyntaxrm(CodePoint.SYNERRCD_DSS_LESS_THAN_6,
							   DRDAProtocolException.NO_CODPNT_ARG);

		// If the GDS id is not valid, throw exception

		if ((buffer[pos++] & 0xff) != DssConstants.DSS_ID)
			agent.throwSyntaxrm(CodePoint.SYNERRCD_CBYTE_NOT_D0,
							   DRDAProtocolException.NO_CODPNT_ARG);

		int gdsFormatter = buffer[pos++] & 0xff;
		
		// Determine if the current DSS is chained with the
		// next DSS, with the same or different request ID.
		if ((gdsFormatter & DssConstants.DSSCHAIN) == DssConstants.DSSCHAIN) 
		{	// on indicates structure chained to next structure
			if ((gdsFormatter & DssConstants.DSSCHAIN_SAME_ID) 
					== DssConstants.DSSCHAIN_SAME_ID) 
			{
				dssIsChainedWithSameID = true;
				dssIsChainedWithDiffID = false;
			}
			else 
			{
				dssIsChainedWithSameID = false;
				dssIsChainedWithDiffID = true;
			}
		}
		else 
		{
			dssIsChainedWithSameID = false;
			dssIsChainedWithDiffID = false;
		}

		dssCorrelationID =
			((buffer[pos++] & 0xff) << 8) +
			((buffer[pos++] & 0xff) << 0);

		if (agent != null && agent.trace)					
			trace("dssLength = " + dssLength + " correlationID = " + dssCorrelationID);

		dssLength -= 6;

	}

	/**
	 * Read the DDM Length and CodePoint
	 *
     * @param isLayerBStreamingPossible true only when layer B streaming is possible
	 * @return - returns codepoint
	 *
	 * @exception DRDProtocolException
	 */
	protected int readLengthAndCodePoint( boolean isLayerBStreamingPossible ) 
        throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (4, NO_ADJUST_LENGTHS);

		ddmScalarLen = readCodePoint();
		int codePoint = readCodePoint();
		
		if (agent != null && agent.trace)
			trace("length = "+ ddmScalarLen + " codepoint = " + java.lang.Integer.toHexString(codePoint));
		// SYNERRCD 0x0D - Object code point index not supported.
		// the object codepoint index will not be checked here since
		// the parse methods will catch any incorrect/unexpected codepoint values
		// and report them as unsupported objects or parameters.

		// Check if this DDM has extended length field
		if ((ddmScalarLen & DssConstants.CONTINUATION_BIT) == DssConstants.CONTINUATION_BIT) 
		{
			int numberOfExtendedLenBytes = ((int)ddmScalarLen - 
					DssConstants.CONTINUATION_BIT) - 4;
			int adjustSize = 0;
			ensureBLayerDataInBuffer (numberOfExtendedLenBytes, NO_ADJUST_LENGTHS);
			switch (numberOfExtendedLenBytes) {
			case 8:
				 ddmScalarLen =
					((buffer[pos++] & 0xFFL) << 56) +
					((buffer[pos++] & 0xFFL) << 48) +
					((buffer[pos++] & 0xFFL) << 40) +
					((buffer[pos++] & 0xFFL) << 32) +
					((buffer[pos++] & 0xFFL) << 24) +
					((buffer[pos++] & 0xFFL) << 16) +
					((buffer[pos++] & 0xFFL) << 8) +
					((buffer[pos++] & 0xFFL) << 0);
				adjustSize = 12;
				break;
			case 6:
				ddmScalarLen =
					((buffer[pos++] & 0xFFL) << 40) +
					((buffer[pos++] & 0xFFL) << 32) +
					((buffer[pos++] & 0xFFL) << 24) +
					((buffer[pos++] & 0xFFL) << 16) +
					((buffer[pos++] & 0xFFL) << 8) +
					((buffer[pos++] & 0xFFL) << 0);
				adjustSize = 10;
				break;
			case 4:
				ddmScalarLen =
					((buffer[pos++] & 0xFFL) << 24) +
					((buffer[pos++] & 0xFFL) << 16) +
					((buffer[pos++] & 0xFFL) << 8) +
					((buffer[pos++] & 0xFFL) << 0);
				adjustSize = 8;
				break;
                
            case 0:
                
                if( isLayerBStreamingPossible &&
                    ( codePoint == CodePoint.EXTDTA || 
                      codePoint == CodePoint.QRYDTA ) ){
                    
                    startLayerBStreaming();
                    adjustSize = 4;
                    
                }else {
                    agent.throwSyntaxrm(CodePoint.SYNERRCD_INCORRECT_EXTENDED_LEN,
                                        DRDAProtocolException.NO_CODPNT_ARG);
                }
                
                break;
                
			default:
				agent.throwSyntaxrm(CodePoint.SYNERRCD_INCORRECT_EXTENDED_LEN,
							   DRDAProtocolException.NO_CODPNT_ARG);
		}

			// adjust the lengths here.	this is a special case since the
			// extended length bytes do not include their own length.
			for (int i = 0; i <= topDdmCollectionStack; i++) {
				ddmCollectionLenStack[i] -= adjustSize;
			}
			dssLength -= adjustSize;
		}
		else {
			if (ddmScalarLen < 4)
				agent.throwSyntaxrm(CodePoint.SYNERRCD_OBJ_LEN_LESS_THAN_4,
								   DRDAProtocolException.NO_CODPNT_ARG);
			adjustLengths (4);
		}
// GemStone changes BEGIN
		if (agent != null && agent.trace) {
		    trace("actual length = "+ ddmScalarLen + " codepoint = "
		        + java.lang.Integer.toHexString(codePoint));
		}
// GemStone changes END
		return codePoint;
	}

	/**
	 * Read the CodePoint
	 *
	 * @return - returns codepoint
	 */
	protected int readCodePoint()
	{
		return( ((buffer[pos++] & 0xff) << 8) +
		  ((buffer[pos++] & 0xff) << 0));
	}

	/**
	 * Push DDM Length on to collection stack
	 */
	protected void markCollection()
	{
		ddmCollectionLenStack[++topDdmCollectionStack] = ddmScalarLen;
		ddmScalarLen = 0;
	}

	/**
	 * 	Get the next CodePoint from a collection
	 * 	@return	NO_CODEPOINT if collection stack is empty or remaining length is
	 *		0; otherwise,  read length and code point
	 *
	 * @exception DRDProtocolException
	 */
	protected int getCodePoint() throws DRDAProtocolException
	{
		if (topDdmCollectionStack == EMPTY_STACK) 
		{
			return NO_CODEPOINT;
		}
		else 
		{
			// if the collecion is exhausted then return NO_CODEPOINT
			if (ddmCollectionLenStack[topDdmCollectionStack] == 0) 
                {
				// done with this collection so remove it's length from the stack
				ddmCollectionLenStack[topDdmCollectionStack--] = 0;
				return NO_CODEPOINT;
			}
			else {
				return readLengthAndCodePoint( false );
			}
		}
	}
	/**
	 * Get the next CodePoint from a collection and check that it matches the specified
	 * 	CodePoint
	 * @param	codePointCheck	- codePoint to check against
	 * @return	codePoint
	 *
	 * @exception DRDProtocolException
	 */
	protected int getCodePoint(int codePointCheck) throws DRDAProtocolException
	{
		int codePoint = getCodePoint();
		if (codePoint != codePointCheck)
			agent.missingCodePoint(codePoint);
		return codePoint;
	}
	/**
	 * The following routines read different types from the input stream
	 * Data can be in network order or platform order depending on whether the
	 * data is part of the protocol or data being received
	 * The platform is determined by EXCSAT protocol
	 */

	/**
	 * Read byte value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected byte readByte () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (1, ADJUST_LENGTHS);
		return buffer[pos++];
	}

	/**
	 * Read byte value and mask out high order bytes before returning
	 * @return value
	 */
	protected int readUnsignedByte () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (1, ADJUST_LENGTHS);
		return (int ) (buffer[pos++] & 0xff);
	}

	/**
	 * Read network short value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected int readNetworkShort () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (2, ADJUST_LENGTHS);
		return ((buffer[pos++] & 0xff) << 8) +
		  ((buffer[pos++] & 0xff) << 0);
	}

	/**
	 * Read signed network short value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected int readSignedNetworkShort () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (2, ADJUST_LENGTHS);
		return (short)(((buffer[pos++] & 0xff) << 8) +
		  ((buffer[pos++] & 0xff) << 0));
	}
	/**
	 * Read platform short value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected short readShort (int byteOrder) throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (2, ADJUST_LENGTHS);
		short s = SignedBinary.getShort (buffer, pos, byteOrder);

		pos += 2;

		return s;
	}

	/**
	 * Read network int value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected int readNetworkInt () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (4, ADJUST_LENGTHS);
		return ((buffer[pos++] & 0xff) << 24) +
		       ((buffer[pos++] & 0xff) << 16) +
		       ((buffer[pos++] & 0xff) << 8) +
		       ((buffer[pos++] & 0xff) << 0);
	}

	/**
	 * Read platform int value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected int readInt (int byteOrder) throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (4, ADJUST_LENGTHS);
		int i = SignedBinary.getInt (buffer, pos, byteOrder);

		pos += 4;

		return i;
	}

	/**
	 * Read network long value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected long readNetworkLong () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (8, ADJUST_LENGTHS);

		return ((buffer[pos++] & 0xffL) << 56) +
		       ((buffer[pos++] & 0xffL) << 48) +
		       ((buffer[pos++] & 0xffL) << 40) +
		       ((buffer[pos++] & 0xffL) << 32) +
		       ((buffer[pos++] & 0xffL) << 24) +
		       ((buffer[pos++] & 0xffL) << 16) +
		       ((buffer[pos++] & 0xffL) << 8) +
		       ((buffer[pos++] & 0xffL) << 0);
	}

	
	/**
	 * Read network six byte value and put it in a long v
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected long readNetworkSixByteLong() throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (6, ADJUST_LENGTHS);

		return (
				((buffer[pos++] & 0xffL) << 40) +
		       ((buffer[pos++] & 0xffL) << 32) +
		       ((buffer[pos++] & 0xffL) << 24) +
		       ((buffer[pos++] & 0xffL) << 16) +
		       ((buffer[pos++] & 0xffL) << 8) +
		       ((buffer[pos++] & 0xffL) << 0));
	}

	/**
	 * Read platform long value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected long readLong (int byteOrder) throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (8, ADJUST_LENGTHS);
		long l = SignedBinary.getLong (buffer, pos, byteOrder);

		pos += 8;

		return l;
	}

	/**
	 * Read platform float value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected float readFloat(int byteOrder) throws DRDAProtocolException
	{
		return Float.intBitsToFloat(readInt(byteOrder));
	}

	/**
	 * Read platform double value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected double readDouble(int byteOrder) throws DRDAProtocolException
	{
		return Double.longBitsToDouble(readLong(byteOrder));
	}

	/**
	 * Read a BigDecimal value
	 * @param	precision of the BigDecimal
	 * @param	scale of the BigDecimal
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected BigDecimal readBigDecimal(int precision, int scale) throws DRDAProtocolException
    {
      // The byte-length of a packed decimal with precision p is always p/2 + 1
      int length = precision / 2 + 1;

	  ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);

      // check for sign.
      int signum;
      if ((buffer[pos+length-1] & 0x0F) == 0x0D)
        signum = -1;
      else
        signum =  1;

      if (precision <= 9) {
        // can be handled by int without overflow.
        int value = packedNybblesToInt(buffer, pos, 0, length*2-1);

        // convert value to a byte array of magnitude.
        byte[] magnitude = new byte[4];
        magnitude[0] = (byte)(value >>> 24);
        magnitude[1] = (byte)(value >>> 16);
        magnitude[2] = (byte)(value >>> 8);
        magnitude[3] = (byte)(value);

		pos += length;
        return new java.math.BigDecimal (new java.math.BigInteger(signum, magnitude), scale);
      }
      else if (precision <= 18) {
        // can be handled by long without overflow.
        long value = packedNybblesToLong(buffer, pos, 0, length*2-1);

        // convert value to a byte array of magnitude.
        byte[] magnitude = new byte[8];
        magnitude[0] = (byte)(value >>> 56);
        magnitude[1] = (byte)(value >>> 48);
        magnitude[2] = (byte)(value >>> 40);
        magnitude[3] = (byte)(value >>> 32);
        magnitude[4] = (byte)(value >>> 24);
        magnitude[5] = (byte)(value >>> 16);
        magnitude[6] = (byte)(value >>>  8);
        magnitude[7] = (byte)(value);

		pos += length;
        return new java.math.BigDecimal (new java.math.BigInteger(signum, magnitude), scale);
      }
      else if (precision <= 27) {
        // get the value of last 9 digits (5 bytes).
        int lo = packedNybblesToInt(buffer, pos, (length-5)*2, 9);
        // get the value of another 9 digits (5 bytes).
        int me = packedNybblesToInt(buffer, pos, (length-10)*2+1, 9);
        // get the value of the rest digits.
        int hi = packedNybblesToInt(buffer, pos, 0, (length-10)*2+1);

        // compute the int array of magnitude.
        int[] value = computeMagnitude(new int[] {hi, me, lo});

        // convert value to a byte array of magnitude.
        byte[] magnitude = new byte[12];
        magnitude[0]  = (byte)(value[0] >>> 24);
        magnitude[1]  = (byte)(value[0] >>> 16);
        magnitude[2]  = (byte)(value[0] >>> 8);
        magnitude[3]  = (byte)(value[0]);
        magnitude[4]  = (byte)(value[1] >>> 24);
        magnitude[5]  = (byte)(value[1] >>> 16);
        magnitude[6]  = (byte)(value[1] >>> 8);
        magnitude[7]  = (byte)(value[1]);
        magnitude[8]  = (byte)(value[2] >>> 24);
        magnitude[9]  = (byte)(value[2] >>> 16);
        magnitude[10] = (byte)(value[2] >>> 8);
        magnitude[11] = (byte)(value[2]);

		pos += length;
        return new java.math.BigDecimal (new java.math.BigInteger(signum, magnitude), scale);
      }
// GemStone changes BEGIN
      else if (precision <= FdocaConstants.NUMERIC_MAX_PRECISION) {
        // convert value to int[]; this is initialized when the first
        // non-zero integer is seen
        int[] magnitude = null;
        final int end = (length - 5) << 1;
        // converting to integers in groups of 9 digits
        final int remaining = (end % 9);
        final int magLen = (end + 8) / 9 + 1;
        int start, index, v;
        for (start = 0, index = 0; start <= end; index++) {
          if (start == 0 && remaining != 0) {
            // first int is the remaining one at the start
            v = packedNybblesToInt(buffer, pos, 0, remaining);
            start = remaining;
          }
          else {
            // get the value of 9 digits (4.5 bytes).
            v = packedNybblesToInt(buffer, pos, start, 9);
            start += 9;
          }
          if (magnitude == null) {
            if (v != 0) {
              magnitude = new int[magLen - index];
              magnitude[0] = v;
              index = 0;
            }
          }
          else {
            magnitude[index] = v;
          }
        }
        pos += length;
        if (magnitude != null) {
          magnitude = computeMagnitude(magnitude);
          // do the strip of leading zeros only if there is a wrapper
          // constructor of BigInteger available in the first place
          if (magnitude[0] == 0 &&
              ResolverUtils.hasBigIntegerWrapperConstructor()) {
            int i = 0;
            while (magnitude[++i] == 0);
            final int newLen = magnitude.length - i;
            int[] newMag = new int[newLen];
            System.arraycopy(magnitude, i, newMag, 0, newLen);
            magnitude = newMag;
          }
          // try to convert to BigDecimal without converting to byte[]
          // if possible using the internal constructor
          return new java.math.BigDecimal(ResolverUtils.newBigInteger(
              magnitude, signum), scale);
        }
        else {
          return new java.math.BigDecimal(BigInteger.ZERO, scale);
        }
      /* (original code)
      else if (precision <= 31) {
        // get the value of last 9 digits (5 bytes).
        int lo   = packedNybblesToInt(buffer, pos, (length-5)*2, 9);
        // get the value of another 9 digits (5 bytes).
        int meLo = packedNybblesToInt(buffer, pos, (length-10)*2+1, 9);
        // get the value of another 9 digits (5 bytes).
        int meHi = packedNybblesToInt(buffer, pos, (length-14)*2, 9);
        // get the value of the rest digits.
        int hi   = packedNybblesToInt(buffer, pos, 0, (length-14)*2);

        // compute the int array of magnitude.
        int[] value = computeMagnitude(new int[] {hi, meHi, meLo, lo});

        // convert value to a byte array of magnitude.
        byte[] magnitude = new byte[16];
        magnitude[0]  = (byte)(value[0] >>> 24);
        magnitude[1]  = (byte)(value[0] >>> 16);
        magnitude[2]  = (byte)(value[0] >>> 8);
        magnitude[3]  = (byte)(value[0]);
        magnitude[4]  = (byte)(value[1] >>> 24);
        magnitude[5]  = (byte)(value[1] >>> 16);
        magnitude[6]  = (byte)(value[1] >>> 8);
        magnitude[7]  = (byte)(value[1]);
        magnitude[8]  = (byte)(value[2] >>> 24);
        magnitude[9]  = (byte)(value[2] >>> 16);
        magnitude[10] = (byte)(value[2] >>> 8);
        magnitude[11] = (byte)(value[2]);
        magnitude[12] = (byte)(value[3] >>> 24);
        magnitude[13] = (byte)(value[3] >>> 16);
        magnitude[14] = (byte)(value[3] >>> 8);
        magnitude[15] = (byte)(value[3]);

		pos += length;
        return new java.math.BigDecimal (new java.math.BigInteger(signum, magnitude), scale);
      */
// GemStone changes END
      }
      else {
		pos += length;
        // throw an exception here if nibbles is greater than 31
// GemStone changes BEGIN
        throw new java.lang.IllegalArgumentException("Decimal may only be up to "
            + FdocaConstants.NUMERIC_MAX_PRECISION + " digits!");
        /* (original code)
        throw new java.lang.IllegalArgumentException("Decimal may only be up to 31 digits!");
        */
// GemStone changes END
      }
    }

	

	byte[] getExtData (boolean checkNullability) throws DRDAProtocolException
	{
		return  getExtData(ddmScalarLen, checkNullability);
	}

	
	/**
	 * Creates an InputStream which can stream EXTDTA objects.
	 * The InputStream uses this DDMReader to read data from network. The 
	 * DDMReader should not be used before all data in the stream has been read.
	 * @param checkNullability used to check if the stream is null. If it is 
	 * null, this method returns null
	 * @return EXTDTAReaderInputStream object which can be passed to prepared
	 *         statement as a binary stream.
	 * @exception DRDAProtocolException standard DRDA protocol exception
	 */
	EXTDTAReaderInputStream getEXTDTAReaderInputStream
		(final boolean checkNullability)
		throws DRDAProtocolException
	{
		if (checkNullability && isEXTDTANull()) {
			return null;
            
		} else if ( doingLayerBStreaming ){
			return new LayerBStreamedEXTDTAReaderInputStream(this);
        
        } else {
            return new StandardEXTDTAReaderInputStream(this);
            
        }

	}
    
    /**
	 * This method is used by EXTDTAReaderInputStream to read the first chunk 
	 * of data.
     * This lengthless method must be called only when layer B streaming.
     *
	 * @exception DRDAProtocolException standard DRDA protocol exception
	 */
	ByteArrayInputStream readLOBInitStream() 
		throws DRDAProtocolException
	{
        if ( SanityManager.DEBUG ) {
            SanityManager.ASSERT( doingLayerBStreaming );
        }
		
        return readLOBInitStream( 0 );
        
	}
	

	/**
	 * This method is used by EXTDTAReaderInputStream to read the first chunk 
	 * of data.
	 * @param desiredLength the desired length of chunk. This parameter is ignored when layerB Streaming is doing.
	 * @exception DRDAProtocolException standard DRDA protocol exception
	 */
	ByteArrayInputStream readLOBInitStream(final long desiredLength) 
		throws DRDAProtocolException
	{
		return readLOBChunk(false, desiredLength);
	}
	
    
    /**
	 * This method is used by EXTDTAReaderInputStream to read the next chunk 
	 * of data.
     *
     * Calling this method finishes layer B streaming 
     * if continuation of DSS segment was finished.
     * This lengthless method must be called only when layer B streaming.
     *
	 * @exception IOException IOException
	 */
	ByteArrayInputStream readLOBContinuationStream ()
		throws IOException
	{		
        if ( SanityManager.DEBUG ) {
            SanityManager.ASSERT( doingLayerBStreaming );
        }
        return readLOBContinuationStream( 0 );
	}
    

	/**
	 * This method is used by EXTDTAReaderInputStream to read the next chunk 
	 * of data.
     *
     * Furthermore, when Layer B streaming is carried out,
     * calling this method finishes layer B streaming 
     * if continuation of DSS segment was finished.
     *
	 * @param desiredLength the desired length of chunk. This parameter is ignored when layerB Streaming is doing.
	 * @exception IOException IOException
	 */
	ByteArrayInputStream readLOBContinuationStream (final long desiredLength)
		throws IOException
	{		
		try {
			return readLOBChunk(true, desiredLength);
		} catch (DRDAProtocolException e) {
			e.printStackTrace(agent.getServer().logWriter);
			throw new IOException(e.getMessage());
		}
	}

	/**
	 * This method is used by EXTDTAReaderInputStream to read the next chunk 
	 * of data.
     *
     * Furthermore, when Layer B streaming is carried out,
     * calling this method may finish layer B streaming.
     *
	 * @param readHeader set to true if the dss continuation should be read
	 * @param desiredLength the desired length of chunk. This parameter is ignored when layerB Streaming is doing.
	 * @exception DRDAProtocolException standard DRDA protocol exception
	 */
	private ByteArrayInputStream readLOBChunk
		(final boolean readHeader, final long desiredLength)
		throws DRDAProtocolException
	{		
		if (readHeader) {			
			readDSSContinuationHeader();
		}
		
        int copySize = doingLayerBStreaming ? 
            dssLength : 
            (int) Math.min(dssLength, desiredLength);
		
		// read the segment
		ensureALayerDataInBuffer (copySize);
        
        if( ! doingLayerBStreaming ){
            adjustLengths (copySize);
            
        }else{
            dssLength -= copySize;
            
        }
		
		// Create ByteArrayInputStream on top of buffer. 
		// This will not make a copy of the buffer.
		ByteArrayInputStream bais = 
			new ByteArrayInputStream(buffer, pos, copySize);
		pos += copySize;
		
        if( doingLayerBStreaming && 
            ! dssIsContinued )
            finishLayerBStreaming();
        
		return bais;
	}

	byte[] getExtData (long desiredLength, boolean checkNullability) throws DRDAProtocolException
  {

      if ( SanityManager.DEBUG ) {
            SanityManager.ASSERT( ! doingLayerBStreaming );
        }

    boolean readHeader;
    int copySize;
    ByteArrayOutputStream baos;
    boolean isLengthAndNullabilityUnknown = false;

	
	if (desiredLength != -1) {
        // allocate a stream based on a known amount of data
        baos = new ByteArrayOutputStream ((int) desiredLength);
	}
	else {
        // allocate a stream to hold an unknown amount of data
        baos = new ByteArrayOutputStream ();
        //isLengthAndNullabilityUnknown = true;
		// If we aren't given a  length get the whole thing.
		desiredLength = MAX_EXTDTA_SIZE;
	}
	

    // check for a null EXTDTA value, if it is nullable and if streaming
    if (checkNullability)
      if (isEXTDTANull())
        return null;

    // set the amount to read for the first segment
	copySize = (int) Math.min(dssLength,desiredLength); //note: has already been adjusted for headers


    //if (checkNullability)  // don't count the null byte we've already read
	//copySize--;

    do {
      // determine if a continuation header needs to be read after the data
      if (dssIsContinued
          // GemStone addition
          // fix for SNAP-213 -- don't read continuation header if the
          // remaining data in buffer is more than desired length since
          // all the desired data will be read from current buffer itself
          && desiredLength > dssLength)
        readHeader = true;
      else
        readHeader = false;

      // read the segment
      ensureALayerDataInBuffer (copySize);
      adjustLengths (copySize);
      baos.write (buffer, pos, copySize);
      pos += copySize;
	  desiredLength -= copySize;

      // read the continuation header, if necessary
      if (readHeader)
        readDSSContinuationHeader ();

	  copySize = (int) Math.min(dssLength,desiredLength); //note: has already been adjusted for headers

    }
    while (readHeader && desiredLength > 0);

    return baos.toByteArray();
  }


  // reads a DSS continuation header
  // prereq: pos is positioned on the first byte of the two-byte header
  // post:   dssIsContinued is set to true if the continuation bit is on, false otherwise
  //         dssLength is set to DssConstants.MAXDSS_LEN - 2 (don't count the header for the next read)
  // helper method for getEXTDTAData
	private void readDSSContinuationHeader () throws DRDAProtocolException
  {
    ensureALayerDataInBuffer(2);

    dssLength =
      ((buffer[pos++]&0xFF) << 8) +
      ((buffer[pos++]&0xFF) << 0);

    if ((dssLength & 0x8000) == 0x8000) {
      dssLength = DssConstants.MAX_DSS_LENGTH;
      dssIsContinued = true;
    }
    else {
      dssIsContinued = false;
    }
    // it is a syntax error if the dss continuation header length
    // is less than or equal to two
    if (dssLength <= 2) {
		agent.throwSyntaxrm(CodePoint.SYNERRCD_DSS_CONT_LESS_OR_EQUAL_2,
							   DRDAProtocolException.NO_CODPNT_ARG);
    }

    dssLength -= 2;  // avoid consuming the DSS cont header
  }

// checks the null EXTDTA byte
  // returns true if null, false otherwise
  // helper method for getEXTDTAData
  private boolean isEXTDTANull () throws DRDAProtocolException
  {
    // make sure that the null byte is in the buffer
    ensureALayerDataInBuffer (1);
    adjustLengths (1);

    // examine the null byte
    byte nullByte = buffer[pos++];
    if (nullByte == (byte)0x00)
      return false;

    return true;
  }


   /**
    * Convert a range of packed nybbles (up to 9 digits without overflow) to an int.
    * Note that for performance purpose, it does not do array-out-of-bound checking.
	* @param buffer			buffer to read from
	* @param offset			offset in the buffer
	* @param startNybble		start nybble
	* @param numberOfNybbles	number of nybbles
	* @return	an int value
    */
    private int packedNybblesToInt (byte[] buffer,
                                         int offset,
                                         int startNybble,
                                         int numberOfNybbles)
    {
      int value = 0;

      int i = startNybble / 2;
      if ((startNybble % 2) != 0) {
        // process low nybble of the first byte if necessary.
        value += buffer[offset+i] & 0x0F;
        i++;
      }

      int endNybble = startNybble + numberOfNybbles -1;
      for (; i<(endNybble+1)/2; i++) {
        value = value*10 + ((buffer[offset+i] & 0xF0) >>> 4); // high nybble.
        value = value*10 +  (buffer[offset+i] & 0x0F);        // low nybble.
      }

      if ((endNybble % 2) == 0) {
        // process high nybble of the last byte if necessary.
        value = value*10 + ((buffer[offset+i] & 0xF0) >>> 4);
      }

      return value;
    }

    /**
     * Convert a range of packed nybbles (up to 18 digits without overflow) to a long.
     * Note that for performance purpose, it does not do array-out-of-bound checking.
	 * @param buffer		buffer to read from
	 * @param offset		offset in the buffer
	 * @param startNybble		start nybble
	 * @param numberOfNybbles	number of nybbles
	 * @return	an long value
     */
    private long packedNybblesToLong (byte[] buffer,
                                           int offset,
                                           int startNybble,
                                           int numberOfNybbles)
    {
      long value = 0;

      int i = startNybble / 2;
      if ((startNybble % 2) != 0) {
        // process low nybble of the first byte if necessary.
        value += buffer[offset+i] & 0x0F;
        i++;
      }

      int endNybble = startNybble + numberOfNybbles -1;
      for (; i<(endNybble+1)/2; i++) {
        value = value*10 + ((buffer[offset+i] & 0xF0) >>> 4); // high nybble.
        value = value*10 +  (buffer[offset+i] & 0x0F);        // low nybble.
      }

      if ((endNybble % 2) == 0) {
        // process high nybble of the last byte if necessary.
        value = value*10 + ((buffer[offset+i] & 0xF0) >>> 4);
      }

 	  return value;
	}

    /**
     * Compute the int array of magnitude from input value segments.
	 * @param	input value segments
	 * @return	array of int magnitudes
     */
    private int[] computeMagnitude(int[] input)
    {
        int length = input.length;
        int[] mag = new int[length];

        mag[length-1] = input[length-1];
        for (int i=0; i<length-1; i++) {
          int carry = 0;
          int j = tenRadixMagnitude[i].length-1;
          int k = length-1;
          for (; j>=0; j--, k--) {
            long product = (input[length-2-i] & 0xFFFFFFFFL) * (tenRadixMagnitude[i][j] & 0xFFFFFFFFL)
                         + (mag[k] & 0xFFFFFFFFL) // add previous value
                         + (carry & 0xFFFFFFFFL); // add carry
            carry  = (int) (product >>> 32);
            mag[k] = (int) (product & 0xFFFFFFFFL);
          }
          mag[k] = (int) carry;
        }
        return mag;
    }

	/**
	 * Read boolean value
	 * @return	value
	 *
	 * @exception DRDProtocolException
	 */
	protected boolean readBoolean () throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (1, ADJUST_LENGTHS);
		return buffer[pos++] != 0;
	}

	/**
	 * Read encrypted string
	 * @param   decryptM  decryption manager
	 * @param   securityMechanism security mechanism
	 * @param   initVector   initialization vector for cipher
	 * @param   sourcePublicKey  public key (as in Deffie-Hellman algorithm)
	 *                           from source (encryptor)
	 * @return  decrypted string
	 *
	 * @exception DRDProtocolException, SQLException(wrapping any exception in decryption)
	 */
	protected String readEncryptedString (DecryptionManager decryptM, int securityMechanism,
										 byte[] initVector, byte[] sourcePublicKey)
			throws DRDAProtocolException, java.sql.SQLException
	{
		byte[] cipherText = readBytes();
		byte[] plainText = null;
		plainText = decryptM.decryptData(cipherText, securityMechanism, initVector,
											 sourcePublicKey);
		if (plainText == null)
			return null;
		else
			return ccsidManager.convertToUCS2(plainText);
	}

	/**
	 * Read string value
	 * Strings in DRDA protocol are encoded in EBCDIC by default so we
	 * need to convert to UCS2
	 * @param length  - length of string to read
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readString (int length) throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);

		String result = ccsidManager.convertToUCS2 (buffer, pos, length);
		pos += length;
		return result;
	}

	/**
	 * Read string value into a <code>DRDAString</code> object.
	 *
	 * @param dst  destination for the read string
	 * @param size size (in bytes) of string to read
	 * @param unpad if true, remove padding (trailing spaces)
	 *
	 * @exception DRDAProtocolException
	 */
	protected void readString(DRDAString dst, int size, boolean unpad)
		throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer(size, ADJUST_LENGTHS);
		int startPos = pos;
		pos += size;
		if (unpad) {
			while ((size > 0) &&
				   (buffer[startPos + size - 1] == ccsidManager.space)) {
				--size;
			}
		}
		dst.setBytes(buffer, startPos, size);
	}

	/**
	 * Read encoded string value
	 * @param length  - length of string to read
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readString (int length, String encoding) 
		throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);
		String s = null;

		try {
		  s = new String (buffer, pos, length, encoding);
		}
		catch (java.io.UnsupportedEncodingException e) {
			agent.agentError("UnsupportedEncodingException in readString, encoding = " 
					+ encoding);
			e.printStackTrace(agent.getServer().logWriter);
		}
		
		pos += length;
		return s;
	}

	/**
	 * Read string value in DDM data with default encoding
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readStringData()
		throws DRDAProtocolException
	{
		return readString((int)ddmScalarLen, NetworkServerControlImpl.DEFAULT_ENCODING);
	}

	/**
	 * Read specified length of string value in DDM data with default encoding
	 * @param length  - length of string to read
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readStringData(int length)
		throws DRDAProtocolException
	{
		return readString(length, NetworkServerControlImpl.DEFAULT_ENCODING);
	}

	/**
	 * Read length delimited string value in DDM data with default encoding
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readLDStringData(String encoding)
		throws DRDAProtocolException
	{
		int length = readNetworkShort();
		return readString(length, encoding);
	}

	/**
	 * Read string value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readString () throws DRDAProtocolException
	{
		return readString((int)ddmScalarLen);
	}

	/**
	 * Read byte string value
	 * @param length  - length of string to read
	 * @return byte array
	 *
	 * @exception DRDProtocolException
	 */
	protected byte[] readBytes (int length) throws DRDAProtocolException
	{
		byte[] b;

// GemStone changes BEGIN
		if ((length + pos) <= DssConstants.MAX_DSS_LENGTH)
		/* (original code)
		if (length < DssConstants.MAX_DSS_LENGTH)
		*/
// GemStone changes END
		{
			ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);
		    b = new byte[length];
			System.arraycopy(buffer,pos,b,0,length);
			pos +=length;
		}
		else
			b = getExtData(length,false);
		return b;
	}
	
	/**
	 * Read byte string value
	 * @return byte array
	 *
	 * @exception DRDProtocolException
	 */
	protected byte[] readBytes () throws DRDAProtocolException
	{
		return readBytes((int)ddmScalarLen);
	}

	/**
	 * Skip byte string value
	 * @param length  - length of string to skip
	 *
	 * @exception DRDProtocolException
	 */
	protected void skipBytes (int length) throws DRDAProtocolException
	{
		ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);
		pos += length;
	}

	/**
	 * Skip byte string value
	 *
	 * @exception DRDAProtocolException
	 */
	protected void skipBytes () throws DRDAProtocolException
	{
		skipBytes((int)ddmScalarLen);
	}

	/**
	 * Skip remaining DSS
	 *
	 * @exception DRDAProtocolException
	 */
	protected void skipDss() throws DRDAProtocolException
	{
		while (dssIsContinued)
		{
			skipBytes((int)dssLength);
			readDSSContinuationHeader();
		}
		skipBytes((int)dssLength);
		topDdmCollectionStack = EMPTY_STACK;
		ddmScalarLen = 0;
		dssLength = 0;

	}

	protected void clearBuffer() throws DRDAProtocolException
	{
		skipBytes(java.lang.Math.min(dssLength, count - pos));
    	dssIsChainedWithSameID = false;
    	dssIsChainedWithDiffID = false;
	}

	/**
	 * Convert EBCDIC byte array to unicode string
	 *
	 * @param 	buf	- byte array
	 * @return string
	 */
	protected String convertBytes(byte[] buf)
	{
		return ccsidManager.convertToUCS2 (buf, 0, buf.length);
	}

	// Private methods
	/**
	 * Adjust remaining length
	 *
	 * @param length - adjustment length
	 */
	private void adjustLengths(int length)
	{
		ddmScalarLen -= length;
		for (int i = 0; i <= topDdmCollectionStack; i++) {
		  ddmCollectionLenStack[i] -= length;
		}
		dssLength -= length;
	}

	/********************************************************************/
	/*   NetworkServerControl  command protocol reading routines        
	 */
	/********************************************************************/
	/**
	 * Read string value
	 * @param length  - length of string to read
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readCmdString (int length) throws DRDAProtocolException, java.io.UnsupportedEncodingException
	{
		if (length == 0)
			return null;

		ensureBLayerDataInBuffer (length, ADJUST_LENGTHS);
		String result = new String (buffer, pos, length,
										NetworkServerControlImpl.DEFAULT_ENCODING);
		pos += length;
		return result;
	}
	/**
	 * Read string value
	 * @return value
	 *
	 * @exception DRDProtocolException
	 */
	protected String readCmdString () throws DRDAProtocolException, java.io.UnsupportedEncodingException
	{
		int length = readNetworkShort();
		return readCmdString(length);
		
	}

	/**************************************************************************/
	/*   Private methods
	/**************************************************************************/
	/**
	 * Make sure a certain amount of Layer A data is in the buffer.
	 * The data will be in the buffer after this method is called.
	 *
	 * @param desiredDataSize - amount of data we need
	 *
	 * @exception	DRDAProtocolException
	 */
	private void ensureALayerDataInBuffer (int desiredDataSize) 
		throws DRDAProtocolException
	{
		// calulate the the number of bytes in the buffer.
		int avail = count - pos;

		// read more bytes off the network if the data is not in the buffer already.
		if (avail < desiredDataSize) 
		{
		  fill (desiredDataSize - avail);
		}
	}
	/**
	 * Make sure a certain amount of Layer B data is in the buffer.
	 * The data will be in the buffer after this method is called.
	 *
	 * @param desiredDataSize - amount of data we need
	 * @param adjustLen	- whether to adjust the remaining lengths
	 *
	 * @exception DRDProtocolException
	 */
	private void ensureBLayerDataInBuffer (int desiredDataSize, boolean adjustLen) 
		throws DRDAProtocolException
	{
		ensureALayerDataInBuffer (desiredDataSize);
		if (dssIsContinued) 
		{
			if (desiredDataSize > dssLength) 
			{
				int continueDssHeaderCount =
					(((desiredDataSize - dssLength) / DssConstants.MAX_DSS_LENGTH) + 1);
				compressBLayerData (continueDssHeaderCount);
			}
		}
		if (adjustLen)
			adjustLengths(desiredDataSize);
	}

	/**
	 * Compress B Layer data if extended total length is used
	 * by removing the continuation headers
	 *
	 * @param continueDssHeaderCount - amount of data we need
	 *
	 * @exception	throws DRDAProtocolException
	 */
	private void compressBLayerData (int continueDssHeaderCount) 
		throws DRDAProtocolException
	{

		
		// jump to the last continuation header.
		int tempPos = 0;
		for (int i = 0; i < continueDssHeaderCount; i++) 
		{
			// the first may be less than the size of a full DSS
			if (i == 0) 
			{
				// only jump by the number of bytes remaining in the current DSS
				tempPos = pos + dssLength;
			}
			else 
			{
				// all other jumps are for a full continued DSS
				tempPos += DssConstants.MAX_DSS_LENGTH;
			}
		}


		// for each of the DSS headers to remove,
		// read out the continuation header and increment the DSS length by the
		// size of the continuation bytes,	then shift the continuation data as needed.
		int shiftSize = 0;
		int bytesToShift = 0;
		int continueHeaderLength = 0;
		int newdssLength = 0;


		for (int i = 0; i < continueDssHeaderCount; i++) 
		{
			continueHeaderLength = ((buffer[tempPos] & 0xff) << 8) +
				((buffer[tempPos + 1] & 0xff) << 0);

			if (i == 0) 
			{
				// if this is the last one (farthest down stream and first to strip out)

				if ((continueHeaderLength & DssConstants.CONTINUATION_BIT) 
						== DssConstants.CONTINUATION_BIT)
				{
				  // the last DSS header is again continued
				  continueHeaderLength = DssConstants.MAX_DSS_LENGTH;
				  dssIsContinued = true;
				}
				else 
				{
				  // the last DSS header was not contiued so update continue state flag
				  dssIsContinued = false;
				}
				// the very first shift size is 2
				shiftSize = 2;
			}
			else 
			{
				// already removed the last header so make sure the chaining flag is on
				if ((continueHeaderLength & DssConstants.CONTINUATION_BIT) == 
						DssConstants.CONTINUATION_BIT)
				{
				  continueHeaderLength = DssConstants.MAX_DSS_LENGTH;
				}
				else 
				{
				  // this is a syntax error but not really certain which one.
				  // for now pick 0x02 which is DSS header Length does not 
				  // match the number
				    // of bytes of data found.
					agent.throwSyntaxrm(CodePoint.SYNERRCD_DSS_LENGTH_BYTE_NUMBER_MISMATCH,
									   DRDAProtocolException.NO_CODPNT_ARG);
				}
				// increase the shift size by 2
				shiftSize += 2;
			}

			// it is a syntax error if the DSS continuation is less 
			// than or equal to two
			if (continueHeaderLength <= 2) 
			{
				agent.throwSyntaxrm(CodePoint.SYNERRCD_DSS_CONT_LESS_OR_EQUAL_2,
							   DRDAProtocolException.NO_CODPNT_ARG);
			}

			newdssLength += (continueHeaderLength-2);

			// calculate the number of bytes to shift
			if (i != (continueDssHeaderCount - 1))
				bytesToShift = DssConstants.MAX_DSS_LENGTH;
			else
				bytesToShift = dssLength;

			tempPos -= (bytesToShift - 2);
			System.arraycopy(buffer, tempPos - shiftSize, buffer, tempPos,
							 bytesToShift);
		}
		// reposition the start of the data after the final DSS shift.
		pos = tempPos;
		dssLength += newdssLength;
	}

	/**
	 * Methods to manage the data buffer.
	 * Methods orginally from JCC
	 * RESOLVE: need to check if this is the best performing way of doing this
	 */

	/**
	 * This is a helper method which shifts the buffered bytes from
	 * wherever they are in the current buffer to the beginning of
	 * different buffer (note these buffers could be the same).
	 * State information is updated as needed after the shift.
	 * @param destinationBuffer - buffer to shift data to
	 */
	private void shiftBuffer (byte[] destinationBuffer)
	{
		// calculate the size of the data in the current buffer.
		int sz = count - pos;
		if (SanityManager.DEBUG) {
			if ((sz < 0 || pos < 0) )
			{
				SanityManager.THROWASSERT(
					      "Unexpected data size or position. sz=" + sz + 
						  " count=" + count +" pos=" + pos);
			}
		}
		
		// copy this data to the new buffer startsing at position 0.
		System.arraycopy (buffer, pos, destinationBuffer, 0, sz);

		// update the state information for data in the new buffer.
		pos = 0;
		count = sz;

		// replace the old buffer with the new buffer.
		buffer = destinationBuffer;
	}
	/**
	 * This method makes sure there is enough room in the buffer
	 * for a certain number of bytes.  This method will allocate
	 * a new buffer if needed and shift the bytes in the current buffer
	 * to make ensure space is available for a fill.  Right now
	 * this method will shift bytes as needed to make sure there is
	 * as much room as possible in the buffer before trying to
	 * do the read.  The idea is to try to have space to get as much data as possible
	 * if we need to do a read on the socket's stream.
	 *
	 * @param desiredSpace - amount of data we need
	 */
	private void ensureSpaceInBufferForFill (int desiredSpace)
	{
		// calculate the total unused space in the buffer.
		// this includes any space at the end of the buffer and any free
		// space at the beginning resulting from bytes already read.
		int currentAvailableSpace = (buffer.length - count) + pos;

		// check to see if there is enough free space.
		if (currentAvailableSpace < desiredSpace) {

			// there is not enough free space so we need more storage.
			// we are going to double the buffer unless that happens to still be 
			// too small. If more than double the buffer is needed, 
			// use the smallest amount over this as possible.
			int doubleBufferSize = (2 * buffer.length);
			int minumNewBufferSize = (desiredSpace - currentAvailableSpace) + 
				buffer.length;
			int newsz = minumNewBufferSize <= doubleBufferSize ? 
				doubleBufferSize : minumNewBufferSize;

			byte[] newBuffer = new byte[newsz];

			// shift everything from the old buffer to the new buffer
			shiftBuffer (newBuffer);
		}
		else {

			// there is enough free space in the buffer but let's make sure
			// it is all at the end.
			// this is also important because if we are going to do a read, 
			// it would be nice
			// to get as much data as possible and making room at the end 
			// if the buffer helps to ensure this.
			if (pos != 0) {
				shiftBuffer (buffer);
			}
		}
	}

	/**
	 * This method will attempt to read a minimum number of bytes
	 * from the underlying stream.	This method will keep trying to
	 * read bytes until it has obtained at least the minimum number.
	 * @param minimumBytesNeeded - minimum required bytes
	 *
	 * @exception DRDProtocolException
	 */
	private void fill (int minimumBytesNeeded) throws DRDAProtocolException
	{
		// make sure that there is enough space in the buffer to hold
		// the minimum number of bytes needed.
		ensureSpaceInBufferForFill (minimumBytesNeeded);

		// read until the minimum number of bytes needed is now in the buffer.
		// hopefully the read method will return as many bytes as it can.
		int totalBytesRead = 0;
		int actualBytesRead = 0;
		do {
			try {
				actualBytesRead = inputStream.read (
				  buffer, count, buffer.length - count);
			} catch (java.net.SocketTimeoutException ste) {

                // Transport the timeout out through the layers. This
                // exception is caught in DRDAConnThread.run();
                throw new DRDASocketTimeoutException(agent);

			} catch (java.io.IOException ioe) {
                agent.markCommunicationsFailure("DDMReader.fill()",
                                                "InputStream.read()", ioe.getMessage(), "*");
			} finally {
				if ((dssTrace != null) && dssTrace.isComBufferTraceOn())
				  dssTrace.writeComBufferData (buffer,
				                               count,
				                               actualBytesRead,
				                               DssTrace.TYPE_TRACE_RECEIVE,
				                               "Request",
				                               "fill",
				                               5);
			}
			if (actualBytesRead != -1)
			{
				count += actualBytesRead;
				totalBytesRead += actualBytesRead;
			}

		} while ((totalBytesRead < minimumBytesNeeded) && (actualBytesRead != -1));

		if (actualBytesRead == -1) 
		{
			if (totalBytesRead < minimumBytesNeeded) 
			{
				agent.markCommunicationsFailure ("DDMReader.fill()",
				  "InputStream.read()", "insufficient data", "*");
			}
		}
// GemStone changes BEGIN
		if (ConnectionStats.isSamplingEnabled()) {
		  this.bytesRead.addAndGet(totalBytesRead);
		}
// GemStone changes END
        totalByteCount += totalBytesRead;
	}

	/**
	 * Print a internal trace message
	 */
	private void trace(String msg)
	{
		if (agent != null)
			agent.trace(msg);
	}

	protected String toDebugString(String indent)
	{
		String s = indent + "***** DDMReader toDebugString ******\n";
		int buflen = 0;
		if (buffer != null)
			buflen = buffer.length;
	   s += indent + "Reader buffer length = " + buffer.length + "\n";
	   return s;
	}

	/**
	 * Return chaining bit for current DSS.
	 */
	protected byte getCurrChainState() {

		if (!dssIsChainedWithSameID && !dssIsChainedWithDiffID)
			return DssConstants.DSS_NOCHAIN;

		if (dssIsChainedWithSameID)
			return DssConstants.DSSCHAIN_SAME_ID;

		return DssConstants.DSSCHAIN;

	}
    
    
    private void startLayerBStreaming() {
        doingLayerBStreaming = true;
    }
    
    
    private void finishLayerBStreaming() {
        doingLayerBStreaming = false;
    }
    
    
    boolean doingLayerBStreaming() {
        return doingLayerBStreaming;
    }
    
    
}
