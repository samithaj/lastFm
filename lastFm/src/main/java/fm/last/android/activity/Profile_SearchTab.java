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
package fm.last.android.activity;

import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import fm.last.android.utils.AsyncTaskEx;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.SearchProvider;
import fm.last.android.adapter.ListAdapter;
import fm.last.android.adapter.ListEntry;
import fm.last.android.utils.ImageCache;

public class Profile_SearchTab extends ListActivity implements OnClickListener, OnKeyListener {
	private EditText mSearchText;
	private ImageButton mSearchButton;
	private ImageButton mVoiceButton;
	private ImageCache mImageCache;
	
	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.search);

		mSearchText = (EditText)findViewById(R.id.station_editbox);
		mSearchText.setOnKeyListener(this);
		
		mSearchButton = (ImageButton)findViewById(R.id.search);
		mSearchButton.setOnClickListener(this);
		
		mVoiceButton = (ImageButton)findViewById(R.id.voice);
		// Check to see if a recognition activity is present
		PackageManager pm = getPackageManager();
		List<ResolveInfo> activities = pm.queryIntentActivities(
		  new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
		if (activities.size() != 0) {
			mVoiceButton.setOnClickListener(this);
		} else {
			mVoiceButton.setVisibility(View.GONE);
		}
		
		
		mImageCache = new ImageCache();
		
		String query = getIntent().getStringExtra(SearchManager.QUERY);
		if(query != null) {
			mSearchText.setText(query);
			new SearchTask().execute((Void)null);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		mgr.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}
	
	public void onClick(View v) {
		if(v.getId() == R.id.voice) {
			try {
		        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
		                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
		        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.action_search));
		        startActivityForResult(intent, 1234);
			} catch (ActivityNotFoundException e) {
				LastFMApplication.getInstance().presentError(this, getString(R.string.ERROR_VOICE_TITLE), getString(R.string.ERROR_VOICE));
			}
		} else {
			new SearchTask().execute((Void)null);
		}
	}
	
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1234 && resultCode == RESULT_OK) {
            // Fill the list view with the strings the recognizer thought it could have heard
            ArrayList<String> matches = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
            mSearchText.setText(matches.get(0));
			new SearchTask().execute((Void)null);
        }

        super.onActivityResult(requestCode, resultCode, data);
    }
    
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_ENTER) {
			new SearchTask().execute((Void)null);
			return true;
		}
		return false;
	}
	
	protected void onListItemClick(ListView l, View v, int position, long id) {
		ListAdapter a = (ListAdapter)getListAdapter();
		
		if(a != null) {
			LastFMApplication.getInstance().mCtx = this;
			String URI = (String)a.getItem(position);
			Intent i = new Intent(this, Profile.class);
			i.setData(Uri.parse(URI));
			startActivity(i);
		}
	}
	
	@Override
	public boolean onSearchRequested() {
	     return true;
	 }
	
	private class SearchTask extends AsyncTaskEx<Void, Void, ArrayList<ListEntry>> {

		@Override
		public void onPreExecute() {
			String[] strings = new String[] { "Searching" };
			ListAdapter adapter = new ListAdapter(Profile_SearchTab.this, strings);

			mSearchText.setEnabled(false);
			mSearchButton.setEnabled(false);
			mVoiceButton.setEnabled(false);
			
			adapter.disableDisclosureIcons();
			adapter.setDisabled();
			adapter.enableLoadBar(0);
			setListAdapter(adapter);
			getListView().setVisibility(View.VISIBLE);
		}
		
		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {
			try {
				Cursor managedCursor = managedQuery(Uri.withAppendedPath(SearchProvider.SUGGESTIONS_URI,Uri.encode(mSearchText.getText().toString()).replace("/", "%2f")),null,null,null,null);
				if (managedCursor.getCount() == 0)
					return null;
				
				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				while(managedCursor.moveToNext()) {
					String text1 = managedCursor.getString(managedCursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1));
					String text2 = managedCursor.getString(managedCursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_2));
					String value = managedCursor.getString(managedCursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA));
					String imageURL = managedCursor.getString(managedCursor.getColumnIndexOrThrow("_imageURL"));
					
					ListEntry entry;
					if(!imageURL.equals("-1"))
						entry = new ListEntry(value, R.drawable.profile_unknown, text1, imageURL, text2);
					else
						entry = new ListEntry(value, R.drawable.list_icon_station, text1, imageURL, text2);
					entry.centerIcon = true;
					iconifiedEntries.add(entry);
				}
				return iconifiedEntries;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			ListAdapter adapter;
			
			if (iconifiedEntries != null) {
				adapter = new ListAdapter(Profile_SearchTab.this, mImageCache);
				adapter.setSourceIconified(iconifiedEntries);
			} else {
				String[] strings = new String[] { getString(R.string.newstation_noresults) };
				adapter = new ListAdapter(Profile_SearchTab.this, strings);
				adapter.disableDisclosureIcons();
				adapter.setDisabled();
			}
			setListAdapter(adapter);
			getListView().setVisibility(View.VISIBLE);
			mSearchText.setEnabled(true);
			mSearchButton.setEnabled(true);
			mVoiceButton.setEnabled(true);
		}
	}

}
