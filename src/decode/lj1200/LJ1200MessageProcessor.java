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
package decode.lj1200;

import message.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sample.Broadcaster;
import sample.Listener;
import alias.AliasList;
import bits.BinaryMessage;

public class LJ1200MessageProcessor implements Listener<BinaryMessage>
{
	private final static Logger mLog = 
			LoggerFactory.getLogger( LJ1200MessageProcessor.class );

	public static int[] SYNC = { 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15 };

	public static int SYNC_TOWER = 0x550F;
	public static int SYNC_TRANSPONDER = 0x2AD5;

	private Broadcaster<Message> mBroadcaster = new Broadcaster<Message>();
	
	private AliasList mAliasList;
	
	public LJ1200MessageProcessor( AliasList list )
	{
		mAliasList = list;
	}
	
	public void dispose()
	{
		mBroadcaster.dispose();
	}
	
	@Override
    public void receive( BinaryMessage buffer )
    {
		int sync = buffer.getInt( SYNC );

		if( sync == SYNC_TOWER )
		{
			LJ1200Message towerMessage = new LJ1200Message( buffer, mAliasList );
			mBroadcaster.receive( towerMessage );
		}
		else if( sync == SYNC_TRANSPONDER )
		{
			LJ1200TransponderMessage transponderMessage = 
					new LJ1200TransponderMessage( buffer, mAliasList );
			
			mBroadcaster.receive( transponderMessage );
		}
    }
	
    public void addMessageListener( Listener<Message> listener )
    {
		mBroadcaster.addListener( listener );
    }

    public void removeMessageListener( Listener<Message> listener )
    {
		mBroadcaster.removeListener( listener );
    }
}
