/**
 * 
 */
package fm.last.android.widget;

import java.net.URL;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import fm.last.android.R;
import fm.last.android.utils.AsyncTaskEx;
import fm.last.util.UrlUtil;

/**
 * @author sam
 * 
 */
public class AlbumArt extends ImageView {

	private FetchArtTask _fetchTask;
	private Bitmap _bitmap;
	private int _defaultResource;

	public AlbumArt(Context context, AttributeSet attributeSet) {
		super(context, attributeSet);

		setDefaultImageResource(R.drawable.no_artwork);
	}

	public Bitmap getBitmap() {
		return _bitmap;
	}

	@Override
	public void setImageBitmap(Bitmap bmp) {
		super.setImageBitmap(bmp);
		_bitmap = bmp;
	}

	public void clear() {
		setImageResource(_defaultResource);
	}

	public void cancel() {
		if (_fetchTask != null)
			_fetchTask.cancel(true);
	}

	public void setDefaultImageResource(int res) {
		_defaultResource = res;
		setImageResource(_defaultResource);
	}

	public void fetch(String URL) {
		setImageResource(_defaultResource);

		if (_fetchTask != null)
			_fetchTask.cancel(true);

		_fetchTask = new FetchArtTask(URL);
		_fetchTask.execute((Void) null);
	}

	private class FetchArtTask extends AsyncTaskEx<Void, Void, Boolean> {
		Bitmap mBitmap = null;
		String mURL = null;

		public FetchArtTask(String url) {
			super();

			mURL = url;
			Log.i("Last.fm", "Fetching art: " + url);
		}

		@Override
		public Boolean doInBackground(Void... params) {
			boolean success = false;
			try {
				mBitmap = UrlUtil.getImage(new URL(mURL));
				success = true;
			} catch (OutOfMemoryError e) {
				mURL.replace("/_/", "/300x300/");
				try {
					mBitmap = UrlUtil.getImage(new URL(mURL));
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (Error e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				success = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			if (result && !isCancelled()) {
				setImageBitmap(mBitmap);
				_fetchTask = null;
			}
		}
	}

}
