package dsp.fsk;

import java.util.ArrayList;
import java.util.BitSet;

import message.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sample.Listener;
import bits.BitSetBuffer;
import bits.BitSetFullException;
import bits.SyncPatternMatcher;
import decode.p25.P25Interleave;
import decode.p25.TrellisHalfRate;
import decode.p25.message.P25Message;
import decode.p25.message.PDUMessage;
import decode.p25.message.TSBKMessage;
import decode.p25.message.TSBKMessageFactory;
import decode.p25.reference.DataUnitID;

public class P25MessageFramer implements Listener<C4FMSymbol>
{
	private final static Logger mLog = 
							LoggerFactory.getLogger( P25MessageFramer.class );
	
	private static final int[] STATUS_BITS = 
		new int[] { 22,92,162,232,302,372,442,512,582,652,722,792,862,932 };

	private ArrayList<P25MessageAssembler> mAssemblers =
			new ArrayList<P25MessageAssembler>();

	public static final int TSBK_BEGIN = 64;
	public static final int TSBK_END = 260;
	public static final int TSBK_DECODED_END = 162;
	
	private Listener<Message> mListener;
	private SyncPatternMatcher mMatcher;
	private boolean mInverted = false;
	private TrellisHalfRate mHalfRate = new TrellisHalfRate();
	
	/**
	 * Constructs a C4FM message framer to receive a stream of C4FM symbols and
	 * detect the sync pattern then capture the following stream of symbols up
	 * to the message length, and then broadcast that bit buffer to the registered
	 * listener.
	 * 
	 * @param sync - sync pattern (maximum of 63 bits)
	 * @param messageLength - in bits
	 * @param inverted - optional flag to indicate the symbol stream should be
	 * uninverted prior to processing 
	 */
	public P25MessageFramer( long sync, int messageLength, boolean inverted )
	{
		mMatcher = new SyncPatternMatcher( sync );
		mInverted = inverted;

		/**
		 * We use two message assemblers to catch any sync detections, so that
		 * if we have a false trigger then we still have a chance to catch the 
		 * valid message. If we have two false triggers before the arrival of 
		 * the actual message sync, then the third or subsequent real or false
		 * sync pattern will produce a debug message indicating no assemblers
		 * are available..
		 */
		mAssemblers.add( new P25MessageAssembler() );
		mAssemblers.add( new P25MessageAssembler() );
	}
	
	private void dispatch( Message message )
	{
		if( mListener != null )
		{
			mListener.receive( message );
		}
	}
	
	@Override
    public void receive( C4FMSymbol symbol )
    {
		C4FMSymbol correctedSymbol = symbol;
		
    	mMatcher.receive( correctedSymbol.getBit1() );
    	mMatcher.receive( correctedSymbol.getBit2() );

    	for( P25MessageAssembler assembler: mAssemblers )
    	{
    		if( assembler.isActive() )
    		{
        		assembler.receive( correctedSymbol );
        		
        		if( assembler.complete() )
        		{
        			assembler.reset();
        		}
    		}
    	}
    	
        /* Check for sync match and activate a message assembler, if we can
         * find an inactive assembler.  Otherwise, ignore and log the issue */
    	if( mMatcher.matches() )
    	{
    		boolean found = false;
    		
        	for( P25MessageAssembler assembler: mAssemblers )
        	{
        		if( !assembler.isActive() )
        		{
        			assembler.setActive( true );
        			found = true;
        			break;
        		}
        	}
        	
        	if( !found )
        	{
            	mLog.debug( "no inactive C4FM message assemblers available" );
        	}
    	}
    }

    public void setListener( Listener<Message> listener )
    {
		mListener = listener;
    }

    public void removeListener( Listener<Message> listener )
    {
		mListener = null;
    }
	
    private class P25MessageAssembler
    {
    	private int mStatusIndicatorPointer = 0;
    	private BitSetBuffer mMessage;
        private int mMessageLength;
        private boolean mComplete = false;
        private boolean mActive = false;
        private DataUnitID mDUID = DataUnitID.NID;
        
        public P25MessageAssembler()
        {
        	mMessageLength = mDUID.getMessageLength();
            mMessage = new BitSetBuffer( mMessageLength );
        	reset();
        }
        
        public void receive( C4FMSymbol symbol )
        {
        	if( mActive )
        	{
        		/* Throw away status bits that are injected every 70 bits */
        		if( mMessage.pointer() == STATUS_BITS[ mStatusIndicatorPointer ] )
        		{
        			mStatusIndicatorPointer++;
        		}
        		else
        		{
                    try
                    {
                        mMessage.add( symbol.getBit1() );
                        mMessage.add( symbol.getBit2() );
                    }
                    catch( BitSetFullException e )
                    {
                         mComplete = true;
                    }
         
                    /* Check the message for complete */
                    if( mMessage.isFull() )
                    {
                    	checkComplete();
                    }
        		}
        	}
        }

