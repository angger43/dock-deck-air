package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fixed-reference renderer for Dock Deck Air. It uses one RGB_565 wallpaper, cached shaders and
 * Canvas primitives instead of blur or a large view hierarchy. Every focus target is registered
 * into one spatial D-pad graph.
 */
final class NothingLauncherView extends View {
    private static final float W = 720f, H = 405f;
    private static final ColorMatrixColorFilter MONO;
    static {
        ColorMatrix m = new ColorMatrix(); m.setSaturation(0f); MONO = new ColorMatrixColorFilter(m);
    }

    enum Screen { HOME, APPS, INPUTS, QUICK, APP_OPTIONS, SEARCH, SETTINGS, NETWORK, AD_BLOCKING,
        ACCESSORIES, PICTURE_SOUND, MEDIA, PRAYER, APP_STORAGE, PERSONALIZE, LIVE_TV, AMBIENT }

    static final class App {
        final String pkg, label; final Intent launch; final Drawable icon;
        App(String pkg, String label, Intent launch, Drawable icon) {
            this.pkg = pkg; this.label = label; this.launch = launch; this.icon = icon;
        }
    }
    static final class Item {
        final String id; final RectF rect; final Runnable click, hold;
        Item(String id, RectF rect, Runnable click, Runnable hold) {
            this.id=id; this.rect=rect; this.click=click; this.hold=hold;
        }
    }

    private final Activity activity;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Typeface body, bodyMedium;
    private final SharedPreferences prefs;
    private final AdBlockingManager adBlocking;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<App> apps = new ArrayList<>();
    private final Map<String, App> appByPackage = new HashMap<>();
    private Screen screen = Screen.HOME, previous = Screen.HOME;
    private String focusId = "dock_home", selectedPackage, query = "";
    private int appPage;
    private float sx = 1f, sy = 1f;
    private boolean longHandled;
    private long enterDown;
    private boolean paused;
    private final Bitmap wallpaper;

