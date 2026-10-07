package com.opentv.app.data;
import android.content.*; import com.opentv.app.model.Channel; import org.json.*; import java.util.*;
public class FavoritesStore {private static final String PREFS="favorites",KEY="channels_json";private final SharedPreferences prefs;
public FavoritesStore(Context c){prefs=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
public synchronized List<Channel> all(){return new ArrayList<>(loadMap().values());} public synchronized boolean contains(String u){return loadMap().containsKey(u);}
public synchronized boolean toggle(Channel c){Map<String,Channel> m=loadMap();boolean n;if(m.containsKey(c.url)){m.remove(c.url);n=false;}else{m.put(c.url,c);n=true;}save(m);return n;}
private LinkedHashMap<String,Channel> loadMap(){LinkedHashMap<String,Channel> out=new LinkedHashMap<>();try{JSONArray a=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null){Channel c=Channel.fromJson(o);if(!c.url.isEmpty())out.put(c.url,c);}}}catch(Exception ignored){}return out;}
private void save(Map<String,Channel> m){JSONArray a=new JSONArray();for(Channel c:m.values())try{a.put(c.toJson());}catch(Exception ignored){}prefs.edit().putString(KEY,a.toString()).apply();}}
