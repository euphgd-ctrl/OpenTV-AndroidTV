package com.opentv.app.ui;
import android.os.*;import android.util.Log;import android.view.*;import android.widget.*;import androidx.appcompat.app.AppCompatActivity;import androidx.media3.common.*;import androidx.media3.datasource.DefaultHttpDataSource;import androidx.media3.exoplayer.ExoPlayer;import androidx.media3.exoplayer.DefaultLoadControl;import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;import androidx.media3.ui.PlayerView;import com.opentv.app.R;import com.opentv.app.data.*;import com.opentv.app.model.Channel;import com.opentv.app.util.LogoLoader;import java.util.*;import java.util.concurrent.*;
public class PlayerActivity extends AppCompatActivity{
static final long RETRY_LIMIT=600000L,STALL_MS=90000L,BUFFER_HINT_MS=8000L,HEALTHY_RESET_MS=30000L;
static final long[] RETRY_DELAYS={3000L,5000L,8000L,13000L,20000L,30000L};ExoPlayer player;Channel channel;FavoritesStore fav;TextView name,meta,source,star,zap,retryStatus;ImageView logo;View info,retryPill;final Handler h=new Handler(Looper.getMainLooper());final List<Channel>channels=new ArrayList<>();int index=-1;final ExecutorService ex=Executors.newSingleThreadExecutor();String playlist,label;long retryStart=0,lastPosition=0,lastAdvance=0,bufferingSince=0,healthySince=0,behindWindowAt=0;int retryAttempts=0,behindWindowAttempts=0;boolean retrying=false,retriesExpired=false;int tuneGeneration=0;
final Runnable retryTask=new Runnable(){public void run(){retrying=false;if(player==null||retriesExpired||isFinishing()||isDestroyed())return;if(player.isPlaying()){recovered();return;}long now=SystemClock.elapsedRealtime();if(retryStart>0&&now-retryStart>=RETRY_LIMIT){markOffline();return;}lastAdvance=now;bufferingSince=now;lastPosition=0;player.stop();player.setMediaItem(MediaItem.fromUri(channel.url));player.prepare();player.play();}};
final Runnable watchdog=new Runnable(){public void run(){
if(player==null||isFinishing()||isDestroyed())return;
long now=SystemClock.elapsedRealtime(),pos=player.getCurrentPosition();
int state=player.getPlaybackState();
if(player.isPlaying()){
 if(pos>lastPosition+250||pos<lastPosition-1000){lastPosition=pos;lastAdvance=now;recovered();if(healthySince==0)healthySince=now;if(now-healthySince>=HEALTHY_RESET_MS){retryStart=0;retryAttempts=0;}}
 else if(lastAdvance>0&&now-lastAdvance>=STALL_MS)beginRetry("Frozen picture");
}else if(state==Player.STATE_BUFFERING){
 healthySince=0;if(bufferingSince==0)bufferingSince=now;
 if(now-bufferingSince>=BUFFER_HINT_MS&&!retrying&&!retriesExpired)showRetry("Buffering…");
 if(now-bufferingSince>=STALL_MS)beginRetry("Buffering timeout");
}else if(state==Player.STATE_ENDED){healthySince=0;beginRetry("Stream ended");}
h.postDelayed(this,5000);
}};
protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(R.layout.activity_player);fav=new FavoritesStore(this);channel=new Channel("",getIntent().getStringExtra("name"),getIntent().getStringExtra("url"),getIntent().getStringExtra("logo"),getIntent().getStringExtra("group"),getIntent().getStringExtra("country"),getIntent().getStringExtra("language"));playlist=getIntent().getStringExtra("playlist_url");label=getIntent().getStringExtra("source_label");info=findViewById(R.id.playerInfo);name=findViewById(R.id.playerName);meta=findViewById(R.id.playerMeta);source=findViewById(R.id.playerSource);star=findViewById(R.id.playerFavorite);zap=findViewById(R.id.channelZap);retryStatus=findViewById(R.id.retryStatus);retryPill=findViewById(R.id.retryPill);logo=findViewById(R.id.playerLogo);
DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("OpenTV/1.5 AndroidTV").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(25000);DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(15000,120000,5000,10000).setPrioritizeTimeOverSizeThresholds(true).build();player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6))).setLoadControl(load).build();((PlayerView)findViewById(R.id.playerView)).setPlayer(player);player.addListener(new Player.Listener(){
public void onPlaybackStateChanged(int state){if(state==Player.STATE_BUFFERING){if(bufferingSince==0)bufferingSince=SystemClock.elapsedRealtime();}else bufferingSince=0;}
public void onPlayerError(PlaybackException e){
 Log.w("OpenTV","Playback error: "+e.getErrorCodeName(),e);healthySince=0;
 if(e.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW){
 long now=SystemClock.elapsedRealtime();if(now-behindWindowAt>30000)behindWindowAttempts=0;behindWindowAt=now;
 if(++behindWindowAttempts<=2){player.seekToDefaultPosition();player.prepare();player.play();return;}
 }
 beginRetry("Stream error");
}
public void onIsPlayingChanged(boolean playing){if(playing){lastAdvance=SystemClock.elapsedRealtime();lastPosition=player.getCurrentPosition();if(healthySince==0)healthySince=lastAdvance;recovered();}else healthySince=0;}
});tune(channel);
if(playlist!=null)ex.execute(()->{try{List<Channel>x=new PlaylistRepository(this).load(playlist,false);h.post(()->{channels.addAll(x);for(int i=0;i<x.size();i++)if(x.get(i).url.equals(channel.url))index=i;});}catch(Exception ignored){}});
h.postDelayed(watchdog,5000);}
void tune(Channel c){
channel=c;tuneGeneration++;h.removeCallbacks(retryTask);retrying=false;retriesExpired=false;retryStart=0;retryAttempts=0;healthySince=0;behindWindowAttempts=0;
retryPill.setVisibility(View.GONE);lastPosition=0;lastAdvance=SystemClock.elapsedRealtime();bufferingSince=lastAdvance;
name.setText(c.name);meta.setText(c.subtitle());source.setText((label==null?"LIVE TV":label.toUpperCase(Locale.ROOT))+" • LIVE");updateStar();LogoLoader.load(logo,c.logo);
player.setMediaItem(MediaItem.fromUri(c.url));player.prepare();player.play();showChannelInfo();
}
void showRetry(String msg){retryStatus.setText(msg);retryPill.setVisibility(View.VISIBLE);}
void recovered(){if(retrying){h.removeCallbacks(retryTask);retrying=false;}if(player!=null&&player.isPlaying()){retriesExpired=false;retryPill.setVisibility(View.GONE);}}
void markOffline(){h.removeCallbacks(retryTask);retrying=false;retriesExpired=true;showRetry("Offline · OK to retry");}
void beginRetry(String reason){
if(player==null||retrying||retriesExpired)return;
long now=SystemClock.elapsedRealtime();if(retryStart==0)retryStart=now;
if(now-retryStart>=RETRY_LIMIT){markOffline();return;}
long delay=RETRY_DELAYS[Math.min(retryAttempts,RETRY_DELAYS.length-1)];retryAttempts++;retrying=true;healthySince=0;
showRetry("Reconnecting…");Log.w("OpenTV",reason+"; retry in "+delay+"ms");h.postDelayed(retryTask,delay);
}
void showChannelInfo(){info.setVisibility(View.VISIBLE);h.removeCallbacks(hideInfo);h.postDelayed(hideInfo,3000);}final Runnable hideInfo=()->info.setVisibility(View.GONE);void toggleFavorite(){boolean n=fav.toggle(channel);updateStar();Toast.makeText(this,n?"Added to favorites":"Removed from favorites",Toast.LENGTH_SHORT).show();}
void updateStar(){star.setText(fav.contains(channel.url)?"★ FAVORITE":"☆ FAVORITE");}
void change(int d){if(channels.isEmpty())return;if(index<0)index=0;index=(index+d+channels.size())%channels.size();tune(channels.get(index));zap.setText(channel.name);zap.setVisibility(View.VISIBLE);h.postDelayed(()->zap.setVisibility(View.GONE),1500);}
public boolean onKeyDown(int k,KeyEvent e){if(e.getRepeatCount()>0)return true;
if(retriesExpired&&(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)){retriesExpired=false;retryStart=0;retryAttempts=0;beginRetry("Manual retry");return true;}if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_CHANNEL_UP){change(-1);return true;}if(k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_CHANNEL_DOWN){change(1);return true;}if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER){toggleFavorite();return true;}if(k==KeyEvent.KEYCODE_INFO||k==KeyEvent.KEYCODE_MENU){showChannelInfo();return true;}return super.onKeyDown(k,e);}
protected void onDestroy(){h.removeCallbacksAndMessages(null);ex.shutdownNow();if(player!=null)player.release();super.onDestroy();}}
