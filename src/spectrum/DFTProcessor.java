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
package spectrum;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import log.Log;

import org.jtransforms.fft.FloatFFT_1D;

import sample.Listener;
import source.Source;
import source.Source.SampleType;
import source.tuner.FrequencyChangeListener;
import spectrum.converter.DFTResultsConverter;
import dsp.filter.Window;
import dsp.filter.Window.WindowType;

/**
 * Processes both complex samples or float samples and dispatches a float array
 * of DFT results, using configurable fft size and output dispatch timelines.  
 */
public class DFTProcessor implements Listener<Float[]>,
									 FrequencyChangeListener
{
	private CopyOnWriteArrayList<DFTResultsConverter> mListeners =
			new CopyOnWriteArrayList<DFTResultsConverter>();

	private ArrayBlockingQueue<Float[]> mQueue = 
							new ArrayBlockingQueue<Float[]>( 200 );
							
	private ScheduledExecutorService mScheduler = 
							Executors.newScheduledThreadPool(1);	
	
	private FFTWidth mFFTWidth = FFTWidth.FFT04096;
	private FFTWidth mNewFFTWidth = FFTWidth.FFT04096;
	
	private double[] mWindow;
	private WindowType mWindowType = Window.WindowType.COSINE;

	private FloatFFT_1D mFFT = new FloatFFT_1D( mFFTWidth.getWidth() );
	
	private int mFrameRate;
	private int mSampleRate;
	private int mFFTFloatsPerFrame;
	private float mNewFloatsPerFrame;
	private float mNewFloatsPerFrameToConsume;
	private float mNewFloatResidual;
	private float[] mPreviousFrame = new float[ 8192 ];
	
	private Float[] mCurrentBuffer;
	private int mCurrentBufferPointer = 0;
	
	private SampleType mSampleType;

	private AtomicBoolean mRunning = new AtomicBoolean();
	
	public DFTProcessor( SampleType sampleType )
	{
		setSampleType( sampleType );
		setFrameRate( 20 );
	}
	
	public void dispose()
	{
		stop();
		
		mListeners.clear();
		mQueue.clear();
		mWindow = null;
		mCurrentBuffer = null;
	}
	
	public WindowType getWindowType()
	{
		return mWindowType;
	}
	
	public void setWindowType( WindowType windowType )
	{
		mWindowType = windowType;
		
		if( mSampleType == SampleType.COMPLEX )
		{
			mWindow = Window.getWindow( mWindowType, 
										mFFTWidth.getWidth() * 2 );		
		}
		else
		{
			mWindow = Window.getWindow( mWindowType,
										mFFTWidth.getWidth() );		
		}
	}
	
	/**
	 * Sets the processor mode to Float or Complex, depending on the sample
	 * types that will be delivered for processing
	 */
	public void setSampleType( SampleType type )
	{
		mSampleType = type;
		setWindowType( mWindowType );
	}
	
	public Source.SampleType getSampleType()
	{
		return mSampleType;
	}

	/**
	 * Queues an FFT size change request.  The scheduled executor will apply 
	 * the change when it runs.
	 */
	public void setFFTSize( FFTWidth width )
	{
		mNewFFTWidth = width;
	}
	
	public FFTWidth getFFTWidth()
	{
		return mFFTWidth;
	}
	
	public int getFrameRate()
	{
		return mFrameRate;
	}
	
	public void setFrameRate( int framesPerSecond )
	{
		//TODO: make sure frame rate & sample rate sample requirement doesn't
		//expect overlap greater than the previous frame length
		
		if( framesPerSecond < 1 || framesPerSecond > 1000 )
		{
			throw new IllegalArgumentException( "DFTProcessor cannot run "
			+ "more than 1000 times per second -- requested setting:" 
					+ framesPerSecond );
		}

		mFrameRate = framesPerSecond;

		calculateConsumptionRate();

		restart();
	}
	
	public void start()
	{
		/**
         * Reset the scheduler
         */
		mScheduler = Executors.newScheduledThreadPool(1);

		/**
		 * Schedule the DFT to run calculations at a fixed rate
		 */
		int initialDelay = 0;
		int period = (int)( 1000 / mFrameRate );
		TimeUnit unit = TimeUnit.MILLISECONDS;

		mScheduler.scheduleAtFixedRate( new DFTCalculationTask(), 
											initialDelay, period, unit );
	}
	
	public void stop()
	{
		/**
		 * Shutdown the scheduler and clear out any remaining tasks
		 */
        try
        {
    		mScheduler.shutdown();

            mScheduler.awaitTermination( 100, TimeUnit.MILLISECONDS );
        }
        catch ( InterruptedException e )
        {
	        Log.error( "DFTProcessor - exception while awaiting shutdown of "
	        		+ "calculation scheduler for reset" );
        }
	}
	
	public void restart()
	{
		stop();
		start();
	}
	
	public int getCalculationsPerSecond()
	{
		return mFrameRate;
	}
	
	/**
	 * Places the sample into a transfer queue for future processing. 
	 */
	@Override
    public void receive( Float[] samples )
    {
		if( !mQueue.offer( samples ) )
		{
			Log.error( "DFTProcessor - [" + mSampleType.toString()
						+ "]queue is full, purging queue, "
						+ "samples[" + samples + "]" );

			mQueue.clear();
			mQueue.offer( samples );
		}
    }
	
	private void getNextBuffer()
	{
		mCurrentBuffer = null;

		try
        {
            mCurrentBuffer = mQueue.take();
        }
        catch ( InterruptedException e )
        {
        	mCurrentBuffer = null;
        }

		mCurrentBufferPointer = 0;
	}

	private float[] getSamples()
	{
		int remaining = (int)mFFTFloatsPerFrame;

		float[] currentFrame = new float[ remaining ];

		int currentFramePointer = 0;

		float integralFloatsToConsume = mNewFloatsPerFrame + mNewFloatResidual;
		
		int newFloatsToConsumeThisFrame = (int)integralFloatsToConsume;
		
		mNewFloatResidual = integralFloatsToConsume - newFloatsToConsumeThisFrame;
		
		/* If the number of required floats for the fft is greater than the
		 * consumption rate per frame, we have to reach into the previous
		 * frame to makeup the difference. */
		if( newFloatsToConsumeThisFrame < remaining )
		{
			int previousFloatsRequired = remaining - newFloatsToConsumeThisFrame;
			
			System.arraycopy( mPreviousFrame, 
							  mPreviousFrame.length - previousFloatsRequired, 
							  currentFrame, 
							  currentFramePointer, 
							  previousFloatsRequired );
			
			remaining -= previousFloatsRequired;
			currentFramePointer += previousFloatsRequired;
		}

		/* Fill the rest of the buffer with new samples */
		while( mRunning.get() && remaining > 0 )
		{
			if( mCurrentBuffer == null || 
					mCurrentBufferPointer >= mCurrentBuffer.length )
			{
				getNextBuffer();
			}

			int samplesAvailable = mCurrentBuffer.length - mCurrentBufferPointer;

			while( remaining > 0 && samplesAvailable > 0 )
			{
				currentFrame[ currentFramePointer++ ] = 
						(float)mCurrentBuffer[ mCurrentBufferPointer++ ];
				
				samplesAvailable--;
				remaining--;
				newFloatsToConsumeThisFrame--;
			}
		}
		
		/* If the incoming float rate is greater than the fft consumption rate,
		 * then we have to purge some floats, otherwise, store the previous
		 * frame, because we have overlapping frames */
		if( newFloatsToConsumeThisFrame > 0 )
		{
			purge( newFloatsToConsumeThisFrame );
		}
		else
		{
			mPreviousFrame = Arrays.copyOf( currentFrame, currentFrame.length );
		}

		return currentFrame;
	}
	
	private void calculate()
	{
		float[] samples = getSamples();
		
		Window.apply( mWindow, samples );

		if( mSampleType == SampleType.FLOAT )
		{
			mFFT.realForward( samples );
		}
		else
		{
			mFFT.complexForward( samples );
		}
		
		dispatch( samples );
	}

	
	private void purge( int samplesToPurge )
	{
		if( samplesToPurge <= 0 )
		{
			throw new IllegalArgumentException( "DFTProcessor - cannot purge "
					+ "negative sample amount" );
		}

		while( mRunning.get() && samplesToPurge > 0 )
		{
			if( mCurrentBuffer == null || 
					mCurrentBufferPointer >= mCurrentBuffer.length )
			{
				getNextBuffer();
			}
			
			int samplesAvailable = mCurrentBuffer.length - mCurrentBufferPointer;
			
			if( samplesAvailable >= samplesToPurge )
			{
				mCurrentBufferPointer += samplesToPurge;
				samplesToPurge = 0;
			}
			else
			{
				samplesToPurge -= samplesAvailable;
				mCurrentBufferPointer = mCurrentBuffer.length;
			}
		}
	}
	
	/**
	 * Takes a calculated DFT results set, reformats the data, and sends it 
	 * out to all registered listeners.
	 */
	private void dispatch( float[] results )
	{
		Iterator<DFTResultsConverter> it = mListeners.iterator();

		while( it.hasNext() )
		{
			it.next().receive( results );
		}
	}

    public void addConverter( DFTResultsConverter listener )
    {
		mListeners.add( listener );
    }

    public void removeConverter( DFTResultsConverter listener )
    {
		mListeners.remove( listener );
    }
	
	private class DFTCalculationTask implements Runnable
	{
		@Override
        public void run()
        {
			/* Only run if we're not currently running */
			if( mRunning.compareAndSet( false, true ) )
			{
				checkFFTSize();

				calculate();
				
				mRunning.set( false );
			}
        }
	}
	
	/**
	 * Checks for a queued FFT width change request and applies it.  This 
	 * method will only be accessed by the scheduled executor that gains 
	 * access to run a calculate method, thus providing thread safety.
	 */
	private void checkFFTSize()
	{
		if( mNewFFTWidth.getWidth() != mFFTWidth.getWidth() )
		{
			mFFTWidth = mNewFFTWidth;
			
			calculateConsumptionRate();

			setWindowType( mWindowType );

			if( mSampleType == SampleType.COMPLEX )
			{
				mPreviousFrame = new float[ mFFTWidth.getWidth() * 2 ];
			}
			else
			{
				mPreviousFrame = new float[ mFFTWidth.getWidth() ];
			}

			mFFT = new FloatFFT_1D( mFFTWidth.getWidth() );
		}
	}
	
	public void clearBuffer()
	{
		mQueue.clear();
	}
	
	@Override
    public void frequencyChanged( long frequency, int bandwidth )
    {
		mSampleRate = bandwidth;
		
		calculateConsumptionRate();
    }
	
	/**
	 * 
	 */
	private void calculateConsumptionRate()
	{
		mNewFloatResidual = 0.0f;
		
		mNewFloatsPerFrame = ( (float)mSampleRate / (float)mFrameRate ) *
				( mSampleType == SampleType.COMPLEX ? 2.0f : 1.0f );
		
		mFFTFloatsPerFrame = ( mSampleType == SampleType.COMPLEX ? 
					mFFTWidth.getWidth() * 2 : 
					mFFTWidth.getWidth() );

		if( mFFTFloatsPerFrame < mNewFloatsPerFrame )
		{
			mNewFloatsPerFrameToConsume = mFFTFloatsPerFrame;
		}
		else
		{
			mNewFloatsPerFrameToConsume = mNewFloatsPerFrame;
		}
	}
}