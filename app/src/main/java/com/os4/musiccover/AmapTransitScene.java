package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德's bus and subway navigation as a page behind the lock screen: ColorOS 17's 公交/地铁逐站
 * card (SceneService's 高德 public-transport card, 536879317), drawn here. On ColorOS 高德 does
 * not draw it either - it hands SceneService the trip as JSON and SceneService builds the card;
 * AmapTransitShare, in 高德's process, takes that JSON instead and sends it here as
 * {@code op transit} (see docs/amap-transit-island.md).
 *
 * What is drawn is what SceneService puts in the card (com.oplus.sdp.ya.b, its BusOrSubwayParser):
 *   - the line, in its own colours, and where it is heading;
 *   - the milestone's line: 下一站 / 当前站 / 准备换乘 / 已到达, with 高德's guideInfo or
 *     「N站 XX下车」 under it;
 *   - three stations - the last, this one, the next - on a track in the line's colour;
 *   - behind it all the landmark the shown station stands near, OPPO's own pictures on its CDN
 *     (AmapTransitLandmarks): while riding only a station within 0.8km of one gets a picture
 *     (com.oplus.sdp.ya.d); on arrival the exit's landmark, else the city's own picture, else the
 *     national one (ya.b.O), by day or by night at that spot. Riding past a landmark-less
 *     station, ColorOS shows none; here the national one stands in, dimmed, so the page keeps a
 *     ground.
 *
 * Ready while 高德's current leg is a bus or a subway and it keeps saying so: a walking leg is
 * AmapNavScene's map, an end (deleteIntent) or STALE_MS of silence closes it. It sits before
 * AmapNavScene in ImmersiveHost.SCENES and claims 高德's island only while it is ready.
 *
 * The ground - the line's colour into black, and the still landmark - is a picture under the
 * shade window (CountdownScene.GroundSurface), for the reason given there: the lock screen's
 * glass rows sample what is behind the window, never a view in it. The animated landmark and the
 * words are a view in the window over it; in the doze the view stops and the still one shows.
 *
 * Probe: {@code op transit} - the state; {@code --es json '<intentEntity>'} feeds one in by hand,
 * {@code --ez demo true} a made-up ride past 天安门 on Beijing's line 1, {@code --es do end} ends it.
 */
final class AmapTransitScene implements ImmersiveScene {

    static final String ID = "amap-transit";
    static final String PKG = AmapNavScene.PKG;
    static final AmapTransitScene INSTANCE = new AmapTransitScene();

    private static final String TAG = "MCImmersive: " + ID + ": ";

    /**
     * How long a trip is believed without another word from 高德. It says something at every
     * milestone and AmapTransitShare repeats the last every minute, so ten minutes of silence is
     * a 高德 that has gone, not a long ride between two stations.
     */
    private static final long STALE_MS = 10L * 60_000L;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private Trip mTrip;
    private Frame mFrame;
    private long mTripAt;
    private int mShares;
    private String mEndedBy;

    private TransitView mView;
    private CountdownScene.GroundSurface mGround;
    private boolean mShown;
    private boolean mDozing;

    private AmapTransitScene() {
    }

    // ---------------------------------------------------------------- what 高德 says

    /** {@code op transit}, any thread. The answer is the state as it is now. */
    String command(String json, String what, boolean demo) {
        if (demo) json = DEMO;
        if (json != null) {
            final Trip t;
            try {
                t = Trip.parse(new JSONObject(json));
            } catch (Throwable e) {
                Xp.log(TAG + "unreadable trip: " + e);
                return "unreadable: " + e + "\n" + describe();
            }
            mMain.post(() -> take(t));
        } else if ("end".equals(what)) {
            mMain.post(() -> end("高德"));
        }
        return describe();
    }

    private void take(Trip t) {
        boolean was = ready();
        mShares++;
        mTrip = t;
        mTripAt = SystemClock.uptimeMillis();
        mEndedBy = null;
        mMain.removeCallbacks(mStale);
        mMain.postDelayed(mStale, STALE_MS);
        Frame f = Frame.of(t);
        boolean newPicture = mFrame == null || !mFrame.sameArt(f);
        mFrame = f;
        Xp.log(TAG + "status=" + t.status + " type=" + t.leg.type + " line=" + t.leg.lineName
                + " -> " + f.primary + " | " + f.secondary + " art=" + f.art);
        if (mView != null) {
            mView.setFrame(f);
            if (newPicture) Art.request(mView.getContext(), f.art, this::onArt);
            if (mDozing) ImmersiveHost.lift(mView);
        }
        if (was != ready()) ImmersiveHost.readyChanged(this);
    }

    private final Runnable mStale = () -> end("silence");

    private void end(String by) {
        mMain.removeCallbacks(mStale);
        if (mTrip == null) return;
        boolean was = ready();
        mTrip = null;
        mFrame = null;
        mEndedBy = by;
        Xp.log(TAG + "ended by " + by);
        if (was) ImmersiveHost.readyChanged(this);
    }

    /** The pictures for the frame they were asked for, once they are here; main thread. */
    private void onArt(Art.Set set) {
        Frame f = mFrame;
        if (f == null || !f.art.equals(set.pick)) return;
        if (mView != null) mView.setArt(set);
        if (mGround != null) paintGround();
    }

    // ---------------------------------------------------------------- the scene

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean serves(String pkg, boolean focus) {
        return focus && PKG.equals(pkg);
    }

    /** 高德's island is this page's only while there is a ride to show; the map's otherwise. */
    @Override
    public boolean servesKey(String key) {
        return ready();
    }

    @Override
    public boolean ready() {
        Trip t = mTrip;
        return t != null && t.leg.rides();
    }

    @Override
    public void prepare(ViewGroup slot) {
        if (mView != null) return;
        Context ctx = slot.getContext();
        TransitView v = new TransitView(ctx);
        v.setVisibility(View.INVISIBLE);
        CountdownScene.GroundSurface g = new CountdownScene.GroundSurface(ctx);
        g.setVisibility(View.INVISIBLE);
        slot.addView(g, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slot.addView(v, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mView = v;
        mGround = g;
        Frame f = mFrame;
        if (f != null) {
            v.setFrame(f);
            Art.request(ctx, f.art, this::onArt);
        }
        Xp.log(TAG + "page made");
        mMain.post(() -> {
            if (mView == v) ImmersiveHost.contentChanged(this);
        });
    }

    @Override
    public boolean hasContent() {
        return mView != null;
    }

    @Override
    public void onShown(boolean shown, boolean dozing) {
        TransitView v = mView;
        if (v == null) return;
        if (shown != mShown) {
            v.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
            if (mGround != null) mGround.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        }
        mShown = shown;
        boolean was = mDozing;
        mDozing = dozing;
        v.setLive(shown && !dozing);
        if (was != dozing) paintGround();
    }

    @Override
    public void setFade(float alpha) {
        if (mView != null) mView.setFade(alpha);
        if (mGround != null) mGround.setFade(alpha);
    }

    @Override
    public void release() {
        TransitView v = mView;
        if (v == null) return;
        mView = null;
        mShown = false;
        v.setLive(false);
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
        CountdownScene.GroundSurface g = mGround;
        mGround = null;
        if (g != null && g.getParent() instanceof ViewGroup) ((ViewGroup) g.getParent()).removeView(g);
        Xp.log(TAG + "released");
        ImmersiveHost.contentChanged(this);
    }

    /** Still, apart from the landmark, which is stopped in the doze. */
    @Override
    public boolean needsDozeBeat() {
        return false;
    }

    /** The page is its own ground; nothing to blur. */
    @Override
    public float[] sharpBand() {
        return null;
    }

    @Override
    public String describe() {
        Trip t = mTrip;
        Frame f = mFrame;
        StringBuilder sb = new StringBuilder();
        sb.append("shares=").append(mShares);
        if (t == null) {
            sb.append(" no trip").append(mEndedBy == null ? "" : " (ended by " + mEndedBy + ")");
        } else {
            sb.append(" status=").append(t.status).append(" type=").append(t.leg.type)
                    .append(" line=").append(t.leg.lineName).append(" remain=").append(t.leg.remain)
                    .append(" city=").append(t.cityCode)
                    .append(" age=").append(SystemClock.uptimeMillis() - mTripAt).append("ms");
        }
        if (f != null) sb.append(" [").append(f.primary).append(" | ").append(f.secondary)
                .append("] art=").append(f.art);
        sb.append(" shown=").append(mShown).append(" dozing=").append(mDozing);
        if (mView != null) sb.append(' ').append(mView.describe());
        return sb.toString();
    }

    /**
     * The ground under the window: the line's colour into black, with the still landmark on it
     * when the view is not drawing the moving one over it - the doze, or no moving one to draw.
     */
    private void paintGround() {
        CountdownScene.GroundSurface g = mGround;
        TransitView v = mView;
        Frame f = mFrame;
        if (g == null || v == null || f == null) return;
        int w = v.getResources().getDisplayMetrics().widthPixels;
        int h = v.getResources().getDisplayMetrics().heightPixels;
        boolean still = mDozing || !v.animating();
        g.setBitmap(Ground.make(w, h, f, still ? v.stillArt() : null));
    }

    // ---------------------------------------------------------------- the trip

    /** One station: its name and, when 高德 gave one, where it is. */
    static final class Station {
        final String name;
        final double lat;
        final double lng;
        final boolean transfer;

        Station(String name, double lat, double lng, boolean transfer) {
            this.name = name == null ? "" : name.trim();
            this.lat = lat;
            this.lng = lng;
            this.transfer = transfer;
        }

        boolean located() {
            return AmapTransitLandmarks.valid(lat, lng);
        }

        static Station of(JSONObject o, String nameKey) {
            if (o == null) return new Station("", 0, 0, false);
            JSONObject c = o.optJSONObject("coord");
            return new Station(o.optString(nameKey),
                    c == null ? 0 : c.optDouble("lat", 0), c == null ? 0 : c.optDouble("lng", 0),
                    o.optBoolean("isTransferStation", false));
        }
    }

    /** One leg of the trip: 高德's naviInfo item, the fields the page reads. */
    static final class Leg {
        /** GaoDeNaviSegmentTransportType: 0 walk, 1 bus, 2 subway, 12 ferry, 13 cable car... */
        String type = "";
        String lineName = "";
        String lineDirection = "";
        int lineBg = 0xff4a86ff;
        int lineText = Color.WHITE;
        int remain;
        Station on = new Station("", 0, 0, false);
        Station off = new Station("", 0, 0, false);
        final List<Station> via = new ArrayList<>();
        final List<Station> ports = new ArrayList<>();
        /** The first real-time arrival's words: 「列车预计 3 分钟进站」. */
        String realtime = "";

        boolean subway() {
            return "2".equals(type);
        }

        /** A bus or a subway: the legs this page is for. */
        boolean rides() {
            return "1".equals(type) || "2".equals(type);
        }

        static Leg of(JSONObject o) {
            Leg l = new Leg();
            if (o == null) return l;
            l.type = o.optString("transportType").trim();
            l.lineName = o.optString("lineName").trim();
            l.lineDirection = o.optString("lineDirection").trim();
            l.lineBg = color(o.optString("lineBgColor"), 0xff4a86ff);
            l.lineText = color(o.optString("lineTextColor"), Color.WHITE);
            l.remain = o.optInt("remainStations", 0);
            JSONObject on = o.optJSONObject("on_station");
            l.on = Station.of(on, "stationName");
            JSONObject off = o.optJSONObject("off_station");
            l.off = Station.of(off, "stationName");
            JSONArray via = o.optJSONArray("via_st_list");
            for (int i = 0; via != null && i < via.length(); i++) {
                l.via.add(Station.of(via.optJSONObject(i), "name"));
            }
            JSONArray ports = off == null ? null : off.optJSONArray("port_list");
            for (int i = 0; ports != null && i < ports.length(); i++) {
                JSONObject p = ports.optJSONObject(i);
                if (p == null) continue;
                Station s = Station.of(p, "name");
                l.ports.add(s);
                String shield = p.optString("shield").trim();
                if (!shield.isEmpty()) l.ports.add(new Station(shield, s.lat, s.lng, false));
            }
            JSONObject wait = on == null ? null : on.optJSONObject("waitInfo");
            JSONArray rt = wait == null ? null : wait.optJSONArray("realTime");
            JSONObject first = rt == null ? null : rt.optJSONObject(0);
            if (first != null) l.realtime = first.optString("mainTitle").trim();
            return l;
        }
    }

    /** 高德's GaoDePtIntentEntity, the parts the page reads. */
    static final class Trip {
        /** GaoDePublicTransportNavMilestone: 1 near the origin ... 7 arrived. */
        String status = "";
        String cityCode = "";
        String destStation = "";
        double destLat;
        double destLng;
        String exitName = "";
        String guideInfo = "";
        String deepLink = "";
        Leg leg = new Leg();
        /** The ride you change to next, for the transfer badge; empty when this is the last. */
        String nextLine = "";
        int nextLineColor = 0xff4a86ff;
        boolean nextSubway;

        static Trip parse(JSONObject o) {
            Trip t = new Trip();
            t.status = o.optString("status").trim();
            t.cityCode = o.optString("destCitycode").trim();
            t.destStation = o.optString("destStation").trim();
            t.destLat = o.optDouble("destLatitude", 0);
            t.destLng = o.optDouble("destLongitude", 0);
            t.exitName = o.optString("exitName").trim();
            t.guideInfo = o.optString("guideInfo").trim();
            t.deepLink = o.optString("deepLink").trim();
            JSONArray navi = o.optJSONArray("naviInfo");
            int curIndex = -1;
            for (int i = 0; navi != null && i < navi.length(); i++) {
                JSONObject n = navi.optJSONObject(i);
                if (n != null && n.optBoolean("isCurrent", false)) {
                    curIndex = i;
                    break;
                }
            }
            // No leg marked current: the first ride, as SceneService's g.b falls back.
            for (int i = 0; curIndex < 0 && navi != null && i < navi.length(); i++) {
                JSONObject n = navi.optJSONObject(i);
                String type = n == null ? "" : n.optString("transportType").trim();
                if ("1".equals(type) || "2".equals(type)) curIndex = i;
            }
            t.leg = Leg.of(curIndex < 0 ? null : navi.optJSONObject(curIndex));
            // The next ride after this one: the line the transfer badge names.
            for (int i = curIndex + 1; navi != null && i < navi.length(); i++) {
                JSONObject n = navi.optJSONObject(i);
                String type = n == null ? "" : n.optString("transportType").trim();
                if ("1".equals(type) || "2".equals(type)) {
                    t.nextLine = n.optString("lineName").trim();
                    t.nextLineColor = color(n.optString("lineBgColor"), 0xff4a86ff);
                    t.nextSubway = "2".equals(type);
                    break;
                }
            }
            return t;
        }

        /** The line's number for a badge: 「地铁1号线」 -> 「1」, 「机场线」 -> 「机场」 (ya.k.c). */
        String nextLineCode() {
            String s = nextLine;
            if (s.isEmpty()) return "";
            s = s.replace("地铁", "");
            int zh = s.indexOf("号线");
            if (zh >= 0) return s.substring(0, zh);
            if (s.endsWith("线")) return s.substring(0, s.length() - 1);
            if (s.endsWith("路")) return s.substring(0, s.length() - 1);
            return s;
        }
    }

    // ---------------------------------------------------------------- what is shown

    /**
     * The three stations on the track, OPPO's cardStationOverview (ya.k.b): the one before, this
     * one, the next, which of them the train is at or heading for, and the transfer line each
     * stands for (a square badge in that line's colour, as 五一路 shows a green 5).
     */
    static final class Nodes {
        final String[] names = new String[3];
        final boolean[] transfer = new boolean[3];
        /** The line you change to at a transfer node, its number; null where there is none. */
        final String[] badge = new String[3];
        final int[] badgeColor = new int[3];
        /** 0 the train is at the middle node, 1 it is on its way to the next. */
        final int focus;

        Nodes(Station[] st, int focus, String nextCode, int nextColor) {
            this.focus = focus;
            for (int i = 0; i < 3; i++) {
                Station s = st[i] == null ? new Station("", 0, 0, false) : st[i];
                names[i] = s.name;
                transfer[i] = s.transfer;
                if (s.transfer && !nextCode.isEmpty()) {
                    badge[i] = nextCode;
                    badgeColor[i] = nextColor;
                }
            }
        }
    }

    /** One state of the page, worked out from a trip. */
    static final class Frame {
        String line = "";
        String direction = "";
        int lineBg;
        int lineText;
        String primary = "";
        String secondary = "";
        Nodes nodes;
        boolean subway;
        Art.Pick art = Art.Pick.NONE;

        boolean sameArt(Frame o) {
            return art.equals(o.art);
        }

        static Frame of(Trip t) {
            Frame f = new Frame();
            Leg l = t.leg;
            f.subway = l.subway();
            f.line = l.lineName;
            f.direction = direction(l.lineDirection);
            f.lineBg = l.lineBg;
            f.lineText = l.lineText;
            int index = Math.max(0, Math.min(l.via.size() - l.remain, l.via.size() - 1));
            String shown = shownStation(t, index);
            switch (t.status) {
                case "1":
                case "2":
                    // The waiting card: the boarding station, and when the train comes.
                    f.primary = l.on.name;
                    f.secondary = !l.realtime.isEmpty() ? l.realtime : f.direction;
                    f.nodes = nodes(t, index, 1);
                    break;
                case "3":
                case "4":
                    f.primary = "下一站 " + shown;
                    f.secondary = remaining(t);
                    f.nodes = nodes(t, index, 1);
                    break;
                case "5":
                    f.primary = "当前站 " + shown;
                    f.secondary = remaining(t);
                    f.nodes = nodes(t, index, 0);
                    break;
                case "6":
                    f.primary = "准备换乘";
                    f.secondary = t.nextLine.isEmpty() ? (shown.isEmpty() ? "" : "已到达 " + shown)
                            : "可换乘" + t.nextLine;
                    f.nodes = nodes(t, index, 0);
                    break;
                case "7":
                    f.primary = shown.isEmpty() ? "已到达" : "已到达 " + shown;
                    f.secondary = t.exitName.isEmpty() ? t.guideInfo : t.exitName + " 出站";
                    break;
                default:
                    f.primary = l.lineName;
                    f.secondary = t.guideInfo;
                    break;
            }
            f.art = Art.Pick.of(t, shown, index);
            return f;
        }

        /** 「往XX」, unless 高德 already said which way. */
        private static String direction(String d) {
            if (d.isEmpty() || d.startsWith("往") || d.startsWith("开往") || d.endsWith("方向")) {
                return d;
            }
            return "往" + d;
        }

        /** 高德's guideInfo, else 「N站 XX下车」 (SceneService's ya.b.Y). */
        private static String remaining(Trip t) {
            if (!t.guideInfo.isEmpty()) return t.guideInfo;
            int n = t.leg.remain;
            if (n <= 0) return "";
            return t.leg.off.name.isEmpty() ? n + "站后下车" : n + "站 " + t.leg.off.name + "下车";
        }

        /** The station a milestone names (SceneService's ya.b.e0). */
        private static String shownStation(Trip t, int index) {
            Leg l = t.leg;
            switch (t.status) {
                case "3": {
                    // The next station: past the via list, the one you get off at.
                    if (l.via.isEmpty()) return l.off.name.isEmpty() ? l.on.name : l.off.name;
                    String here = l.via.get(index).name;
                    String next = index + 1 < l.via.size() ? l.via.get(index + 1).name : l.off.name;
                    if (l.remain > l.via.size()) return here.isEmpty() ? l.on.name : here;
                    if (!next.isEmpty() && !next.equals(here)) return next;
                    return !l.off.name.isEmpty() ? l.off.name : here.isEmpty() ? l.on.name : here;
                }
                case "5": {
                    if (l.via.isEmpty()) return l.on.name.isEmpty() ? l.off.name : l.on.name;
                    String here = l.via.get(index).name;
                    return here.isEmpty() ? l.on.name : here;
                }
                case "7":
                    return !l.off.name.isEmpty() ? l.off.name : t.destStation;
                default:
                    return !l.off.name.isEmpty() ? l.off.name : l.on.name;
            }
        }

        /** SceneService's ThreeNodeStations (ya.e.a): the one before, this one, the next. */
        private static Nodes nodes(Trip t, int index, int focus) {
            Leg l = t.leg;
            String code = t.nextLineCode();
            Station prev, here, next;
            if (l.via.isEmpty()) {
                prev = l.on;
                here = l.off;
                next = l.off;
            } else {
                here = l.via.get(index);
                prev = index == 0 ? l.on : l.via.get(index - 1);
                next = index + 1 < l.via.size() ? l.via.get(index + 1) : l.off;
            }
            return new Nodes(new Station[] {prev, here, next}, focus, code, t.nextLineColor);
        }
    }

    // ---------------------------------------------------------------- the pictures

    /** Which pictures, and fetching them: on a thread of their own, kept on disk. */
    static final class Art {

        private Art() {
        }

        /** What to show, as addresses: the first of [ground] that arrives, and [label]. */
        static final class Pick {
            static final Pick NONE = new Pick(new String[0], null, false, "none");

            /** Animated first where there is one; then stills, each a fallback for the last. */
            final String[] ground;
            /** The landmark's name, white, for under it; null without a landmark. */
            final String label;
            /** A default rather than a landmark: drawn dimmer. */
            final boolean fallback;
            private final String why;

            Pick(String[] ground, String label, boolean fallback, String why) {
                this.ground = ground;
                this.label = label;
                this.fallback = fallback;
                this.why = why;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Pick)) return false;
                Pick p = (Pick) o;
                return java.util.Arrays.equals(ground, p.ground) && TextUtils.equals(label, p.label);
            }

            @Override
            public int hashCode() {
                return java.util.Arrays.hashCode(ground);
            }

            @Override
            public String toString() {
                return why;
            }

            /** SceneService's choice (ya.d.a riding, ya.b.O arriving), for a trip. */
            static Pick of(Trip t, String shown, int index) {
                Leg l = t.leg;
                boolean riding = "3".equals(t.status) || "4".equals(t.status) || "5".equals(t.status);
                if (riding) {
                    Station at = locate(l, shown);
                    AmapTransitLandmarks.Match m = at == null ? null
                            : AmapTransitLandmarks.near(t.cityCode, at.lat, at.lng);
                    if (m != null) {
                        return new Pick(new String[] {m.animatedUrl(), m.stillUrl()}, m.labelUrl(),
                                false, "landmark " + m + " at " + shown);
                    }
                    double[] p = at != null ? new double[] {at.lat, at.lng} : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    return new Pick(new String[] {
                            AmapTransitLandmarks.nationalDefaultUrl(l.subway(), night)},
                            null, true, "no landmark at " + shown + (night ? ", night" : ""));
                }
                if ("7".equals(t.status)) {
                    Station exit = exit(t);
                    double[] p = exit != null ? new double[] {exit.lat, exit.lng} : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    List<String> urls = new ArrayList<>();
                    String label = null;
                    AmapTransitLandmarks.Match m =
                            AmapTransitLandmarks.near(t.cityCode, p[0], p[1]);
                    if (m != null) {
                        urls.add(m.animatedUrl());
                        urls.add(m.stillUrl());
                        label = m.labelUrl();
                    }
                    // A subway arrival falls back to the city's picture, a bus straight to the
                    // nation's (PublicTransportDestinationImageUtil.j / .n).
                    String folder = AmapTransitLandmarks.folder(t.cityCode);
                    if (l.subway() && folder != null) {
                        urls.add(AmapTransitLandmarks.cityDefaultUrl(folder, night));
                    }
                    urls.add(AmapTransitLandmarks.nationalDefaultUrl(l.subway(), night));
                    return new Pick(urls.toArray(new String[0]), label, m == null,
                            (m == null ? "arrival, default" : "arrival, landmark " + m)
                                    + (night ? ", night" : ""));
                }
                double[] p = where(t);
                boolean night = night(p[0], p[1], System.currentTimeMillis());
                return new Pick(new String[] {
                        AmapTransitLandmarks.nationalDefaultUrl(l.subway(), night)},
                        null, true, "waiting" + (night ? ", night" : ""));
            }

            /** The shown station's spot: the via station of that name, else the one you get off at. */
            private static Station locate(Leg l, String name) {
                for (Station s : l.via) {
                    if (s.located() && s.name.equals(name)) return s;
                }
                if (l.off.located() && l.off.name.equals(name)) return l.off;
                return null;
            }

            /** The exit 高德 sends you to, else the station (ya.b.N). */
            private static Station exit(Trip t) {
                Leg l = t.leg;
                if (!t.exitName.isEmpty()) {
                    for (Station p : l.ports) {
                        if (p.located() && (p.name.equalsIgnoreCase(t.exitName)
                                || p.name.contains(t.exitName))) {
                            return p;
                        }
                    }
                }
                if (l.off.located()) return l.off;
                return null;
            }

            /** Somewhere on the trip, for whether it is night there. */
            private static double[] where(Trip t) {
                Leg l = t.leg;
                if (l.off.located()) return new double[] {l.off.lat, l.off.lng};
                for (Station s : l.via) {
                    if (s.located()) return new double[] {s.lat, s.lng};
                }
                if (AmapTransitLandmarks.valid(t.destLat, t.destLng)) {
                    return new double[] {t.destLat, t.destLng};
                }
                return new double[] {39.9, 116.4};
            }
        }

        /** What arrived for a pick. */
        static final class Set {
            final Pick pick;
            final Drawable ground;
            /** The still the animated one was made from, for the ground under the window. */
            final Bitmap still;
            final Drawable label;

            Set(Pick pick, Drawable ground, Bitmap still, Drawable label) {
                this.pick = pick;
                this.ground = ground;
                this.still = still;
                this.label = label;
            }
        }

        interface Done {
            void done(Set set);
        }

        private static final ExecutorService IO =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "mc-transit-art"));
        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        /** Fetches [pick] and hands it to [done] on the main thread; nothing if nothing came. */
        static void request(Context ctx, Pick pick, Done done) {
            if (pick == null || pick.ground.length == 0) return;
            final File dir = new File(ctx.getCacheDir(), "mc-transit");
            IO.execute(() -> {
                Drawable ground = null;
                Bitmap still = null;
                for (String url : pick.ground) {
                    byte[] b = fetch(dir, url);
                    if (b == null) continue;
                    ground = drawable(b);
                    if (ground == null) continue;
                    still = bitmap(b);
                    // An animated landmark's still is its .png beside it, for the ground.
                    if (ground instanceof AnimatedImageDrawable && url.endsWith(".webp")) {
                        byte[] s = fetch(dir, url.substring(0, url.length() - 5) + ".png");
                        Bitmap sb = s == null ? null : bitmap(s);
                        if (sb != null) still = sb;
                    }
                    break;
                }
                Drawable label = null;
                if (pick.label != null) {
                    byte[] b = fetch(dir, pick.label);
                    if (b != null) label = drawable(b);
                }
                if (ground == null) {
                    Xp.log(TAG + "no picture for " + pick);
                    return;
                }
                final Set set = new Set(pick, ground, still, label);
                MAIN.post(() -> done.done(set));
            });
        }

        /** From the disk if it has been fetched before, else from OPPO's CDN, kept. */
        private static byte[] fetch(File dir, String url) {
            File f = new File(dir, url.substring(AmapTransitLandmarks.CDN.length()).replace('/', '_'));
            try {
                if (f.isFile() && f.length() > 0) return java.nio.file.Files.readAllBytes(f.toPath());
            } catch (Throwable ignored) {
            }
            Http.Raw r = Http.request(url, "transit", null);
            if (!r.ok()) return null;
            try {
                if (dir.isDirectory() || dir.mkdirs()) {
                    File tmp = new File(dir, f.getName() + ".part");
                    java.nio.file.Files.write(tmp.toPath(), r.body);
                    if (!tmp.renameTo(f)) tmp.delete();
                }
            } catch (Throwable t) {
                Xp.log(TAG + "not kept: " + t);
            }
            return r.body;
        }

        private static Drawable drawable(byte[] b) {
            try {
                return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(b)));
            } catch (Throwable t) {
                Xp.log(TAG + "undecodable picture: " + t);
                return null;
            }
        }

        private static Bitmap bitmap(byte[] b) {
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b)),
                        (d, info, src) -> d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Whether the sun is down at a spot: its altitude under -0.833 degrees, the sunset line
     * (SceneService decides day and night pictures by sunrise and sunset there, pa.f).
     */
    static boolean night(double lat, double lng, long now) {
        double d = now / 86400000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0);
        double q = (280.459 + 0.98564736 * d) % 360.0;
        double lon = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(lon), Math.cos(lon));
        double dec = Math.asin(Math.sin(e) * Math.sin(lon));
        double gmst = (18.697374558 + 24.06570982441908 * d) % 24.0;
        double ha = Math.toRadians(gmst * 15.0 + lng) - ra;
        double phi = Math.toRadians(lat);
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec)
                + Math.cos(phi) * Math.cos(dec) * Math.cos(ha));
        return Math.toDegrees(alt) < -0.833;
    }

    // ---------------------------------------------------------------- where things go

    /**
     * The page's layout, as shares of the screen: the words under the small clock, the landmark
     * in the middle, the track under it, all inside the band ColorOS keeps an immersive page's
     * information in (0.231 to 0.703 of the height, LiveAlertScene.INFO_*), clear of the clock
     * above and the rows below.
     */
    private static final float WORDS_TOP = 0.235f;
    private static final float ART_CENTRE = 0.505f;
    private static final float TRACK_Y = 0.665f;
    /** OPPO's landmark pictures are 807x378. */
    private static final float ART_ASPECT = 378f / 807f;
    private static final float LABEL_ASPECT = 66f / 423f;

    /** The ground: the line's colour at the top into near-black, and the still landmark. */
    static final class Ground {
        private Ground() {
        }

        private static final int BOTTOM = 0xff07080b;

        /** At half the screen's size: it is a soft gradient, and the still is shown under a veil. */
        static Bitmap make(int w, int h, Frame f, Bitmap still) {
            int bw = Math.max(1, w / 2);
            int bh = Math.max(1, h / 2);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, bh,
                    new int[] {blend(f.lineBg, BOTTOM, 0.62f), blend(f.lineBg, BOTTOM, 0.86f), BOTTOM},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            if (still != null) {
                RectF r = artRect(bw, bh);
                Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                ip.setAlpha(f.art.fallback ? FALLBACK_ALPHA : 255);
                c.drawBitmap(still, null, r, ip);
            }
            return b;
        }
    }

    /** A default rather than a landmark stays in the background. */
    private static final int FALLBACK_ALPHA = 110;

    static RectF artRect(float w, float h) {
        float side = w * 0.04f;
        float aw = w - 2f * side;
        float ah = aw * ART_ASPECT;
        float cy = h * ART_CENTRE;
        return new RectF(side, cy - ah / 2f, side + aw, cy + ah / 2f);
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(255, r, g, bl);
    }

    static int color(String s, int fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty()) return fallback;
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * The track, for the page and for the island's card alike (AmapTransitIsland): it reaches
     * TOP above the bar's line and BOTTOM below it, the badge over a transfer stop and the names.
     */
    static final class Track {
        static final float TOP_DP = 33f;
        static final float BOTTOM_DP = 36f;

        final float dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint node = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Track(float dp) {
            this.dp = dp;
            node.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            node.setTextAlign(Paint.Align.CENTER);
        }

        /**
         * The three stops, OPPO-style (五一路): a bar in the line's colour, the part behind the
         * train full and the part ahead dim; the middle stop the current one; a transfer stop a
         * ⇄ ring with a square line-number badge over it, in that line's colour; the names below.
         */
        void draw(Canvas canvas, Frame f, float left, float right, float y) {
            Nodes n = f.nodes;
            float mid = (left + right) / 2f;
            float[] xs = {left, mid, right};
            // Where the train is: on the middle stop, or between it and the next.
            float trainX = n.focus == 0 ? mid : (mid + right) / 2f;
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeWidth(5f * dp);
            fill.setColor(f.lineBg);
            canvas.drawLine(left, y, trainX, y, fill);
            fill.setColor(0x33ffffff);
            canvas.drawLine(trainX, y, right, y, fill);
            node.setTextSize(13f * dp);
            Paint.FontMetrics fn = node.getFontMetrics();
            float slot = (right - left) / 2f - 6f * dp;
            for (int i = 0; i < 3; i++) {
                boolean passed = xs[i] <= trainX + 0.5f;
                boolean current = i == 1;
                int tint = passed || current ? f.lineBg : 0xff4a4d55;
                if (n.transfer[i]) {
                    // A transfer stop: a white-ringed pill with the interchange arrows.
                    float rw = 15f * dp;
                    float rh = 11f * dp;
                    rect.set(xs[i] - rw, y - rh, xs[i] + rw, y + rh);
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(tint);
                    canvas.drawRoundRect(rect, rh, rh, fill);
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(1.6f * dp);
                    fill.setColor(0xffffffff);
                    canvas.drawRoundRect(rect, rh, rh, fill);
                    fill.setStyle(Paint.Style.FILL);
                    drawTransferGlyph(canvas, xs[i], y, 6f * dp);
                } else {
                    float r = current ? 6.5f : 5f;
                    fill.setColor(tint);
                    canvas.drawCircle(xs[i], y, r * dp, fill);
                    fill.setColor(Color.WHITE);
                    canvas.drawCircle(xs[i], y, r * 0.42f * dp, fill);
                }
                // The line-number badge over a transfer stop.
                if (n.badge[i] != null) drawBadge(canvas, n.badge[i], n.badgeColor[i], xs[i], y - 15f * dp);
                // The name below.
                node.setColor(current ? 0xf2ffffff : 0x99ffffff);
                node.setFakeBoldText(current);
                String name = TextUtils.ellipsize(n.names[i] == null ? "" : n.names[i], node, slot,
                        TextUtils.TruncateAt.END).toString();
                canvas.drawText(name, xs[i], y + 16f * dp - fn.top, node);
            }
            fill.setStrokeWidth(5f * dp);
        }

        /** The interchange arrows (⇄) at a transfer stop, white. */
        private void drawTransferGlyph(Canvas canvas, float cx, float cy, float s) {
            fill.setColor(0xffffffff);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(1.4f * dp);
            float g = 2.4f * dp;
            canvas.drawLine(cx - s, cy - g, cx + s * 0.6f, cy - g, fill);
            canvas.drawLine(cx + s * 0.6f, cy - g - 2f * dp, cx + s, cy - g, fill);
            canvas.drawLine(cx + s * 0.6f, cy - g + 2f * dp, cx + s, cy - g, fill);
            canvas.drawLine(cx + s, cy + g, cx - s * 0.6f, cy + g, fill);
            canvas.drawLine(cx - s * 0.6f, cy + g - 2f * dp, cx - s, cy + g, fill);
            canvas.drawLine(cx - s * 0.6f, cy + g + 2f * dp, cx - s, cy + g, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        /** A line-number square in its colour, white number: 五一路's green 5, blue 2. */
        private void drawBadge(Canvas canvas, String code, int color, float cx, float bottom) {
            node.setTextSize(11f * dp);
            node.setFakeBoldText(true);
            node.setColor(0xffffffff);
            float tw = node.measureText(code);
            float pad = 4f * dp;
            float bw = Math.max(16f * dp, tw + 2f * pad);
            float bh = 16f * dp;
            rect.set(cx - bw / 2f, bottom - bh, cx + bw / 2f, bottom);
            fill.setColor(color);
            canvas.drawRoundRect(rect, 4f * dp, 4f * dp, fill);
            Paint.FontMetrics fm = node.getFontMetrics();
            canvas.drawText(code, cx, rect.centerY() - (fm.ascent + fm.descent) / 2f, node);
            node.setFakeBoldText(false);
        }
    }

    // ---------------------------------------------------------------- the page

    /** The words, the track and the moving landmark, over the ground. */
    final class TransitView extends View {

        private final float mDp;
        private final TextPaint mPrimary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mSecondary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mLine = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mDirection = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Track mTrack;

        private Frame mF;
        private Drawable mArt;
        private Bitmap mStill;
        private Drawable mLabel;
        private boolean mLive;

        TransitView(Context ctx) {
            super(ctx);
            mDp = ctx.getResources().getDisplayMetrics().density;
            Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            Typeface plain = Typeface.create("sans-serif", Typeface.NORMAL);
            mPrimary.setTypeface(bold);
            mPrimary.setColor(0xf2ffffff);
            mPrimary.setTextAlign(Paint.Align.CENTER);
            mSecondary.setTypeface(plain);
            mSecondary.setColor(0xb3ffffff);
            mSecondary.setTextAlign(Paint.Align.CENTER);
            mLine.setTypeface(bold);
            mDirection.setTypeface(plain);
            mDirection.setColor(0xccffffff);
            mTrack = new Track(mDp);
        }

        void setFrame(Frame f) {
            mF = f;
            invalidate();
        }

        void setArt(Art.Set set) {
            stopArt();
            if (mArt != null) mArt.setCallback(null);
            mArt = set.ground;
            mStill = set.still;
            mLabel = set.label;
            if (mArt != null) mArt.setCallback(this);
            startArt();
            invalidate();
        }

        Bitmap stillArt() {
            return mStill;
        }

        boolean animating() {
            return mLive && mArt instanceof AnimatedImageDrawable;
        }

        /** On the lit lock screen and shown: the landmark moves. */
        void setLive(boolean live) {
            if (mLive == live) return;
            mLive = live;
            if (live) startArt();
            else stopArt();
            invalidate();
        }

        private void startArt() {
            if (mLive && mArt instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable a = (AnimatedImageDrawable) mArt;
                a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                a.start();
            }
            paintGround();
        }

        private void stopArt() {
            if (mArt instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) mArt).stop();
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == mArt || super.verifyDrawable(who);
        }

        @Override
        protected void onDetachedFromWindow() {
            stopArt();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            paintGround();
        }

        String describe() {
            return "art=" + (mArt == null ? "-" : mArt.getClass().getSimpleName())
                    + " label=" + (mLabel != null) + " live=" + mLive;
        }

        // ------------------------------------------------------------ fading

        /** The plugin's spring for a page coming in (PageSpring); no bounce, as the map has. */
        private static final float ENTER_SCALE = 1.06f;
        private static final float SCALE_RESPONSE = 0.45f;

        private float mLastFade = 1f;
        private float mScaleTo = 1f;
        private android.animation.ValueAnimator mScaleAnim;

        void setFade(float alpha) {
            float last = mLastFade;
            mLastFade = alpha;
            setAlpha(alpha);
            if (alpha <= 0f) {
                stopScale();
                setScale(ENTER_SCALE);
                mScaleTo = ENTER_SCALE;
            } else if (alpha >= 1f && last <= 0f) {
                stopScale();
                setScale(1f);
                mScaleTo = 1f;
            } else if (alpha > last) {
                if (mScaleTo != 1f) scaleTo(1f);
            } else if (alpha < last) {
                if (mScaleTo != ENTER_SCALE) scaleTo(ENTER_SCALE);
            }
        }

        private void setScale(float s) {
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            setScaleX(s);
            setScaleY(s);
        }

        private void stopScale() {
            android.animation.ValueAnimator a = mScaleAnim;
            mScaleAnim = null;
            if (a != null) a.cancel();
        }

        private void scaleTo(float to) {
            stopScale();
            mScaleTo = to;
            final float from = getScaleX();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(PageSpring.durationMs(SCALE_RESPONSE, 0f));
            a.setInterpolator(PageSpring.interpolator(SCALE_RESPONSE, 0f));
            a.addUpdateListener(an -> {
                if (mScaleAnim == an) setScale(from + (to - from) * (float) an.getAnimatedValue());
            });
            mScaleAnim = a;
            a.start();
        }

        // ------------------------------------------------------------ drawing

        @Override
        protected void onDraw(Canvas canvas) {
            Frame f = mF;
            int w = getWidth();
            int h = getHeight();
            if (f == null || w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float maxW = w - 48f * mDp;

            // The landmark, moving, over the ground's own still of it.
            if (animating()) {
                RectF r = artRect(w, h);
                mArt.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right),
                        Math.round(r.bottom));
                mArt.setAlpha(f.art.fallback ? FALLBACK_ALPHA : 255);
                mArt.draw(canvas);
            }
            if (mLabel != null) {
                RectF r = artRect(w, h);
                float lw = Math.min(r.width() * 0.5f, 220f * mDp);
                float lh = lw * LABEL_ASPECT;
                float top = r.bottom + 4f * mDp;
                mLabel.setBounds(Math.round(cx - lw / 2f), Math.round(top),
                        Math.round(cx + lw / 2f), Math.round(top + lh));
                mLabel.draw(canvas);
            }

            // The line's pill and where it is going, one row, centred.
            float y = h * WORDS_TOP;
            mLine.setTextSize(14f * mDp);
            mDirection.setTextSize(14f * mDp);
            Paint.FontMetrics fm = mLine.getFontMetrics();
            float pillH = (fm.bottom - fm.top) + 8f * mDp;
            String line = f.line;
            float lineW = line.isEmpty() ? 0f : mLine.measureText(line) + 20f * mDp;
            String dir = TextUtils.ellipsize(f.direction, mDirection,
                    Math.max(0f, maxW - lineW - 8f * mDp), TextUtils.TruncateAt.END).toString();
            float dirW = dir.isEmpty() ? 0f : mDirection.measureText(dir);
            float rowW = lineW + (lineW > 0 && dirW > 0 ? 8f * mDp : 0f) + dirW;
            float x = cx - rowW / 2f;
            float baseline = y + pillH / 2f - (fm.ascent + fm.descent) / 2f;
            if (lineW > 0) {
                mRect.set(x, y, x + lineW, y + pillH);
                mFill.setColor(f.lineBg);
                canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mFill);
                mLine.setColor(f.lineText);
                canvas.drawText(line, x + 10f * mDp, baseline, mLine);
                x += lineW + 8f * mDp;
            }
            if (dirW > 0) canvas.drawText(dir, x, baseline, mDirection);
            y += pillH + 14f * mDp;

            // The milestone, and the stations left under it.
            mPrimary.setTextSize(30f * mDp);
            Paint.FontMetrics fp = mPrimary.getFontMetrics();
            String primary = TextUtils.ellipsize(f.primary, mPrimary, maxW,
                    TextUtils.TruncateAt.END).toString();
            canvas.drawText(primary, cx, y - fp.top, mPrimary);
            y += fp.bottom - fp.top + 6f * mDp;
            if (!f.secondary.isEmpty()) {
                mSecondary.setTextSize(15f * mDp);
                Paint.FontMetrics fs = mSecondary.getFontMetrics();
                String sec = TextUtils.ellipsize(f.secondary, mSecondary, maxW,
                        TextUtils.TruncateAt.END).toString();
                canvas.drawText(sec, cx, y - fs.top, mSecondary);
            }

            if (f.nodes != null) mTrack.draw(canvas, f, w * 0.17f, w * 0.83f, h * TRACK_Y);
        }
    }

    // ---------------------------------------------------------------- the demo

    /**
     * A ride on Beijing's line 1 towards 四惠东, now at 天安门东 (by the Forbidden City, so the
     * landmark shows), getting off at 王府井 to change to line 8 - a real interchange, so the
     * transfer stop shows the ⇄ and a line 8 badge. The shape is 高德's GaoDePtIntentEntity.
     */
    static final String DEMO = "{\"status\":\"5\",\"destCitycode\":\"010\","
            + "\"destStation\":\"王府井\",\"exitName\":\"A口\",\"guideInfo\":\"\","
            + "\"deepLink\":\"amapuri://amap\","
            + "\"naviInfo\":[{\"isCurrent\":true,\"transportType\":\"2\",\"lineName\":\"地铁1号线\","
            + "\"lineDirection\":\"四惠东\",\"lineBgColor\":\"#C23A30\",\"lineTextColor\":\"#FFFFFF\","
            + "\"remainStations\":1,"
            + "\"on_station\":{\"stationName\":\"西单\"},"
            + "\"off_station\":{\"stationName\":\"王府井\",\"isTransferStation\":true,"
            + "\"coord\":{\"lat\":39.908,\"lng\":116.411},"
            + "\"port_list\":[{\"name\":\"A口\",\"coord\":{\"lat\":39.9085,\"lng\":116.4106}}]},"
            + "\"via_st_list\":[{\"name\":\"天安门西\",\"coord\":{\"lat\":39.9075,\"lng\":116.3912}},"
            + "{\"name\":\"天安门东\",\"coord\":{\"lat\":39.9078,\"lng\":116.4013}}]},"
            + "{\"transportType\":\"2\",\"lineName\":\"地铁8号线\",\"lineBgColor\":\"#009B6B\"}]}";
}
