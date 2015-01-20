Forked from [lastfm](https://github.com/lastfm/lastfm-android)
Modify to AndroidStudio

For study of Android **Account** and **Sync**

----------------------
#####Build Tips

In order to build this source, you will need to obtain an API key from
http://www.last.fm/api/

Once you have a key, create a new file in src/fm/last/android called PrivateAPIKey.java containing
the following:

```Java
package fm.last.android;

public class PrivateAPIKey {
	public static final String KEY = "put your API key here";
	public static final String SECRET = "put your API secret here";
	public static final String ANALYTICS_ID = ""; //leave this empty, or provide your own Google Analytics ID
}
```