        public void reset()
        {
        	mDUID = DataUnitID.NID;
        	mMessage.setSize( mDUID.getMessageLength() );
        	mMessage.clear();
        	mStatusIndicatorPointer = 0;
            mComplete = false;
            mActive = false;
        }
        
        public void setActive( boolean active )
        {
        	mActive = active;
        }

        private void setDUID( DataUnitID id )
        {
        	mDUID = id;
        	mMessageLength = id.getMessageLength();
        	mMessage.setSize( mMessageLength );
        }

        private void checkComplete()
        {
        	switch( mDUID )
        	{
				case NID:
					int value = mMessage.getInt( P25Message.DUID );
					
					DataUnitID duid = DataUnitID.fromValue( value );
					
					if( duid != DataUnitID.UNKN )
					{
						setDUID( duid );
					}
					else
					{
						mComplete = true;
					}
					break;
				case HDU:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case LDU1:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case LDU2:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case PDU1:
					int blocks = mMessage.getInt( 
								PDUMessage.PDU_HEADER_BLOCKS_TO_FOLLOW );
					int padBlocks = mMessage.getInt( PDUMessage.PDU_HEADER_PAD_BLOCKS );
					
					int blockCount = blocks + padBlocks;

					if( blockCount == 24 || blockCount == 32 )
					{
						setDUID( DataUnitID.PDU2 );
					}
					else if( blockCount == 36 || blockCount == 48 )
					{
						setDUID( DataUnitID.PDU3 );
					}
					else
					{
						dispatch( new PDUMessage( mMessage.copy(), mDUID ) );
						mComplete = true;
					}
					break;
				case PDU2:
				case PDU3:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case TDU:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case TDULC:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				case TSBK1:
					/* Remove interleaving */
					P25Interleave.deinterleave( mMessage, TSBK_BEGIN, TSBK_END );
	
					/* Remove trellis encoding */
					mHalfRate.decode( mMessage, TSBK_BEGIN, TSBK_END );

					/* Construct the message */
					int tsbkSystem1 = mMessage.getInt( P25Message.NAC );

					BitSetBuffer tsbkBuffer1 = new BitSetBuffer( mMessage.get( 
							TSBK_BEGIN, TSBK_DECODED_END ), 96 );
					
					TSBKMessage tsbkMessage1 = 
							TSBKMessageFactory.getMessage( tsbkSystem1, tsbkBuffer1 ); 

					if( tsbkMessage1.isLastBlock() )
					{
						mComplete = true;
					}
					else
					{
						setDUID( DataUnitID.TSBK2 );
						mMessage.setPointer( TSBK_BEGIN );
					}
					
					dispatch( tsbkMessage1 );
					break;
				case TSBK2:
					/* Remove interleaving */
					P25Interleave.deinterleave( mMessage, TSBK_BEGIN, TSBK_END );

					/* Remove trellis encoding */
					mHalfRate.decode( mMessage, TSBK_BEGIN, TSBK_END );

					/* Construct the message */
					int tsbkSystem2 = mMessage.getInt( P25Message.NAC );

					BitSetBuffer tsbkBuffer2 = new BitSetBuffer( mMessage.get( 
							TSBK_BEGIN, TSBK_DECODED_END ), 98 );
					
					TSBKMessage tsbkMessage2 = 
							TSBKMessageFactory.getMessage( tsbkSystem2, tsbkBuffer2 ); 

					if( tsbkMessage2.isLastBlock() )
					{
						mComplete = true;
					}
					else
					{
						setDUID( DataUnitID.TSBK3 );
						mMessage.setPointer( TSBK_BEGIN );
					}
					
					dispatch( tsbkMessage2 );
					
					break;
				case TSBK3:
					/* Remove interleaving */
					P25Interleave.deinterleave( mMessage, TSBK_BEGIN, TSBK_END );
	
					/* Remove trellis encoding */
					mHalfRate.decode( mMessage, TSBK_BEGIN, TSBK_END );

					/* Construct the message */
					int tsbkSystem3 = mMessage.getInt( P25Message.NAC );

					BitSetBuffer tsbkBuffer3 = new BitSetBuffer( mMessage.get( 
							TSBK_BEGIN, TSBK_DECODED_END ), 96 );
					
					TSBKMessage tsbkMessage3 = 
							TSBKMessageFactory.getMessage( tsbkSystem3, tsbkBuffer3 ); 

					mComplete = true;

					dispatch( tsbkMessage3 );
					
					break;
				case UNKN:
					mComplete = true;
					dispatch( new P25Message( mMessage.copy(), mDUID ) );
					break;
				default:
					mComplete = true;
					break;
        	}
        }
        
        public void dispose()
        {
        	mMessage = null;
        	mHalfRate.dispose();
        }

        /**
         * Flag to indicate when this assembler has received all of the bits it
         * is looking for (ie message length), and should then be removed from
         * receiving any more bits
         */
        public boolean complete()
        {
            return mComplete;
        }
        
        public boolean isActive()
        {
        	return mActive;
        }
    }
}
