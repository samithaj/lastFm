/***************************************************************************
 *   Copyright 2005-2009 Last.fm Ltd.                                      *
 *   Portions contributed by Casey Link, Lukasz Wisniewski,                *
 *   Mike Jennings, and Michael Novak Jr.                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.         *
 ***************************************************************************/
package fm.last.android.scrobbler;

import java.io.Serializable;

import fm.last.api.RadioTrack;

/**
 * Serializable queue entry class used by the scrobbler
 * 
 * @author Sam Steele <sam@last.fm>
 */
public class ScrobblerQueueEntry implements Serializable {
	private static final long serialVersionUID = 2L;
	public String artist = "";
	public String title = "";
	public String album = "";
	public Long startTime = 0L;
	public Long duration = 0L;
	public String trackAuth = "";
	public String rating = "";
	public Boolean postedNowPlaying = false;
	public Boolean loved = false;
	public Boolean currentTrack = false;

	public RadioTrack toRadioTrack() {
		return new RadioTrack("", title, "", album, artist, String.valueOf(duration), "", trackAuth, loved, null);
	}
}