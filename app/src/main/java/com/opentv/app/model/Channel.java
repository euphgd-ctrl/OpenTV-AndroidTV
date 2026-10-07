package com.opentv.app.model;
import org.json.JSONException; import org.json.JSONObject;
public class Channel { public final String id,name,url,logo,group,country,language;
public Channel(String id,String name,String url,String logo,String group,String country,String language){this.id=safe(id);this.name=safe(name);this.url=safe(url);this.logo=safe(logo);this.group=safe(group);this.country=safe(country);this.language=safe(language);}
private static String safe(String v){return v==null?"":v.trim();}
public String subtitle(){if(!group.isEmpty()&&!country.isEmpty())return group+" • "+country;if(!group.isEmpty())return group;if(!country.isEmpty())return country;if(!language.isEmpty())return language;return "Live";}
public JSONObject toJson() throws JSONException {JSONObject o=new JSONObject();o.put("id",id);o.put("name",name);o.put("url",url);o.put("logo",logo);o.put("group",group);o.put("country",country);o.put("language",language);return o;}
public static Channel fromJson(JSONObject o){return new Channel(o.optString("id"),o.optString("name"),o.optString("url"),o.optString("logo"),o.optString("group"),o.optString("country"),o.optString("language"));}}