    private int bg, panel, panel2, text, dim, divider, accent, danger, border;
    private boolean light;
    private ThemePalette palette;
    private Shader backgroundShader, glowPrimaryShader, glowSecondaryShader;
    private int backgroundKey = Integer.MIN_VALUE;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (!paused) { invalidate(); clock.postDelayed(this, 1000); } }
    };

    NothingLauncherView(Activity activity) {
        super(activity); this.activity = activity;
        setFocusable(true); setFocusableInTouchMode(true); requestFocus();
        Typeface loaded;
        try { loaded = Typeface.createFromAsset(activity.getAssets(), "font/outfit.ttf"); }
        catch (RuntimeException e) { loaded = Typeface.create("sans-serif", Typeface.NORMAL); }
        body = loaded; bodyMedium = Typeface.create(loaded, Typeface.BOLD);
        prefs = activity.getSharedPreferences("nothing_tv", Context.MODE_PRIVATE);
        adBlocking = new AdBlockingManager(activity, prefs);
        if (prefs.getInt("dock_deck_ui_version", 0) < 1) {
            prefs.edit().putInt("dock_deck_ui_version", 1)
                    .putInt("theme_preset", ThemePalette.DOCK_DARK).apply();
        }
        BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
        bitmapOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        wallpaper = BitmapFactory.decodeResource(getResources(), R.drawable.dock_deck_mountain, bitmapOptions);
        AdhanScheduler.ensureDefaults(activity);
        loadApps();
    }

    void resumeUi() { paused=false; clock.removeCallbacks(ticker); clock.post(ticker); loadApps(); requestFocus(); }
    void pauseUi() { paused=true; clock.removeCallbacks(ticker); }
    void openHome() { show(Screen.HOME, "dock_home"); }
    void openInputs() { show(Screen.INPUTS, "input0"); }
    void openQuickSettings() { show(Screen.QUICK, "quick0"); }
    void openPersonalize() { show(Screen.PERSONALIZE, "widget_clock"); }
    void openScreen(String name) {
        try { Screen value=Screen.valueOf(name.toUpperCase(Locale.ROOT)); if(value==Screen.APP_OPTIONS&&!apps.isEmpty())selectedPackage=apps.get(0).pkg; show(value,null); }
        catch (IllegalArgumentException ignored) { openHome(); }
    }
    void setSearchQuery(String value) { query=value==null?"":value; show(Screen.SEARCH,"result0"); }
    void showUnavailable(String message) { toast(message); }
    boolean goBack() {
        if (screen == Screen.HOME) return true;
        if (screen == Screen.APP_OPTIONS) show(previous == Screen.HOME ? Screen.HOME : Screen.APPS, null);
        else show(Screen.HOME, "dock_home");
        return true;
    }

    private void show(Screen next, String preferred) {
        if (screen != next) previous = screen;
        screen = next; focusId = preferred; appPage = 0; invalidate(); requestFocus();
    }

    private void loadApps() {
        PackageManager pm = activity.getPackageManager();
        loader.execute(() -> {
            LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (!pkg.equals(activity.getPackageName())) found.putIfAbsent(pkg, ri);
                }
            }
            ArrayList<App> loaded = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
                try {
                    ResolveInfo ri=e.getValue(); String label=String.valueOf(ri.loadLabel(pm));
                    Intent launch=pm.getLeanbackLaunchIntentForPackage(e.getKey());
                    if (launch==null) launch=pm.getLaunchIntentForPackage(e.getKey());
                    if (launch==null) launch=new Intent(Intent.ACTION_MAIN).setClassName(e.getKey(),ri.activityInfo.name);
                    loaded.add(new App(e.getKey(), label, launch, ri.loadIcon(pm)));
                } catch (RuntimeException ignored) { }
            }
            loaded.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
            clock.post(() -> {
                apps.clear(); appByPackage.clear();
                apps.addAll(loaded); for (App a:apps) appByPackage.put(a.pkg,a);
                seedFavorites(); invalidate();
            });
        });
    }

    private void seedFavorites() {
        if (prefs.contains("favorites")) return;
        ArrayList<String> chosen = new ArrayList<>();
        try {
            for (String pkg : activity.getResources().getStringArray(R.array.dock_apps))
                if (appByPackage.containsKey(pkg)) chosen.add(pkg);
        } catch (RuntimeException ignored) { }
        for (App a:apps) if (chosen.size()<8 && !chosen.contains(a.pkg)) chosen.add(a.pkg);
        prefs.edit().putString("favorites", TextUtils.join(",", chosen)).apply();
    }

    private List<App> favorites() {
        ArrayList<App> result=new ArrayList<>();
        String saved=prefs.getString("favorites","");
        for(String pkg:saved.split(",")) { App a=appByPackage.get(pkg); if(a!=null) result.add(a); }
        for(App app:apps){if(result.size()>=8)break;boolean present=false;for(App favorite:result)if(favorite.pkg.equals(app.pkg)){present=true;break;}if(!present)result.add(app);}
        return result;
    }

    private Set<String> hidden() {
        return new HashSet<>(java.util.Arrays.asList(prefs.getString("hidden","").split(",")));
    }

    @Override protected void onDraw(Canvas raw) {
        super.onDraw(raw);
        sx=getWidth()/W; sy=getHeight()/H; raw.save(); raw.scale(sx,sy);
        applyPalette(); drawThemeBackground(raw); items.clear();
        // Centre each board from its actual content bounds; the source grids have different widths.
        float boardOffset=secondaryXOffset();
        if(boardOffset!=0) raw.translate(boardOffset,0);
        switch(screen) {
            case HOME: drawHome(raw); break; case APPS: drawApps(raw); break;
            case INPUTS: drawInputs(raw); break; case QUICK: drawQuick(raw); break;
            case APP_OPTIONS: drawAppOptions(raw); break; case SEARCH: drawSearch(raw); break;
            case SETTINGS: drawSettings(raw); break; case NETWORK: drawNetwork(raw); break;
            case AD_BLOCKING: drawAdBlocking(raw); break;
            case ACCESSORIES: drawAccessories(raw); break; case PICTURE_SOUND: drawPictureSound(raw); break;
            case MEDIA: drawMedia(raw); break; case PRAYER: drawPrayer(raw); break;
            case APP_STORAGE: drawAppStorage(raw); break; case PERSONALIZE: drawPersonalize(raw); break;
            case LIVE_TV: drawLiveTv(raw); break; case AMBIENT: drawAmbient(raw); break;
        }
        if(screen!=Screen.HOME&&screen!=Screen.AMBIENT)drawTopChrome(raw,true);
        if (focusId == null && !items.isEmpty()) { focusId=items.get(0).id; postInvalidate(); }
        raw.restore();
    }

    private void applyPalette() {
        int mode=prefs.getInt("theme_mode",1); Calendar c=Calendar.getInstance();
        boolean originalLight=mode==0 || (mode==2 && c.get(Calendar.HOUR_OF_DAY)>=prefs.getInt("light_start",6)
                && c.get(Calendar.HOUR_OF_DAY)<prefs.getInt("dark_start",18));
        int preset=prefs.getInt("theme_preset",ThemePalette.DOCK_DARK);
        if(preset<ThemePalette.ORIGINAL||preset>=ThemePalette.COUNT)preset=ThemePalette.ORIGINAL;
        palette=ThemePalette.resolve(preset,originalLight);light=palette.lightSurface;
        bg=palette.backgroundStart;panel=palette.panel;panel2=palette.panelAlt;text=palette.text;
        dim=palette.dim;divider=palette.divider;accent=palette.accent;danger=palette.danger;border=palette.border;
        int key=(preset+2)*2+(originalLight?1:0);if(key!=backgroundKey){prepareThemeBackground();backgroundKey=key;}
    }

    private void prepareThemeBackground(){
        backgroundShader=palette.backgroundStart==palette.backgroundEnd?null:
                new LinearGradient(0,0,W,H,palette.backgroundStart,palette.backgroundEnd,Shader.TileMode.CLAMP);
        glowPrimaryShader=palette.glowPrimary==0?null:new RadialGradient(610,75,255,
                new int[]{palette.glowPrimary,0x00000000},null,Shader.TileMode.CLAMP);
        glowSecondaryShader=palette.glowSecondary==0?null:new RadialGradient(105,365,285,
                new int[]{palette.glowSecondary,0x00000000},null,Shader.TileMode.CLAMP);
    }

    private void drawThemeBackground(Canvas c){
        if(screen==Screen.HOME&&wallpaper!=null){
            backgroundPaint.setShader(null);backgroundPaint.setAlpha(255);c.drawBitmap(wallpaper,null,new RectF(0,0,W,H),backgroundPaint);
            int overlay;
            switch(palette.preset){
                case ThemePalette.DOCK_LIGHT:overlay=0x38fff7ec;break;
                case ThemePalette.DOCK_BLUE:overlay=0x61001545;break;
                case ThemePalette.DOCK_AMBER:overlay=0x55a62f10;break;
                case ThemePalette.DOCK_DARK:overlay=0x61000b16;break;
                default:overlay=light?0x66f7f1e7:0x72030a12;
            }
            c.drawColor(overlay);
            backgroundPaint.setShader(new LinearGradient(0,0,0,H,new int[]{0x22000000,0x05000000,0x9a02060b},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,backgroundPaint);backgroundPaint.setShader(null);
            return;
        }
        if(backgroundShader==null)c.drawColor(bg);else{backgroundPaint.setShader(backgroundShader);c.drawRect(0,0,W,H,backgroundPaint);backgroundPaint.setShader(null);}
        if(glowPrimaryShader!=null){glowPaint.setShader(glowPrimaryShader);c.drawRect(0,0,W,H,glowPaint);}
        if(glowSecondaryShader!=null){glowPaint.setShader(glowSecondaryShader);c.drawRect(0,0,W,H,glowPaint);}
        glowPaint.setShader(null);
        if(palette.grid){for(float x=0;x<=W;x+=36)line(c,x,0,x,H,.45f,palette.gridColor);for(float y=0;y<=H;y+=27)line(c,0,y,W,y,.45f,palette.gridColor);}
    }

    private void drawHome(Canvas c) {
        Calendar now=Calendar.getInstance();
        drawTopChrome(c,false);
        label(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(now.getTime()),35,88,40,text,Paint.Align.LEFT,body);
        label(c,new SimpleDateFormat("EEEE, MMM d, yyyy",Locale.getDefault()).format(now.getTime()),36,109,12,text,Paint.Align.LEFT,body);
        drawPrayerHome(c,455,58,240,121,now);
        drawHomeControls(c);

        label(c,"Apps",36,244,14,text,Paint.Align.LEFT,bodyMedium);
        line(c,36,250,685,250,1,0x35ffffff);
        RectF shelf=new RectF(36,257,685,326);round(c,shelf,14,0x94101820);
        List<App> fav=favorites();
        for(int i=0;i<9;i++){
            float x=41+i*71.2f;RectF r=new RectF(x,261,x+66,322);boolean addApp=i==8;App a=i<fav.size()?fav.get(i):null;
            String id=addApp?"home_all_apps":"fav"+i;boolean f=focused(id);
            if(f){round(c,new RectF(r.left-2,r.top-2,r.right+2,r.bottom+2),13,0xe8ffffff);round(c,r,11,0xff202a34);}
            if(addApp){label(c,"+",r.centerX(),291,26,f?Color.WHITE:text,Paint.Align.CENTER,body);label(c,"Add App",r.centerX(),316,8,text,Paint.Align.CENTER,body);add(id,r,()->show(Screen.APPS,"app0"),null);}
            else if(a!=null){drawAppIcon(c,a,r.centerX(),282,31,false);label(c,shortLabel(a.label,12),r.centerX(),316,8,text,Paint.Align.CENTER,body);final App app=a;add(id,r,()->launch(app),()->options(app));}
        }
        drawHomeDock(c);
    }

    private void drawPrayerHome(Canvas c,float x,float y,float w,float h,Calendar now) {
        RectF r=new RectF(x,y,x+w,y+h); round(c,r,14,0xa83b2926);
        PrayerTimes pt=prayers(now); int next=pt.nextIndex(now), until=pt.minutesUntilNext(now);
        drawMosque(c,x+33,y+42,Color.WHITE);
        label(c,"Next Prayer",x+74,y+20,8,text,Paint.Align.LEFT,bodyMedium);
        label(c,PrayerTimes.NAMES[next],x+74,y+42,12,text,Paint.Align.LEFT,bodyMedium);
        label(c,PrayerTimes.format(pt.minutes[next],true),x+74,y+61,15,text,Paint.Align.LEFT,bodyMedium);
        label(c,(until/60)+"h "+(until%60)+"m left",x+74,y+77,8,text,Paint.Align.LEFT,body);
        line(c,x,y+84,x+w,y+84,1,0x2effffff);
        for(int i=0;i<6;i++) { float px=x+14+i*37; label(c,PrayerTimes.NAMES[i],px,y+100,6.5f,text,Paint.Align.LEFT,body);
            label(c,PrayerTimes.format(pt.minutes[i],true),px,y+115,6.5f,text,Paint.Align.LEFT,bodyMedium); }
        add("prayer_card",r,()->show(Screen.PRAYER,"prayer_city"),null);
    }

    private void drawHomeControls(Canvas c) {
        String[] ids={"home_input","home_network","home_sleep"};String[] titles={primaryInputLabel(),networkSummary(),"Sleep Timer"};
        String[] subs={"TV / HDMI / AV",networkConnected()?"Connected":"Offline","Off"};String[] glyphs={"▱",networkGlyph(),"◔"};
        for(int i=0;i<3;i++){float x=375+i*108;RectF r=new RectF(x,191,x+101,236);boolean f=focused(ids[i]);round(c,r,10,f?focusFill():0xa0242830);
            label(c,glyphs[i],x+15,210,12,f?focusText():text,Paint.Align.CENTER,bodyMedium);label(c,shortLabel(titles[i],12),x+29,208,8,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,subs[i],x+29,224,7,f?focusText():dim,Paint.Align.LEFT,body);
            final int n=i;add(ids[i],r,()->{if(n==0)show(Screen.INPUTS,"input0");else if(n==1)show(Screen.NETWORK,"network_wifi");else openSleepSettings();},null);}
    }

    private void drawApps(Canvas c) {
        header(c,"All Apps","Everything you watch, listen to and open.","");
        chip(c,"filter_all",36,116,48,"All",true,()->{});chip(c,"filter_video",90,116,54,"Video",false,()->{});
        chip(c,"filter_music",150,116,54,"Music",false,()->{});chip(c,"filter_tools",210,116,54,"Tools",false,()->{});chip(c,"filter_games",270,116,58,"Games",false,()->{});chip(c,"filter_utils",334,116,66,"Utilities",false,()->{});
        ArrayList<App> visible=new ArrayList<>(); Set<String> hide=hidden(); for(App a:apps)if(!hide.contains(a.pkg))visible.add(a);
        boolean paged=visible.size()>24;
        label(c,visible.size()+" apps   |   Press menu for options",paged?570:682,130,7,dim,Paint.Align.RIGHT,body);
        int start=Math.min(appPage*24,Math.max(0,visible.size()-1)); int end=Math.min(start+24,visible.size());
        for(int n=start;n<end;n++) { int i=n-start,row=i/8,col=i%8; float x=35+col*82.5f,y=145+row*78; RectF r=new RectF(x,y,x+72,y+67); App a=visible.get(n);
            boolean f=focused("app"+i); tile(c,r,f); drawAppIcon(c,a,r.centerX(),y+28,32,false); label(c,shortLabel(a.label,12),r.centerX(),y+59,7.5f,f?focusText():text,Paint.Align.CENTER,bodyMedium);
            add("app"+i,r,()->launch(a),()->options(a)); }
        if(paged) { chip(c,"page",604,116,80,"Page "+(appPage+1)+" / "+((visible.size()+23)/24),false,()->{appPage=(appPage+1)%((visible.size()+23)/24);focusId="app0";invalidate();}); }
    }

    private void drawInputs(Canvas c) {
        header(c,"INPUTS","Switch sources without leaving the couch.","05  /  INPUTS");
        List<TvInputInfo> list=tvInputs();
        if(list.isEmpty()) {
            emptyState(c,"No TV inputs were published by this firmware.","Open the TV's system input picker",()->openAction("com.android.tv.action.VIEW_INPUTS"));
        } else for(int i=0;i<list.size()&&i<6;i++) { int row=i/3,col=i%3; float x=48+col*203,y=144+row*102; RectF r=new RectF(x,y,x+190,y+89); TvInputInfo in=list.get(i); int state=inputState(in);
            boolean f=focused("input"+i); tile(c,r,f); label(c,inputGlyph(in),x+18,y+29,22,f?focusText():text,Paint.Align.LEFT,body);
            label(c,safeInputLabel(in),x+14,y+57,14,f?focusText():text,Paint.Align.LEFT,bodyMedium);
            label(c,stateLabel(state),x+14,y+77,9,f?focusText():dim,Paint.Align.LEFT,body); final TvInputInfo target=in; add("input"+i,r,()->openInput(target),null);
        }
        label(c,"Only sources reported by Android TV Input Framework are shown.",48,372,10,dim,Paint.Align.LEFT,body);
        footer(c,"05  /  INPUTS");
    }

    private void drawQuick(Canvas c) {
        header(c,"Quick Settings","The controls you reach for most.","");
        String[] title={"Wi-Fi","Bluetooth","Input","Cast","Picture","Sound","Sleep timer","Power"};
        String[] value={networkSummary(),bluetoothState(),primaryInputLabel(),"System receiver",pictureMode(),soundMode(),"System timer","System menu"};
        String[] glyph={"⌁","ᛒ","↪","▱","▧",")))","☾","○"};
        for(int i=0;i<8;i++){int row=i/4,col=i%4;float x=34+col*165,y=138+row*91;RectF r=new RectF(x,y,x+151,y+80);boolean f=focused("quick"+i);tile(c,r,f);
            label(c,glyph[i],x+13,y+30,20,f?focusText():text,Paint.Align.LEFT,body);label(c,title[i],x+13,y+53,12,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,value[i],x+13,y+72,9,f?focusText():dim,Paint.Align.LEFT,body);
            final int q=i;add("quick"+i,r,()->quickAction(q),null);}
        AudioManager am=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);int vol=am.getStreamVolume(AudioManager.STREAM_MUSIC),max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        RectF volume=new RectF(34,329,444,366);round(c,volume,12,panel);label(c,")))",52,353,10,text,Paint.Align.LEFT,body);line(c,78,348,389,348,3,divider);line(c,78,348,78+311f*vol/Math.max(1,max),348,3,text);circle(c,78+311f*vol/Math.max(1,max),348,4,Color.WHITE);label(c,String.valueOf(vol),425,353,9,text,Paint.Align.RIGHT,bodyMedium);
        RectF all=new RectF(456,329,686,366);button(c,"quick_all",all,"⌂   All Settings",()->show(Screen.SETTINGS,"settings0"));
    }

    private void drawSettings(Canvas c) {
        header(c,"Settings","Make the TV feel like yours.","");
        String[] t={"Network & internet","Ad Blocking","Remotes & accessories","Apps & storage","Home & widgets","Display & sound","System","Profiles & restrictions","About"};
        for(int i=0;i<9;i++){float y=116+i*29.5f;RectF r=new RectF(34,y,232,y+25);boolean f=focused("settings"+i);if(f)round(c,r,7,0xfff7f7f5);
            label(c,settingsGlyph(i),47,y+18,10,f?0xff101820:text,Paint.Align.CENTER,body);label(c,t[i],62,y+18,8.5f,f?0xff101820:text,Paint.Align.LEFT,bodyMedium);final int n=i;add("settings"+i,r,()->settingsAction(n),null);}
        RectF panelRect=new RectF(250,123,686,372);round(c,panelRect,12,0x8f121b24);label(c,"Network & internet",268,151,14,text,Paint.Align.LEFT,bodyMedium);
        RectF current=new RectF(266,165,670,214);round(c,current,9,0xa21e2934);label(c,networkGlyph(),283,189,14,text,Paint.Align.CENTER,body);label(c,networkDetail(),301,185,9,text,Paint.Align.LEFT,bodyMedium);label(c,networkConnected()?"Connected":"Not connected",301,201,7,dim,Paint.Align.LEFT,body);
        boolean wifiFocus=focused("settings_wifi");RectF wifi=new RectF(266,218,670,257);round(c,wifi,8,wifiFocus?focusFill():0x721d2731);label(c,"Wi-Fi",282,242,9,wifiFocus?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,wifiEnabled()?"On":"Off",650,242,8,wifiFocus?focusText():dim,Paint.Align.RIGHT,body);add("settings_wifi",wifi,()->openSystem(Settings.ACTION_WIFI_SETTINGS),null);
        label(c,"Available networks",268,278,7,dim,Paint.Align.LEFT,bodyMedium);
        String[] networks={networkDetail(),"Open network settings","Add network"};
        for(int i=0;i<3;i++){float y=286+i*25;RectF r=new RectF(266,y,670,y+22);boolean f=focused("settings_net"+i);if(f)round(c,r,6,focusFill());label(c,i==2?"+":"⌁",281,y+15,9,f?focusText():text,Paint.Align.CENTER,body);label(c,shortLabel(networks[i],32),298,y+15,8,f?focusText():text,Paint.Align.LEFT,body);add("settings_net"+i,r,()->openSystem(Settings.ACTION_WIFI_SETTINGS),null);}
    }

    private void drawNetwork(Canvas c) {
        header(c,"NETWORK & INTERNET","A clear connection status, at a glance.","08  /  NETWORK");
        RectF card=new RectF(48,141,270,350);round(c,card,15,panel);circle(c,159,214,42,palette.iconBackground);label(c,networkGlyph(),159,226,38,0xff171717,Paint.Align.CENTER,body);
        label(c,networkConnected()?"Connected":"Not connected",159,289,17,text,Paint.Align.CENTER,bodyMedium);label(c,networkDetail(),159,312,11,dim,Paint.Align.CENTER,body);pill(c,new RectF(108,327,210,347),networkConnected()?"ACTIVE":"OFFLINE",false,null);
        networkRow(c,"network_wifi",290,140,"Wi-Fi",wifiEnabled()?"On":"Off",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_current",290,195,networkDetail(),networkConnected()?"Connected":"Unavailable",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_other",290,250,"Other networks","Scan in system settings",()->openSystem(Settings.ACTION_WIFI_SETTINGS));
        networkRow(c,"network_ethernet",290,305,"Ethernet",isTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Connected":"Not connected",()->openSystem(Settings.ACTION_WIRELESS_SETTINGS));
        footer(c,"08  /  NETWORK");
    }

    private void drawAdBlocking(Canvas c) {
        header(c,"AD BLOCKING","Private, encrypted DNS without a VPN.","DNS  /  PROTECTION");
        AdBlockingManager.Status status=adBlocking.status();
        boolean selected=status.state.adGuardSelected;
        int stateColor=status.state.kind==DnsProtectionState.Kind.PROTECTED?0xff65d49a:
                status.state.kind==DnsProtectionState.Kind.CONNECTION_ERROR?danger:
                status.state.kind==DnsProtectionState.Kind.OFFLINE?accent:dim;

        RectF hero=new RectF(48,135,270,350);round(c,hero,15,panel);
        circle(c,159,205,42,selected?palette.iconBackground:panel2);
        drawProtectionIcon(c,159,205,status.state.kind,selected?0xff171717:dim);
        label(c,selected?"AdGuard DNS":"DNS protection",159,273,12,text,
                Paint.Align.CENTER,bodyMedium);
        label(c,status.state.title,159,306,12,stateColor,Paint.Align.CENTER,bodyMedium);
        pill(c,new RectF(108,326,210,347),selected?"SELECTED":"ORIGINAL",false,null);

        RectF toggle=new RectF(296,135,672,202);boolean toggleFocus=focused("adblock_toggle");
        round(c,toggle,12,toggleFocus?focusFill():panel);
        int primary=toggleFocus?focusText():text,secondary=toggleFocus?focusText():dim;
        label(c,"AdGuard DNS",316,162,13,primary,Paint.Align.LEFT,bodyMedium);
        label(c,selected?"On":"Off",316,184,9,secondary,Paint.Align.LEFT,body);
        RectF track=new RectF(606,153,652,181);round(c,track,14,
                toggleFocus?0x55303a43:selected?0xff65d49a:divider);
        circle(c,selected?638:620,167,10,toggleFocus?focusText():Color.WHITE);
        add("adblock_toggle",toggle,this::toggleAdBlocking,null);

        RectF statusCard=new RectF(296,216,672,286);round(c,statusCard,12,panel);
        label(c,"ACTUAL PROTECTION STATUS",316,238,7,dim,Paint.Align.LEFT,bodyMedium);
        label(c,status.state.title,316,259,11,stateColor,Paint.Align.LEFT,bodyMedium);
        label(c,shortLabel(status.error.isEmpty()?status.state.detail:status.error,52),316,277,
                7.5f,dim,Paint.Align.LEFT,body);

        RectF provider=new RectF(296,300,672,350);round(c,provider,12,panel);
        label(c,"PROVIDER",316,320,7,dim,Paint.Align.LEFT,bodyMedium);
        label(c,AdBlockingManager.HOST,316,341,10,text,Paint.Align.LEFT,bodyMedium);
        label(c,status.canWrite?"Android Private DNS":"Open TV Settings",652,341,8,
                status.canWrite?dim:accent,Paint.Align.RIGHT,body);
        add("adblock_settings",provider,adBlocking::openSupportedSettings,null);
        footer(c,"DNS  /  PROTECTION");
    }

    private void drawProtectionIcon(Canvas c,float cx,float cy,DnsProtectionState.Kind kind,int color) {
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if(kind==DnsProtectionState.Kind.PROTECTED){
            p.setStrokeWidth(5.5f);path.reset();path.moveTo(cx-18,cy);path.lineTo(cx-6,cy+13);
            path.lineTo(cx+21,cy-17);c.drawPath(path,p);
        }else if(kind==DnsProtectionState.Kind.CONNECTION_ERROR||kind==DnsProtectionState.Kind.OFFLINE){
            p.setStrokeWidth(5f);c.drawLine(cx,cy-18,cx,cy+6,p);p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx,cy+18,3.2f,p);
        }else{
            p.setStrokeWidth(3.5f);path.reset();path.moveTo(cx,cy-20);path.lineTo(cx+20,cy);
            path.lineTo(cx,cy+20);path.lineTo(cx-20,cy);path.close();c.drawPath(path,p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);p.setStrokeJoin(Paint.Join.MITER);p.setStyle(Paint.Style.FILL);
    }

    private void drawAccessories(Canvas c) {
        header(c,"REMOTES & ACCESSORIES","Pair a remote, headphones or a controller.","09  /  ACCESSORIES");
        RectF card=new RectF(48,141,276,350);round(c,card,15,panel);label(c,"◉",162,222,54,text,Paint.Align.CENTER,body);dot(c,"TV REMOTE",112,253,2.0f,text);label(c,"Connected through Android TV",162,291,11,dim,Paint.Align.CENTER,body);
        accessoriesRow(c,"pair",296,141,"＋  Pair accessory","Open Bluetooth pairing",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"remote",296,197,"▯  TV Remote","System managed",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"headphones",296,253,")))  Wireless headphones",bluetoothState(),()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        accessoriesRow(c,"controller",296,309,"⌁  Game controller","Pair or manage",()->openSystem(Settings.ACTION_BLUETOOTH_SETTINGS));
        footer(c,"09  /  ACCESSORIES");
    }

    private void drawPictureSound(Canvas c) {
        header(c,"PICTURE & SOUND","Comfortable viewing. Clear listening.","10  /  PICTURE + SOUND");
        label(c,"PICTURE",48,128,9,dim,Paint.Align.LEFT,bodyMedium);label(c,"SOUND",366,128,9,dim,Paint.Align.LEFT,bodyMedium);
        String[] left={"Picture mode|"+pictureMode(),"Brightness|"+globalInt("picture_brightness",-1,"%"),"Screen size|System controlled","Advanced picture|Open controls"};
        String[] right={"Sound mode|"+soundMode(),"Audio output|TV / connected output","Digital audio|"+globalString("sound_effect","System"),"Audio delay|"+globalInt("sound_spdif_delay",-1," ms")};
        for(int i=0;i<4;i++){settingRow(c,"picture"+i,48,140+i*53,294,left[i],()->openSystem(Settings.ACTION_DISPLAY_SETTINGS));settingRow(c,"sound"+i,366,140+i*53,294,right[i],()->openSystem(Settings.ACTION_SOUND_SETTINGS));}
        label(c,"Only controls supported by the TV firmware are opened.",366,362,9,dim,Paint.Align.LEFT,body);footer(c,"10  /  PICTURE + SOUND");
    }

    private void drawAppOptions(Canvas c) {
        header(c,"APP OPTIONS","One place to launch, pin or manage an app.","03  /  APP OPTIONS"); App a=appByPackage.get(selectedPackage);if(a==null){emptyState(c,"That app is no longer installed.","Back",this::goBack);return;}
        drawAppIcon(c,a,158,213,58,false);dot(c,a.label.toUpperCase(Locale.ROOT),105,273,2.4f,text);label(c,"Installed application",158,303,11,dim,Paint.Align.CENTER,body);
        boolean pinned=favorites().stream().anyMatch(x->x.pkg.equals(a.pkg));
        appOption(c,"option_open",310,128,"▶  Open "+a.label,"",()->launch(a));
        appOption(c,"option_pin",310,176,"⚑  Pinned to Home",pinned?"On":"Off",()->toggleFavorite(a));
        appOption(c,"option_move",310,224,"↔  Move in app row","Move right",()->moveFavorite(a));
        appOption(c,"option_info",310,272,"ⓘ  App information","Storage and permissions",()->appInfo(a));
        appOption(c,"option_hide",310,320,"⠿  Hide from Home","Keep app installed",()->hideApp(a));footer(c,"03  /  APP OPTIONS");
    }

    private void drawSearch(Canvas c) {
        header(c,"SEARCH","Find an installed app, input or TV setting.","04  /  SEARCH");
        RectF bar=new RectF(48,112,650,151);round(c,bar,20,panel);label(c,"⌕",63,138,22,text,Paint.Align.LEFT,body);label(c,query.isEmpty()?"Type with the on-screen keyboard":query,92,138,17,query.isEmpty()?dim:text,Paint.Align.LEFT,body);RectF mic=new RectF(614,116,646,147);boolean fm=focused("voice");if(fm)round(c,mic,16,focusFill());label(c,"●",630,138,13,fm?focusText():text,Paint.Align.CENTER,body);add("voice",mic,()->((MainActivity)activity).startVoiceSearch(),null);
        ArrayList<App> matches=new ArrayList<>();for(App a:apps)if(!query.isEmpty()&&a.label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))matches.add(a);
        label(c,"RESULTS",48,176,9,dim,Paint.Align.LEFT,bodyMedium);for(int i=0;i<Math.min(3,matches.size());i++){App a=matches.get(i);RectF r=new RectF(48+i*126,185,164+i*126,258);boolean f=focused("result"+i);tile(c,r,f);drawAppIcon(c,a,r.centerX(),r.top+30,29,false);label(c,a.label,r.centerX(),r.bottom-8,9,f?focusText():text,Paint.Align.CENTER,body);add("result"+i,r,()->launch(a),()->options(a));}
        String keys="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_@";for(int i=0;i<keys.length();i++){int row=i/10,col=i%10;float x=48+col*39,y=262+row*24;RectF r=new RectF(x,y,x+34,y+20);boolean f=focused("key"+i);round(c,r,7,f?focusFill():panel);label(c,String.valueOf(keys.charAt(i)),r.centerX(),y+14,9,f?focusText():text,Paint.Align.CENTER,bodyMedium);final char ch=keys.charAt(i);add("key"+i,r,()->{query+=ch;invalidate();},null);}
        button(c,"search_space",455,new RectF(455,262,548,286),"Space",()->{query+=" ";invalidate();});button(c,"search_delete",557,new RectF(557,262,650,286),"Delete",()->{if(!query.isEmpty())query=query.substring(0,query.length()-1);invalidate();});button(c,"search_done",455,new RectF(455,294,650,326),"Done",()->{if(!matches.isEmpty())launch(matches.get(0));});footer(c,"04  /  SEARCH");
    }

    private void drawMedia(Canvas c) {
        header(c,"Media Library","Your files, on the big screen.","");
        List<StorageVolume> volumes=((StorageManager)activity.getSystemService(Context.STORAGE_SERVICE)).getStorageVolumes();
        chip(c,"media_all",34,116,48,"All",true,()->{});chip(c,"media_video",88,116,58,"Videos",false,()->{});chip(c,"media_music",152,116,58,"Music",false,()->{});chip(c,"media_photos",216,116,58,"Photos",false,()->{});chip(c,"media_folders",280,116,66,"Folders",false,()->{});
        int shown=0;for(StorageVolume v:volumes){if(shown>=5)break;float x=34+shown*131;RectF r=new RectF(x,151,x+119,218);boolean f=focused("media"+shown);tile(c,r,f);label(c,"▰",x+17,181,20,f?focusText():text,Paint.Align.LEFT,body);label(c,shortLabel(v.getDescription(activity),15),x+13,202,8,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,v.isRemovable()?"External":"Device",x+13,213,6.5f,f?focusText():dim,Paint.Align.LEFT,body);add("media"+shown,r,this::browseMedia,null);shown++;}
        if(shown==0)label(c,"No storage volume is currently mounted.",34,190,10,text,Paint.Align.LEFT,bodyMedium);
        label(c,"Recent media",34,251,9,text,Paint.Align.LEFT,bodyMedium);RectF recent=new RectF(34,264,686,328);round(c,recent,11,0x65131c25);label(c,"No media index is exposed by Android. Browse the real storage to open a file.",recent.centerX(),294,9,dim,Paint.Align.CENTER,body);
        button(c,"browse",new RectF(238,340,482,376),"▰   Browse Folders",this::browseMedia);
    }

    private void drawPrayer(Canvas c) {
        header(c,"Prayer & Adhan","Offline schedule and automatic TV playback.","");Calendar now=Calendar.getInstance();PrayerTimes pt=prayers(now);int next=pt.nextIndex(now),left=pt.minutesUntilNext(now);
        RectF card=new RectF(34,130,239,366);round(c,card,14,panel);label(c,"NEXT PRAYER",51,157,8,dim,Paint.Align.LEFT,bodyMedium);drawMosque(c,74,206,text);label(c,PrayerTimes.NAMES[next],51,253,21,text,Paint.Align.LEFT,bodyMedium);label(c,PrayerTimes.format(pt.minutes[next],true),51,284,28,text,Paint.Align.LEFT,bodyMedium);label(c,(left/60)+"h "+(left%60)+"m remaining",51,306,9,dim,Paint.Align.LEFT,body);label(c,PrayerTimes.CITIES[Math.max(0,Math.min(PrayerTimes.CITIES.length-1,prefs.getInt("prayer_city",0)))].name,51,343,9,text,Paint.Align.LEFT,bodyMedium);
        int city=Math.max(0,Math.min(PrayerTimes.CITIES.length-1,prefs.getInt("prayer_city",0))),method=Math.max(0,Math.min(PrayerTimes.METHODS.length-1,prefs.getInt("prayer_method",0)));
        prayerSetting(c,"prayer_city",255,130,431,"Location",PrayerTimes.CITIES[city].name,()->{prefs.edit().putInt("prayer_city",(city+1)%PrayerTimes.CITIES.length).apply();AdhanScheduler.schedule(activity);invalidate();});
        prayerSetting(c,"prayer_method",255,171,431,"Calculation method",PrayerTimes.METHODS[method],()->{prefs.edit().putInt("prayer_method",(method+1)%PrayerTimes.METHODS.length).apply();AdhanScheduler.schedule(activity);invalidate();});
        boolean enabled=prefs.getBoolean(AdhanScheduler.KEY_ENABLED,true);
        prayerSetting(c,"adhan_enabled",255,212,210,"Automatic Adhan",enabled?"Enabled":"Disabled",()->{prefs.edit().putBoolean(AdhanScheduler.KEY_ENABLED,!enabled).apply();AdhanScheduler.schedule(activity);invalidate();});
        int audio=prefs.getInt(AdhanScheduler.KEY_AUDIO,0),volume=prefs.getInt(AdhanScheduler.KEY_VOLUME,65);
        prayerSetting(c,"adhan_audio",476,212,210,"Audio",audio==0?"Full · 4m 36s":"Brief · 20 sec",()->{prefs.edit().putInt(AdhanScheduler.KEY_AUDIO,(audio+1)%2).apply();invalidate();});
        prayerSetting(c,"adhan_volume",255,253,431,"Adhan volume",volume+"%",()->{int nextVolume=volume<35?35:volume<65?65:volume<85?85:volume<100?100:20;prefs.edit().putInt(AdhanScheduler.KEY_VOLUME,nextVolume).apply();invalidate();});
        int mask=prefs.getInt(AdhanScheduler.KEY_MASK,AdhanPlan.DEFAULT_MASK);int[] prayerIndexes={0,2,3,4,5};
        for(int i=0;i<prayerIndexes.length;i++){int prayer=prayerIndexes[i];float x=255+i*87;RectF r=new RectF(x,298,x+79,326);boolean on=(mask&(1<<prayer))!=0,f=focused("adhan_prayer"+prayer);round(c,r,9,f?focusFill():on?0xb5313c47:0x67151d25);label(c,PrayerTimes.NAMES[prayer],r.centerX(),316,7.5f,f?focusText():on?text:dim,Paint.Align.CENTER,bodyMedium);add("adhan_prayer"+prayer,r,()->{prefs.edit().putInt(AdhanScheduler.KEY_MASK,mask^(1<<prayer)).apply();AdhanScheduler.schedule(activity);invalidate();},null);}
        boolean playing=AdhanPlaybackService.isPlaying();
        button(c,"adhan_test",new RectF(255,338,451,372),playing?"■   Stop Adhan":"▶   Play Adhan",()->{AdhanScheduler.toggleTest(activity);invalidate();});
        boolean exact=AdhanScheduler.exactAccess(activity);button(c,"adhan_exact",new RectF(462,338,686,372),exact?"✓   Exact scheduling":"!   Allow exact scheduling",()->{try{activity.startActivity(AdhanScheduler.exactAccessIntent(activity));}catch(RuntimeException e){toast("Exact alarm settings are unavailable");}});
    }

    private void drawAppStorage(Canvas c) {
        header(c,"APPS & STORAGE","A lighter Home starts with fewer distractions.","16  /  APPS + STORAGE");File data=activity.getFilesDir();StatFs stat=new StatFs(data.getPath());long total=stat.getTotalBytes(),free=stat.getAvailableBytes(),used=total-free;
        RectF card=new RectF(48,144,270,353);round(c,card,15,panel);label(c,"DEVICE STORAGE",63,174,10,dim,Paint.Align.LEFT,body);dot(c,human(used),63,206,4.0f,text);label(c,"used of "+human(total),63,273,11,dim,Paint.Align.LEFT,body);line(c,63,303,247,303,7,divider);line(c,63,303,63+184f*used/Math.max(1,total),303,7,text);label(c,human(free)+" available",63,332,11,text,Paint.Align.LEFT,bodyMedium);
        appOption(c,"storage_apps",292,142,"⠿  See all apps",apps.size()+" apps",()->show(Screen.APPS,"app0"));if(!apps.isEmpty()){App a=apps.get(0);appOption(c,"storage_first",292,195,"▶  "+a.label,"App details",()->appInfo(a));}appOption(c,"storage_manage",292,248,"ⓘ  Manage applications","System settings",()->openSystem(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));appOption(c,"storage_external",292,301,"⌁  External storage",externalStorageSummary(),this::browseMedia);footer(c,"16  /  APPS + STORAGE");
    }

    private void drawPersonalize(Canvas c) {
        header(c,"Appearance","Theme previews apply immediately and persist after restart.","");
        int mode=prefs.getInt("theme_mode",1),preset=prefs.getInt("theme_preset",ThemePalette.DOCK_DARK);
        label(c,"Brightness",34,120,8,dim,Paint.Align.LEFT,bodyMedium);String[] modes={"Light","Dark","Automatic"};
        for(int i=0;i<3;i++){final int value=i;chip(c,"appearance_mode"+i,34+i*83,130,76,modes[i],mode==i,()->{prefs.edit().putInt("theme_mode",value).apply();backgroundKey=Integer.MIN_VALUE;invalidate();});}
        label(c,"Interface theme",34,169,8,dim,Paint.Align.LEFT,bodyMedium);
        int[] order={ThemePalette.DOCK_DARK,ThemePalette.DOCK_LIGHT,ThemePalette.DOCK_BLUE,ThemePalette.DOCK_AMBER,ThemePalette.NOTHING_LIGHT,ThemePalette.GLASS_DARK,ThemePalette.NEO_MINIMAL,ThemePalette.INDUSTRIAL_MONO,ThemePalette.WARM_PREMIUM,ThemePalette.PLAYFUL_ACCENT};
        for(int i=0;i<order.length;i++){int value=order[i],row=i/5,col=i%5;float x=34+col*132,y=178+row*91;RectF r=new RectF(x,y,x+120,y+74);boolean f=focused("appearance_theme"+value),selected=preset==value;ThemePalette preview=ThemePalette.resolve(value,mode==0);
            round(c,r,10,preview.backgroundStart);RectF mini=new RectF(x+8,y+8,x+112,y+50);round(c,mini,7,preview.panel);round(c,new RectF(x+16,y+16,x+72,y+22),3,preview.text);for(int n=0;n<4;n++)round(c,new RectF(x+16+n*22,y+29,x+33+n*22,y+43),4,n==0?preview.focusFill:preview.panelAlt);label(c,ThemePalette.name(value),x+8,y+66,7,preview.text,Paint.Align.LEFT,bodyMedium);if(selected||f)stroke(c,new RectF(r.left-2,r.top-2,r.right+2,r.bottom+2),12,f?text:accent,f?2.4f:1.5f);
            add("appearance_theme"+value,r,()->{prefs.edit().putInt("theme_preset",value).apply();backgroundKey=Integer.MIN_VALUE;invalidate();},null);}
        button(c,"appearance_favs",new RectF(34,365,219,395),"↔  Favorite Apps",()->show(Screen.APPS,"app0"));button(c,"appearance_dream",new RectF(231,365,416,395),"◷  Screen Saver",()->openSystem(Settings.ACTION_DREAM_SETTINGS));button(c,"appearance_prayer",new RectF(428,365,686,395),"Prayer & Adhan",()->show(Screen.PRAYER,"prayer_city"));
    }

    private void drawLiveTv(Canvas c) {
        header(c,"LIVE TV",new SimpleDateFormat("EEEE, dd MMMM",Locale.getDefault()).format(Calendar.getInstance().getTime())+"  /  Channels from the TV provider","11  /  LIVE TV");List<TvInputInfo> inputs=tvInputs();ArrayList<TvInputInfo> tuners=new ArrayList<>();for(TvInputInfo i:inputs)if(i.getType()==TvInputInfo.TYPE_TUNER)tuners.add(i);
        chip(c,"tv_all",48,116,80,"All channels",true,()->{});button(c,"tv_scan",new RectF(552,112,650,139),"Scan channels",()->openAction("android.settings.CHANNELS_SETTINGS"));
        if(tuners.isEmpty())emptyState(c,"No tuner service is exposed by this TV.","Open the genuine Live TV app",this::openLiveTv);
        else for(int i=0;i<tuners.size()&&i<4;i++){RectF r=new RectF(48,158+i*47,650,197+i*47);boolean f=focused("tv"+i);tile(c,r,f);label(c,String.format(Locale.US,"%02d",i+1),62,r.centerY()+5,10,f?focusText():dim,Paint.Align.LEFT,body);label(c,safeInputLabel(tuners.get(i)),111,r.centerY()+5,13,f?focusText():text,Paint.Align.LEFT,bodyMedium);final TvInputInfo in=tuners.get(i);add("tv"+i,r,()->openInput(in),null);}label(c,"Programme listings come only from the TV provider; this launcher invents no channel data.",48,357,9,dim,Paint.Align.LEFT,body);footer(c,"11  /  LIVE TV");
    }

    private void drawAmbient(Canvas c) {
        dot(c,"DOCK DECK /AIR/",43,30,1.2f,text);Calendar now=Calendar.getInstance();dot(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(now.getTime()),76,132,6.5f,text);dot(c,new SimpleDateFormat("EEEE / dd MMMM",Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.ROOT),76,225,1.8f,text);PrayerTimes pt=prayers(now);int n=pt.nextIndex(now);RectF r=new RectF(76,262,348,301);round(c,r,20,panel);circle(c,96,281,4,accent);label(c,"Next prayer  /  "+PrayerTimes.NAMES[n]+" "+PrayerTimes.format(pt.minutes[n],true),112,287,13,text,Paint.Align.LEFT,body);drawAnalog(c,520,182,92);label(c,"Press any button to return",43,375,10,dim,Paint.Align.LEFT,body);dot(c,"CLOCK / AMBIENT",565,365,1.3f,dim);add("ambient",new RectF(0,0,W,H),()->show(Screen.HOME,"dock_home"),null);
    }

    // ---- Components -----------------------------------------------------
    private void drawTopChrome(Canvas c,boolean compact){
        dot(c,"DOCK DECK /AIR/",36,24,.72f,text);
        if(compact)label(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(Calendar.getInstance().getTime()),602,25,9,text,Paint.Align.RIGHT,bodyMedium);
        topIcon(c,"search",compact?614:574,"search",()->show(Screen.SEARCH,"key0"));
        topIcon(c,"inputtop",compact?640:610,"input",()->show(Screen.INPUTS,"input0"));
        topIcon(c,"settingtop",compact?666:646,"settings",()->show(Screen.SETTINGS,"settings0"));
        RectF profile=new RectF(compact?687:682,10,compact?711:710,38);boolean profileFocus=focused("profile");round(c,profile,14,profileFocus?0xc9ffffff:0x8f16212b);int profileColor=profileFocus?0xff101820:text;float profileX=profile.centerX();circle(c,profileX,19,3.4f,profileColor);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setColor(profileColor);c.drawArc(new RectF(profileX-6.2f,23,profileX+6.2f,34),190,160,false,p);p.setStyle(Paint.Style.FILL);add("profile",profile,()->openSystem(Settings.ACTION_SYNC_SETTINGS),null);
    }

    private void topIcon(Canvas c,String id,float x,String kind,Runnable run){
        RectF r=new RectF(x,10,x+28,38);boolean f=focused(id);if(f)round(c,r,14,0xc9ffffff);int color=f?0xff101820:text;float cx=r.centerX(),cy=r.centerY();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(color);
        if("search".equals(kind)){c.drawCircle(cx-2,cy-2,4.7f,p);c.drawLine(cx+1.5f,cy+1.5f,cx+6,cy+6,p);}
        else if("settings".equals(kind)){c.drawCircle(cx,cy,5.4f,p);c.drawCircle(cx,cy,1.8f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(cx+(float)Math.cos(a)*7,cy+(float)Math.sin(a)*7,cx+(float)Math.cos(a)*9,cy+(float)Math.sin(a)*9,p);}}
        else{c.drawLine(cx-6,cy,cx+5,cy,p);c.drawLine(cx+1,cy-4,cx+5,cy,p);c.drawLine(cx+1,cy+4,cx+5,cy,p);}
        p.setStyle(Paint.Style.FILL);add(id,r,run,null);
    }

    private void drawHomeDock(Canvas c){
        RectF band=new RectF(106,337,614,392);round(c,band,15,0xe51a222b);
        String[] ids={"dock_home","dock_apps","dock_tv","dock_media","dock_input","dock_settings"};
        String[] names={"Home","Apps","Live TV","Media","Input","Settings"};
        for(int i=0;i<ids.length;i++){float x=111+i*83.2f;RectF r=new RectF(x,342,x+74,387);boolean f=focused(ids[i]);if(f)round(c,r,12,0xfff7f7f5);if(i>0)line(c,x-5,348,x-5,381,1,0x20ffffff);
            drawDockIcon(c,i,r.centerX(),356,f?0xff101820:text);label(c,names[i],r.centerX(),379,8,f?0xff101820:text,Paint.Align.CENTER,bodyMedium);
            final int n=i;add(ids[i],r,()->{switch(n){case 1:show(Screen.APPS,"app0");break;case 2:show(Screen.LIVE_TV,"tv0");break;case 3:show(Screen.MEDIA,"media0");break;case 4:show(Screen.INPUTS,"input0");break;case 5:show(Screen.SETTINGS,"settings0");break;default:break;}},null);}
    }

    private void drawDockIcon(Canvas c,int icon,float cx,float cy,int color){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.55f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(color);
        if(icon==0){
            path.reset();path.moveTo(cx-7,cy);path.lineTo(cx,cy-6);path.lineTo(cx+7,cy);path.moveTo(cx-5,cy-1);path.lineTo(cx-5,cy+6);path.lineTo(cx+5,cy+6);path.lineTo(cx+5,cy-1);c.drawPath(path,p);
        }else if(icon==1){
            for(int row=0;row<2;row++)for(int col=0;col<2;col++)c.drawRoundRect(new RectF(cx-6+col*7,cy-6+row*7,cx-1+col*7,cy-1+row*7),1.2f,1.2f,p);
        }else if(icon==2){
            c.drawRoundRect(new RectF(cx-8,cy-6,cx+8,cy+5),2,2,p);path.reset();path.moveTo(cx-2.5f,cy-3);path.lineTo(cx+3.5f,cy-.5f);path.lineTo(cx-2.5f,cy+2);path.close();c.drawPath(path,p);c.drawLine(cx-4,cy+8,cx+4,cy+8,p);c.drawLine(cx,cy+5,cx,cy+8,p);
        }else if(icon==3){
            path.reset();path.moveTo(cx-8,cy-4);path.lineTo(cx-2,cy-4);path.lineTo(cx,cy-1);path.lineTo(cx+8,cy-1);path.lineTo(cx+8,cy+6);path.lineTo(cx-8,cy+6);path.close();c.drawPath(path,p);c.drawLine(cx-5,cy-7,cx+6,cy-7,p);c.drawLine(cx+6,cy-7,cx+8,cy-5,p);
        }else if(icon==4){
            c.drawRoundRect(new RectF(cx-8,cy-5,cx+8,cy+5),2.5f,2.5f,p);c.drawLine(cx-4,cy-2,cx-4,cy+2,p);c.drawLine(cx,cy-2,cx,cy+2,p);c.drawLine(cx+4,cy-2,cx+4,cy+2,p);c.drawLine(cx+8,cy,cx+11,cy,p);c.drawLine(cx+9,cy-2,cx+11,cy,p);c.drawLine(cx+9,cy+2,cx+11,cy,p);
        }else{
            c.drawCircle(cx,cy,4.3f,p);c.drawCircle(cx,cy,1.5f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(cx+(float)Math.cos(a)*6,cy+(float)Math.sin(a)*6,cx+(float)Math.cos(a)*8,cy+(float)Math.sin(a)*8,p);}
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawMosque(Canvas c,float x,float y,int color){
        p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRect(x-18,y+5,x+18,y+17,p);c.drawRect(x-13,y-3,x+13,y+7,p);c.drawCircle(x,y-3,7,p);c.drawRect(x-23,y-9,x-19,y+17,p);c.drawCircle(x-21,y-10,2.5f,p);c.drawRect(x+19,y-9,x+23,y+17,p);c.drawCircle(x+21,y-10,2.5f,p);c.drawRect(x-5,y+8,x+5,y+17,p);
    }

    private String shortLabel(String value,int max){if(value==null)return "";return value.length()<=max?value:value.substring(0,Math.max(1,max-1))+"…";}

    private float secondaryXOffset(){
        return 0f;
    }
    private void header(Canvas c,String title,String subtitle,String page){label(c,title,34,76,22,text,Paint.Align.LEFT,bodyMedium);label(c,subtitle,34,95,9,dim,Paint.Align.LEFT,body);}
    private void footer(Canvas c,String page){}
    private void homeBottom(Canvas c,String id,float x,String glyph,String title,Runnable run){RectF r=new RectF(x,342,x+151,370);button(c,id,r,glyph+"   "+title,run);}
    private void iconButton(Canvas c,String id,float x,String glyph,Runnable run){RectF r=new RectF(x,22,x+30,50);boolean f=focused(id);if(f)round(c,r,14,focusFill());label(c,glyph,r.centerX(),42,18,f?focusText():text,Paint.Align.CENTER,body);add(id,r,run,null);}
    private void chip(Canvas c,String id,float x,float y,float w,String value,boolean selected,Runnable run){RectF r=new RectF(x,y,x+w,y+25);boolean f=focused(id);round(c,r,13,f||selected?focusFill():panel);label(c,value,r.centerX(),y+17,9,f||selected?focusText():text,Paint.Align.CENTER,bodyMedium);add(id,r,run,null);}
    private void button(Canvas c,String id,RectF r,String title,Runnable run){boolean f=focused(id);round(c,r,14,f?focusFill():buttonFill(id));if(f)stroke(c,r,14,palette.focusText,2);label(c,title,r.centerX(),r.centerY()+5,11,f?focusText():text,Paint.Align.CENTER,bodyMedium);add(id,r,run,null);}
    private void button(Canvas c,String id,float x,RectF r,String title,Runnable run){button(c,id,r,title,run);}
    private void tile(Canvas c,RectF r,boolean focused){round(c,r,10,focused?focusFill():panel);if(focused)stroke(c,new RectF(r.left-1.5f,r.top-1.5f,r.right+1.5f,r.bottom+1.5f),11,text,1.5f);}
    private void settingRow(Canvas c,String id,float x,float y,float w,String spec,Runnable run){String[] v=spec.split("\\|",2);RectF r=new RectF(x,y,x+w,y+43);boolean f=focused(id);tile(c,r,f);label(c,v[0],x+14,y+27,11,f?focusText():text,Paint.Align.LEFT,bodyMedium);if(v.length>1)label(c,v[1],x+w-12,y+27,9,f?focusText():dim,Paint.Align.RIGHT,body);add(id,r,run,null);}
    private void appOption(Canvas c,String id,float x,float y,String title,String status,Runnable run){RectF r=new RectF(x,y,650,y+43);boolean f=focused(id);tile(c,r,f);label(c,title,x+14,y+27,11,f?focusText():text,Paint.Align.LEFT,bodyMedium);label(c,status,635,y+27,9,f?focusText():dim,Paint.Align.RIGHT,body);add(id,r,run,null);}
    private void prayerRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){appOption(c,id,x,y,title,status,run);}
    private void prayerSetting(Canvas c,String id,float x,float y,float w,String title,String status,Runnable run){RectF r=new RectF(x,y,x+w,y+35);boolean f=focused(id);round(c,r,9,f?focusFill():panel);label(c,title,x+13,y+15,7,f?focusText():dim,Paint.Align.LEFT,body);label(c,shortLabel(status,27),x+13,y+29,9,f?focusText():text,Paint.Align.LEFT,bodyMedium);add(id,r,run,null);}
    private void personalizeRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,294,title+"|"+status,run);}
    private void networkRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,360,"⌁  "+title+"|"+status,run);}
    private void accessoriesRow(Canvas c,String id,float x,float y,String title,String status,Runnable run){settingRow(c,id,x,y,354,title+"|"+status,run);}
    private void emptyState(Canvas c,String message,String action,Runnable run){float center=360-secondaryXOffset();label(c,message,center,225,16,text,Paint.Align.CENTER,bodyMedium);button(c,"empty_action",new RectF(center-125,255,center+125,297),action,run);}
    private void pill(Canvas c,RectF r,String value,boolean focus,Runnable run){round(c,r,r.height()/2,focus?focusFill():accent);label(c,value,r.centerX(),r.centerY()+4,7,focus?focusText():Color.WHITE,Paint.Align.CENTER,bodyMedium);if(run!=null)add("pill_"+value,r,run,null);}

    private void drawAnalog(Canvas c,float cx,float cy,float radius){circle(c,cx,cy,radius,panel);if(Color.alpha(border)>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(border);c.drawCircle(cx,cy,radius,p);p.setStyle(Paint.Style.FILL);}for(int i=0;i<12;i++){double a=Math.PI*2*i/12-Math.PI/2;float x=(float)(cx+Math.cos(a)*(radius-10)),y=(float)(cy+Math.sin(a)*(radius-10));circle(c,x,y,i==0?3:1.4f,i==0?danger:dim);}Calendar now=Calendar.getInstance();double minute=now.get(Calendar.MINUTE)*Math.PI/30-Math.PI/2,hour=(now.get(Calendar.HOUR)%12+now.get(Calendar.MINUTE)/60f)*Math.PI/6-Math.PI/2;hand(c,cx,cy,hour,radius*.43f,8,text);hand(c,cx,cy,minute,radius*.62f,4,dim);circle(c,cx,cy,5,danger);}
    private void hand(Canvas c,float cx,float cy,double a,float len,float width,int color){p.setColor(color);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(cx,cy,(float)(cx+Math.cos(a)*len),(float)(cy+Math.sin(a)*len),p);}
    private void drawAppIcon(Canvas c,App a,float cx,float cy,float size,boolean inverse){
        float radius=size/2;
        int iconBg=inverse?Color.WHITE:palette.iconBackground;
        RectF iconRect=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
        if(a.icon==null){round(c,iconRect,7,iconBg);label(c,a.label.substring(0,1).toUpperCase(Locale.ROOT),cx,cy+6,16,inverse?0xff171717:text,Paint.Align.CENTER,bodyMedium);return;}
        int save=c.save();
        path.reset();path.addRoundRect(iconRect,7,7,Path.Direction.CW);c.clipPath(path);
        round(c,iconRect,7,iconBg);
        try{
            Drawable d=a.icon.mutate();if(palette.monochromeIcons)d.setColorFilter(MONO);else d.clearColorFilter();
            d.setBounds((int)(cx-radius),(int)(cy-radius),(int)(cx+radius),(int)(cy+radius));
            d.draw(c);d.clearColorFilter();
        }catch(RuntimeException ignored){}
        c.restoreToCount(save);
    }
    private int homeAppFill(int index){
        return palette.appTile(index);
    }
    private int activeNavFill(){
        return palette.preset==ThemePalette.GLASS_DARK||palette.preset==ThemePalette.INDUSTRIAL_MONO?panel:focusFill();
    }
    private int activeNavText(){
        return palette.preset==ThemePalette.GLASS_DARK||palette.preset==ThemePalette.INDUSTRIAL_MONO?text:focusText();
    }
    private int buttonFill(String id){
        if("bottom_tv".equals(id))return palette.bottomTile(0);
        if("bottom_media".equals(id))return palette.bottomTile(1);
        if("bottom_custom".equals(id))return palette.bottomTile(2);
        if("bottom_quick".equals(id))return palette.bottomTile(3);
        return panel;
    }
    private void round(Canvas c,RectF r,float radius,int color){boolean surface=color==panel||color==panel2;if(surface&&Color.alpha(palette.shadowColor())>0){p.setStyle(Paint.Style.FILL);p.setColor(palette.shadowColor());c.drawRoundRect(r.left+1.2f,r.top+1.6f,r.right+1.2f,r.bottom+1.6f,radius,radius,p);}p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(r,radius,radius,p);if(surface&&Color.alpha(border)>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(border);c.drawRoundRect(r,radius,radius,p);p.setStyle(Paint.Style.FILL);}}
    private void stroke(Canvas c,RectF r,float radius,int color,float width){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(color);c.drawRoundRect(r,radius,radius,p);p.setStyle(Paint.Style.FILL);}
    private void circle(Canvas c,float x,float y,float radius,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,radius,p);}
    private void line(Canvas c,float x1,float y1,float x2,float y2,float width,int color){p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(color);c.drawLine(x1,y1,x2,y2,p);}
    private void label(Canvas c,String value,float x,float baseline,float size,int color,Paint.Align align,Typeface face){p.setTypeface(face);p.setTextSize(size);p.setTextAlign(align);p.setColor(color);p.setStyle(Paint.Style.FILL);String out=value==null?"":value;if(out.length()>64)out=out.substring(0,61)+"…";c.drawText(out,x,baseline,p);}
    private int focusFill(){return palette.focusFill;} private int focusText(){return palette.focusText;}
    private boolean focused(String id){return id.equals(focusId);} private void add(String id,RectF r,Runnable click,Runnable hold){items.add(new Item(id,new RectF(r),click,hold));}

    // Dot matrix 5x7 renderer for the reference's NDot-style display type.
    private static final Map<Character,String> DOTS=new HashMap<>();
    static {String chars="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/+-.()&";String[] rows={
        "0111010001111111000110001","1111010001111101000111110","0111110000100001000001111","1111010001100011000111110","1111110000111101000011111","1111110000111101000010000","0111110000101111000101111","1000110001111111000110001","1111100100001000010011111","0011100010000101001001100","1000110010111001001010001","1000010000100001000011111","1000111011101011000110001","1000111001101011001110001","0111010001100011000101110","1111010001111101000010000","0111010001101011001001101","1111010001111101001010001","0111110000011100000111110","1111100100001000010000100","1000110001100011000101110","1000110001100010101000100","1000110001101011101110001","1000101010001000101010001","1000101010001000010000100","1111100010001000100011111",
        "0111010001100011000101110","0010001100001000010011111","0111010001000100010011111","1111000001001100000111110","0001000110010101111100010","1111110000111100000111110","0111110000111101000101110","1111100010001000100001000","0111010001011101000101110","0111010001011110000111110","0000000100000000010000000","0010000100000000010000100","0000000100001000100000000","0000000000111110000000000","0000000000000000000000100","0000100010001000100010000","0010000100000000010000100","0011001000010000100000110","0111010001011101010101111"};for(int i=0;i<chars.length();i++)DOTS.put(chars.charAt(i),rows[i]);}
    private void dot(Canvas c,String value,float x,float y,float unit,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);float ox=x;String upper=(value==null?"":value.toUpperCase(Locale.ROOT));for(int k=0;k<upper.length();k++){char ch=upper.charAt(k);if(ch==' '){x+=unit*4;continue;}String bits=DOTS.get(ch);if(bits==null){label(c,String.valueOf(ch),x,y+unit*7,unit*7,color,Paint.Align.LEFT,Typeface.MONOSPACE);x+=unit*6;continue;}for(int i=0;i<35&&i<bits.length();i++)if(bits.charAt(i)=='1'){int col=i%5,row=i/5;c.drawRect(x+col*unit,y+row*unit,x+col*unit+unit*.72f,y+row*unit+unit*.72f,p);}x+=unit*6;if(x>W-20){x=ox;y+=unit*9;}}}

    // ---- Actions and real system state ---------------------------------
    private PrayerTimes prayers(Calendar now){int city=Math.max(0,Math.min(PrayerTimes.CITIES.length-1,prefs.getInt("prayer_city",0)));int method=Math.max(0,Math.min(PrayerTimes.METHODS.length-1,prefs.getInt("prayer_method",0)));return PrayerTimes.calculate(now,PrayerTimes.CITIES[city],method);}
    private void launch(App a){try{Intent i=new Intent(a.launch);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity.startActivity(i);}catch(RuntimeException e){toast("App is not available");}}
    private void options(App a){selectedPackage=a.pkg;show(Screen.APP_OPTIONS,"option_open");}
    private void appInfo(App a){try{activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.pkg)));}catch(RuntimeException e){openSystem(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);}}
    private void toggleFavorite(App a){ArrayList<String> f=favoritePackages();if(f.contains(a.pkg))f.remove(a.pkg);else{if(f.size()>=8)f.remove(f.size()-1);f.add(a.pkg);}saveFavorites(f);invalidate();}
    private void moveFavorite(App a){ArrayList<String> f=favoritePackages();int i=f.indexOf(a.pkg);if(i<0){toggleFavorite(a);return;}Collections.swap(f,i,(i+1)%f.size());saveFavorites(f);invalidate();}
    private ArrayList<String> favoritePackages(){ArrayList<String> f=new ArrayList<>();for(String pkg:prefs.getString("favorites","").split(","))if(!pkg.isEmpty())f.add(pkg);return f;}
    private void saveFavorites(List<String> f){prefs.edit().putString("favorites",TextUtils.join(",",f)).apply();}
    private void hideApp(App a){Set<String> h=hidden();h.add(a.pkg);prefs.edit().putString("hidden",TextUtils.join(",",h)).apply();show(Screen.APPS,"app0");}
    private void toggle(String key,boolean def){prefs.edit().putBoolean(key,!prefs.getBoolean(key,def)).apply();invalidate();}
    private void toggleAdBlocking(){AdBlockingManager.Status status=adBlocking.status();boolean changed=status.state.adGuardSelected?adBlocking.disable():adBlocking.enable();if(!changed&&!status.canWrite)adBlocking.openSupportedSettings();invalidate();}
    private void settingsAction(int n){switch(n){case 0:show(Screen.NETWORK,"network_wifi");break;case 1:show(Screen.AD_BLOCKING,"adblock_toggle");break;case 2:show(Screen.ACCESSORIES,"pair");break;case 3:show(Screen.APP_STORAGE,"storage_apps");break;case 4:show(Screen.PERSONALIZE,"appearance_preset");break;case 5:show(Screen.PICTURE_SOUND,"picture0");break;case 6:openSystem(Settings.ACTION_SETTINGS);break;case 7:openSystem(Settings.ACTION_SYNC_SETTINGS);break;default:openSystem(Settings.ACTION_DEVICE_INFO_SETTINGS);}}
    private void quickAction(int n){switch(n){case 0:show(Screen.NETWORK,"network_wifi");break;case 1:show(Screen.ACCESSORIES,"pair");break;case 2:show(Screen.INPUTS,"input0");break;case 3:openSystem(Settings.ACTION_CAST_SETTINGS);break;case 4:show(Screen.PICTURE_SOUND,"picture0");break;case 5:show(Screen.PICTURE_SOUND,"sound0");break;case 6:openSleepSettings();break;default:openSystem(Settings.ACTION_SETTINGS);}}
    private void openSleepSettings(){openSystem(Settings.ACTION_DREAM_SETTINGS);toast("Sleep and power timers are controlled by TV Settings");}
    private void browseMedia(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");activity.startActivity(i);}catch(RuntimeException e){openSystem(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);}}
    private void openLiveTv(){Intent i=new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);try{activity.startActivity(i);}catch(RuntimeException e){openAction("android.media.tv.action.SETUP_INPUTS");}}
    private void openInput(TvInputInfo info){try{Uri uri=TvContract.buildChannelUriForPassthroughInput(info.getId());Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.cursor.item/channel");activity.startActivity(i);}catch(RuntimeException e){openAction("com.android.tv.action.VIEW_INPUTS");}}
    private void openAction(String action){try{activity.startActivity(new Intent(action));}catch(RuntimeException e){openSystem(Settings.ACTION_SETTINGS);}}
    private void openSystem(String action){try{activity.startActivity(new Intent(action));}catch(ActivityNotFoundException|SecurityException e){try{activity.startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(RuntimeException ignored){toast("This setting is unavailable on the TV");}}}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_SHORT).show();}

    private List<TvInputInfo> tvInputs(){TvInputManager m=(TvInputManager)activity.getSystemService(Context.TV_INPUT_SERVICE);try{return m==null?Collections.emptyList():m.getTvInputList();}catch(RuntimeException e){return Collections.emptyList();}}
    private int inputState(TvInputInfo i){TvInputManager m=(TvInputManager)activity.getSystemService(Context.TV_INPUT_SERVICE);try{return m==null?TvInputManager.INPUT_STATE_DISCONNECTED:m.getInputState(i.getId());}catch(RuntimeException e){return TvInputManager.INPUT_STATE_DISCONNECTED;}}
    private String stateLabel(int state){return state==TvInputManager.INPUT_STATE_CONNECTED?"Connected":state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"Standby":"Unavailable";}
    private String safeInputLabel(TvInputInfo i){try{CharSequence s=i.loadLabel(activity);return s==null?"TV input":s.toString();}catch(RuntimeException e){return "TV input";}}
    private String inputGlyph(TvInputInfo i){return i.getType()==TvInputInfo.TYPE_TUNER?"▣":i.getType()==TvInputInfo.TYPE_HDMI?"↪":"□";}
    private String settingsGlyph(int i){String[] g={"⌁","◇","▧","◉","⠿","≡","⚝","♙","◷"};return g[Math.max(0,Math.min(g.length-1,i))];}
    private String primaryInputLabel(){for(TvInputInfo i:tvInputs())if(inputState(i)==TvInputManager.INPUT_STATE_CONNECTED)return safeInputLabel(i);return tvInputs().isEmpty()?"Inputs":"Select input";}
    private boolean networkConnected(){ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);try{Network n=cm.getActiveNetwork();NetworkCapabilities nc=cm.getNetworkCapabilities(n);return nc!=null&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}catch(RuntimeException e){return false;}}
    private boolean isTransport(int t){ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);try{NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());return nc!=null&&nc.hasTransport(t);}catch(RuntimeException e){return false;}}
    private boolean wifiEnabled(){try{return ((WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();}catch(RuntimeException e){return false;}}
    private String networkSummary(){if(isTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";if(isTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Connected";return networkConnected()?"Connected":"Offline";}
    private String networkGlyph(){return isTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"↔":"⌁";}
    @SuppressWarnings("deprecation") private String networkDetail(){if(isTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";try{WifiInfo wi=((WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();String s=wi==null?null:wi.getSSID();if(s!=null&&!"<unknown ssid>".equalsIgnoreCase(s))return s.replace("\"","");}catch(RuntimeException ignored){}return networkConnected()?"Network connected":"No connection";}
    private String bluetoothState(){try{BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();return a!=null&&a.isEnabled()?"On":"Off";}catch(SecurityException e){return Settings.Global.getInt(activity.getContentResolver(),"bluetooth_on",0)==1?"On":"Off";}}
    private String pictureMode(){int m=Settings.Global.getInt(activity.getContentResolver(),"picture_mode",-1);return m<0?"System":"Mode "+m;}
    private String soundMode(){int m=Settings.Global.getInt(activity.getContentResolver(),"sound_style",-1);return m<0?"System":"Mode "+m;}
    private String globalString(String key,String fallback){String v=Settings.Global.getString(activity.getContentResolver(),key);return v==null?fallback:v;}
    private String globalInt(String key,int fallback,String suffix){int v=Settings.Global.getInt(activity.getContentResolver(),key,fallback);return v<0?"System":v+suffix;}
    private String externalStorageSummary(){for(StorageVolume v:((StorageManager)activity.getSystemService(Context.STORAGE_SERVICE)).getStorageVolumes())if(v.isRemovable())return v.getDescription(activity);return "No USB drive";}
    private String human(long b){float g=b/(1024f*1024f*1024f);return g>=1?String.format(Locale.US,"%.1f GB",g):String.format(Locale.US,"%.0f MB",b/(1024f*1024f));}

    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0){enterDown=System.currentTimeMillis();longHandled=false;}else if(!longHandled&&event.getRepeatCount()>=1){Item i=current();if(i!=null&&i.hold!=null){longHandled=true;i.hold.run();}}return true;}if(code==KeyEvent.KEYCODE_BACK){activity.onBackPressed();return true;}if(code==KeyEvent.KEYCODE_SETTINGS){openQuickSettings();return true;}if(code==KeyEvent.KEYCODE_TV_INPUT||code==KeyEvent.KEYCODE_TV){openInputs();return true;}if(code==KeyEvent.KEYCODE_PROFILE_SWITCH){openSystem(Settings.ACTION_SYNC_SETTINGS);return true;}if(screen==Screen.AMBIENT){show(Screen.HOME,"dock_home");return true;}int dir=code==KeyEvent.KEYCODE_DPAD_LEFT?0:code==KeyEvent.KEYCODE_DPAD_RIGHT?1:code==KeyEvent.KEYCODE_DPAD_UP?2:code==KeyEvent.KEYCODE_DPAD_DOWN?3:-1;if(dir>=0){move(dir);return true;}return super.onKeyDown(code,event);}
    @Override public boolean onKeyUp(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(!longHandled){Item i=current();if(i!=null&&i.click!=null)i.click.run();}return true;}return super.onKeyUp(code,event);}
    private Item current(){for(Item i:items)if(i.id.equals(focusId))return i;return items.isEmpty()?null:items.get(0);}
    private void move(int dir){Item from=current();if(from==null)return;float fx=from.rect.centerX(),fy=from.rect.centerY(),best=Float.MAX_VALUE;Item target=null;for(Item i:items){if(i==from)continue;float dx=i.rect.centerX()-fx,dy=i.rect.centerY()-fy;if((dir==0&&dx>=-1)||(dir==1&&dx<=1)||(dir==2&&dy>=-1)||(dir==3&&dy<=1))continue;float main=(dir<2?Math.abs(dx):Math.abs(dy)),cross=(dir<2?Math.abs(dy):Math.abs(dx));float score=main+cross*8;if(score<best){best=score;target=i;}}if(target!=null){focusId=target.id;invalidate();}}
}
