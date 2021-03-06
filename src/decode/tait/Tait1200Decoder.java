/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package decode.tait;

import instrument.Instrumentable;
import instrument.tap.Tap;
import instrument.tap.stream.BinaryTap;
import instrument.tap.stream.FloatTap;

import java.util.ArrayList;
import java.util.List;

import sample.Broadcaster;
import sample.real.RealSampleListener;
import source.Source.SampleType;
import alias.AliasList;
import audio.IAudioOutput;
import bits.MessageFramer;
import bits.SyncPattern;
import decode.Decoder;
import decode.DecoderType;
import dsp.filter.Filters;
import dsp.filter.FloatFIRFilter;
import dsp.filter.FloatHalfBandFilter;
import dsp.fsk.FSK2Decoder;
import dsp.fsk.FSK2Decoder.Output;

/**
 * TAIT 1200 - 1200 baud 2FSK decoder
 */
public class Tait1200Decoder extends Decoder implements Instrumentable
{
	/* Decimated sample rate ( 48,000 / 2 = 24,000 ) feeding the decoder */
	private static final int DECIMATED_SAMPLE_RATE = 24000;
	
	/* Baud or Symbol Rate */
	private static final int SYMBOL_RATE = 1200;

	/* Message length ... */
    private static final int MESSAGE_LENGTH = 440;
    
    /* Instrumentation Taps */
    private ArrayList<Tap> mAvailableTaps;
	private static final String INSTRUMENT_INPUT = 
			"Tap Point: Float Input";
	private static final String INSTRUMENT_BANDPASS_FILTER_TO_FSK2_DEMOD = 
			"Tap Point: Bandpass Filter > < FSK2 Decoder";
	private static final String INSTRUMENT_FSK2_DECODER_TO_MESSAGE_FRAMER = 
			"Tap Point: FSK2 Decoder > < Message Framer";
	
    private FSK2Decoder mFSKDecoder;
    
    private FloatHalfBandFilter mDecimationFilter;
    private FloatFIRFilter mBandPassFilter;
    private MessageFramer mMessageFramerGPS;
    private MessageFramer mMessageFramerSELCALL;
    private Broadcaster<Boolean> mFSKBroadcaster = new Broadcaster<Boolean>();
    private Tait1200GPSMessageProcessor mMessageAProcessor;
    private Tait1200ANIMessageProcessor mMessageBProcessor;
    
    public Tait1200Decoder( AliasList aliasList )
	{
    	super( SampleType.REAL );
    	
        mDecimationFilter = new FloatHalfBandFilter( 
        		Filters.FIR_HALF_BAND_31T_ONE_EIGHTH_FCO, 1.0002 );
        addRealSampleListener( mDecimationFilter );

        mBandPassFilter = new FloatFIRFilter( 
        		Filters.FIRBP_1200FSK_24000FS.getCoefficients(), 1.02 );
        mDecimationFilter.setListener( mBandPassFilter );

        mFSKDecoder = new FSK2Decoder( DECIMATED_SAMPLE_RATE, 
        					SYMBOL_RATE, Output.INVERTED );
        mBandPassFilter.setListener( mFSKDecoder );


        mFSKDecoder.setListener( mFSKBroadcaster );

        mMessageFramerGPS = new MessageFramer( 
        		SyncPattern.TAIT_CCDI_GPS_MESSAGE.getPattern(), MESSAGE_LENGTH );
        mMessageFramerSELCALL = new MessageFramer( 
        		SyncPattern.TAIT_SELCAL_MESSAGE.getPattern(), MESSAGE_LENGTH );
        
        mFSKBroadcaster.addListener( mMessageFramerGPS );
        mFSKBroadcaster.addListener( mMessageFramerSELCALL );

        mMessageAProcessor = new Tait1200GPSMessageProcessor( aliasList );
        mMessageBProcessor = new Tait1200ANIMessageProcessor( aliasList );
        
        mMessageFramerGPS.addMessageListener( mMessageAProcessor );
        mMessageFramerSELCALL.addMessageListener( mMessageBProcessor );
        
        mMessageAProcessor.addMessageListener( this );
        mMessageBProcessor.addMessageListener( this );
	}
    
    public void dispose()
    {
    	super.dispose();
    	
    	mDecimationFilter.dispose();
    	mBandPassFilter.dispose();
    	mFSKDecoder.dispose();
    	mMessageFramerGPS.dispose();
    	mMessageFramerSELCALL.dispose();
    	mMessageAProcessor.dispose();
    	mMessageBProcessor.dispose();
    }

	@Override
    public DecoderType getType()
    {
	    return DecoderType.TAIT_1200;
    }

	/**
	 * Returns a float listener interface for connecting this decoder to a 
	 * float stream provider
	 */
	public RealSampleListener getRealReceiver()
	{
		return (RealSampleListener)mDecimationFilter;
	}
	
	@Override
    public List<Tap> getTaps()
    {
		if( mAvailableTaps == null )
		{
			mAvailableTaps = new ArrayList<Tap>();
			
			mAvailableTaps.add( new FloatTap( INSTRUMENT_INPUT, 0, 1.0f ) );
			mAvailableTaps.add( new FloatTap( 
					INSTRUMENT_BANDPASS_FILTER_TO_FSK2_DEMOD, 0, 0.5f ) );
			mAvailableTaps.addAll( mFSKDecoder.getTaps() );
			mAvailableTaps.add( new BinaryTap( 
					INSTRUMENT_FSK2_DECODER_TO_MESSAGE_FRAMER, 0, 0.025f ) );
		}
		
	    return mAvailableTaps;
    }

	@Override
    public void addTap( Tap tap )
    {
		mFSKDecoder.addTap( tap );

		switch( tap.getName() )
		{
			case INSTRUMENT_INPUT:
				FloatTap inputTap = (FloatTap)tap;
				addRealSampleListener( inputTap );
				break;
			case INSTRUMENT_BANDPASS_FILTER_TO_FSK2_DEMOD:
				FloatTap bpTap = (FloatTap)tap;
				mBandPassFilter.setListener( bpTap );
				bpTap.setListener( mFSKDecoder );
				break;
			case INSTRUMENT_FSK2_DECODER_TO_MESSAGE_FRAMER:
				BinaryTap decoderTap = (BinaryTap)tap;
				mFSKBroadcaster.addListener( decoderTap );
		        break;
		}
    }

	@Override
    public void removeTap( Tap tap )
    {
		mFSKDecoder.removeTap( tap );

		switch( tap.getName() )
		{
			case INSTRUMENT_INPUT:
				FloatTap inputTap = (FloatTap)tap;
				removeRealListener( inputTap );
				break;
			case INSTRUMENT_BANDPASS_FILTER_TO_FSK2_DEMOD:
				mBandPassFilter.setListener( mFSKDecoder );
				break;
			case INSTRUMENT_FSK2_DECODER_TO_MESSAGE_FRAMER:
				mFSKBroadcaster.removeListener( (BinaryTap)tap );
		        break;
		}
    }

	@Override
    public void addUnfilteredRealSampleListener( RealSampleListener listener )
    {
		//Not implemented
    }

	@Override
	public IAudioOutput getAudioOutput()
	{
		// Not implemented
		return null;
	}
}
