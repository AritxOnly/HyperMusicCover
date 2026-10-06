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

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德's bus and subway trip as a page behind the lock screen: ColorOS 17's lock-screen card for
 * it (SceneService's 536879317, an immersive template card), drawn here.
 *
 * AmapTransitShare, in 高德's process, sends the trip as ColorOS's GaoDePtIntentEntity
 * ({@code op transit}); AmapTransitCard works out the card from it, the same card the island is
 * posted from, and this draws it:
 *   - the line, in its own colour, and where it is heading;
 *   - the card's primary and secondary lines (下一站 XX / N站 XX下车, 准备换乘 / 3号线(往XX), the
 *     stop and its exit at 到站, the waiting card's next train);
 *   - the station overview, two or three stops with the train between or at them, a transfer stop
 *     with ⇄ and the next line's badge;
 *   - behind it, the landmark ColorOS shows: in transit the one by the stop the card names (ya.d),
 *     at 到站 the exit's, else the city's, else the nation's (ya.b.O), from OPPO's CDN
 *     (AmapTransitLandmarks). Where ColorOS shows none the national picture stands in, dimmed, so
 *     the page keeps a ground.
 *
 * Ready while 高德's trip has a card that is not a walk (a walk is 高德's own map, AmapNavScene);
 * it sits before AmapNavScene in ImmersiveHost.SCENES and claims 高德's island only while ready.
 *
 * The ground - the line's colour into black, and the still landmark - is a picture under the
 * shade window (CountdownScene.GroundSurface): the lock screen's glass rows sample what is behind
 * the window, never a view in it. The moving landmark and the words are a view over it.
 *
 * Probe: {@code op transit} - the state; {@code --es json '<entity>'} feeds one in by hand,
 * {@code --ez demo true} a made-up ride past 天安门, {@code --es do end} ends it.
 */
final class AmapTransitScene implements ImmersiveScene {

    static final String ID = "amap-transit";
    static final String PKG = AmapNavScene.PKG;
    static final AmapTransitScene INSTANCE = new AmapTransitScene();

    private static final String TAG = "MCImmersive: " + ID + ": ";

    /**
     * How long a card lasts without another word: GaoDePtNaviSceneRouter.h - the trip's end 30 s,
     * a 到站 15 min, the rest 30 min. AmapTransitShare keeps the same times, but in 高德's process,
     * which Greezer freezes once 高德 is in the background (after the trip, say), so its timer can
     * stop running; the page keeps its own.
     */
    private static long staleMs(String status) {
        if (AmapTransitCard.ARRIVE_FINAL_DESTINATION.equals(status)) return 30_000L;
        if (AmapTransitCard.ARRIVE_LINE_DESTINATION.equals(status)) return 15L * 60_000L;
        return 30L * 60_000L;
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private AmapTransitCard.Trip mTrip;
    private AmapTransitCard.Card mCard;
    private Art.Pick mPick = Art.Pick.NONE;
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
            final AmapTransitCard.Trip t;
            try {
                t = AmapTransitCard.Trip.parse(new JSONObject(json));
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

    private void take(AmapTransitCard.Trip t) {
        AmapTransitCard.Card c = AmapTransitCard.of(t);
        if (c == null) {
            end("no card for status " + t.status);
            return;
        }
        boolean was = ready();
        mShares++;
        mTrip = t;
        mTripAt = SystemClock.uptimeMillis();
        mEndedBy = null;
        mMain.removeCallbacks(mStale);
        mMain.postDelayed(mStale, staleMs(c.status));
        Art.Pick pick = Art.Pick.of(t, c);
        boolean newPicture = !pick.equals(mPick);
        // The landmark plays once for a new state - a new picture, milestone or stop - and not
        // for the same state again (a keepalive, a picture that arrived late).
        boolean again = mCard == null || newPicture || !mCard.sameAs(c);
        mCard = c;
        mPick = pick;
        Xp.log(TAG + c.kind + " " + c.status + ": " + c.primary + " | " + c.secondaryLine
                + c.secondary + " art=" + pick);
        if (mView != null) {
            mView.setCard(c, again);
            if (newPicture) Art.request(mView.getContext(), pick, this::onArt);
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
        mCard = null;
        mPick = Art.Pick.NONE;
        mEndedBy = by;
        Xp.log(TAG + "ended by " + by);
        if (was) ImmersiveHost.readyChanged(this);
    }

    /** The pictures for the card they were asked for, once they are here; main thread. */
    private void onArt(Art.Set set) {
        if (!mPick.equals(set.pick)) return;
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

    /** 高德's island is this page's only while there is a card to show; the map's otherwise. */
    @Override
    public boolean servesKey(String key) {
        return ready();
    }

    /** A card that is not the walking one: a walk is 高德's own navigation, and its map. */
    @Override
    public boolean ready() {
        AmapTransitCard.Card c = mCard;
        return c != null && !AmapTransitCard.Card.KIND_WALK.equals(c.kind);
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
        AmapTransitCard.Card c = mCard;
        if (c != null) {
            v.setCard(c, false);
            Art.request(ctx, mPick, this::onArt);
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
        AmapTransitCard.Trip t = mTrip;
        AmapTransitCard.Card c = mCard;
        StringBuilder sb = new StringBuilder();
        sb.append("shares=").append(mShares);
        if (t == null || c == null) {
            sb.append(" no trip").append(mEndedBy == null ? "" : " (ended by " + mEndedBy + ")");
        } else {
            AmapTransitCard.Leg l = t.current;
            sb.append(' ').append(c.kind).append(" status=").append(c.status).append(' ').append(c.page)
                    .append(" leg=").append(t.currentIndex()).append('/').append(t.navi.size())
                    .append(l == null ? "" : " " + l.lineName + " remain=" + l.remain + " via=" + l.via.size())
                    .append(" age=").append(SystemClock.uptimeMillis() - mTripAt).append("ms")
                    .append(" [").append(c.primary).append(" | ").append(c.secondaryLine)
                    .append(c.secondary).append("] capsule=[").append(c.leftLine).append(' ')
                    .append(c.leftWhite).append(" | ").append(c.rightLine).append(' ')
                    .append(c.rightWhite).append(c.rightGray).append(']');
            if (c.stations != null) {
                sb.append(" stations=");
                for (AmapTransitCard.Node n : c.stations) {
                    sb.append(n.name).append(n.transfer ? "⇄" : "").append(n.badge.isEmpty() ? "" : "[" + n.badge + "]")
                            .append(',');
                }
                sb.append(c.atStation ? " at" : " between");
            }
            sb.append(" art=").append(mPick);
        }
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
        AmapTransitCard.Card c = mCard;
        if (g == null || v == null || c == null) return;
        int w = v.getResources().getDisplayMetrics().widthPixels;
        int h = v.getResources().getDisplayMetrics().heightPixels;
        boolean still = mDozing || !v.animating();
        g.setBitmap(Ground.make(w, h, c.lineBg, mPick, still ? v.stillArt() : null));
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

            /** SceneService's choice for a card: ya.d.a in transit, ya.b.O on arriving. */
            static Pick of(AmapTransitCard.Trip t, AmapTransitCard.Card c) {
                AmapTransitCard.Leg l = t.current;
                boolean subway = c.subway();
                if (!c.landmarkStation.isEmpty() && l != null) {
                    double[] at = stationPoint(l, c.landmarkStation);
                    AmapTransitLandmarks.Match m = at == null ? null
                            : AmapTransitLandmarks.near(t.cityCode, at[0], at[1]);
                    if (m != null) {
                        return new Pick(new String[] {m.animatedUrl(), m.stillUrl()}, m.labelUrl(),
                                false, "landmark " + m + " at " + c.landmarkStation);
                    }
                    double[] p = at != null ? at : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    return new Pick(new String[] {AmapTransitLandmarks.nationalDefaultUrl(subway, night)},
                            null, true, "no landmark at " + c.landmarkStation + (night ? ", night" : ""));
                }
                if (c.arrivalArt && l != null) {
                    double[] exit = exitPoint(t, l);
                    double[] p = exit != null ? exit : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    List<String> urls = new ArrayList<>();
                    String label = null;
                    // Only a point of the trip's own is looked up: where() falls back to a fixed
                    // one for day and night, which would find that city's landmark for any trip.
                    AmapTransitLandmarks.Match m = exit == null ? null
                            : AmapTransitLandmarks.near(t.cityCode, p[0], p[1]);
                    if (m != null) {
                        urls.add(m.animatedUrl());
                        urls.add(m.stillUrl());
                        label = m.labelUrl();
                    }
                    // A subway arrival falls back to the city's picture, a bus straight to the
                    // nation's (PublicTransportDestinationImageUtil.j / .n).
                    String folder = AmapTransitLandmarks.folder(t.cityCode);
                    if (folder == null && exit != null) folder = AmapTransitLandmarks.folderNear(p[0], p[1]);
                    if (subway && folder != null) {
                        urls.add(AmapTransitLandmarks.cityDefaultUrl(folder, night));
                    }
                    urls.add(AmapTransitLandmarks.nationalDefaultUrl(subway, night));
                    return new Pick(urls.toArray(new String[0]), label, m == null,
                            (m == null ? "arrival, default" : "arrival, landmark " + m)
                                    + (night ? ", night" : ""));
                }
                double[] p = where(t);
                boolean night = night(p[0], p[1], System.currentTimeMillis());
                return new Pick(new String[] {AmapTransitLandmarks.nationalDefaultUrl(subway, night)},
                        null, true, c.kind + (night ? ", night" : ""));
            }

            /** ya.d.c: the named stop's spot, a stop between the ends or the one gotten off at. */
            private static double[] stationPoint(AmapTransitCard.Leg l, String name) {
                for (AmapTransitCard.Station s : l.via) {
                    if (s.name.trim().equals(name) && s.located()) return new double[] {s.lat, s.lng};
                }
                if (l.offName.trim().equals(name) && AmapTransitLandmarks.valid(l.offLat, l.offLng)) {
                    return new double[] {l.offLat, l.offLng};
                }
                return null;
            }

            /**
             * ya.b.N: the exit 高德 named, by its name or its shield, else the stop's first exit,
             * else the stop itself.
             */
            private static double[] exitPoint(AmapTransitCard.Trip t, AmapTransitCard.Leg l) {
                if (!l.ports.isEmpty()) {
                    String exit = t.exitName.trim();
                    AmapTransitCard.Port port = null;
                    if (!exit.isEmpty()) {
                        for (AmapTransitCard.Port p : l.ports) {
                            if (p.name.equalsIgnoreCase(exit) || p.shield.equalsIgnoreCase(exit)
                                    || p.name.toLowerCase().contains(exit.toLowerCase())) {
                                port = p;
                                break;
                            }
                        }
                    }
                    if (port == null) port = l.ports.get(0);
                    if (port.located()) return new double[] {port.lat, port.lng};
                }
                if (AmapTransitLandmarks.valid(l.offLat, l.offLng)) return new double[] {l.offLat, l.offLng};
                return null;
            }

            /** Somewhere on the trip, for whether it is night there - never for a landmark. */
            private static double[] where(AmapTransitCard.Trip t) {
                AmapTransitCard.Leg l = t.current;
                if (l != null) {
                    if (AmapTransitLandmarks.valid(l.offLat, l.offLng)) return new double[] {l.offLat, l.offLng};
                    for (AmapTransitCard.Station s : l.via) {
                        if (s.located()) return new double[] {s.lat, s.lng};
                    }
                    if (AmapTransitLandmarks.valid(l.onLat, l.onLng)) return new double[] {l.onLat, l.onLng};
                }
                if (AmapTransitLandmarks.valid(t.destLat, t.destLng)) return new double[] {t.destLat, t.destLng};
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
     * (SceneService picks day and night pictures by sunrise and sunset there, pa.f).
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
     * in the middle, the overview under it, all inside the band ColorOS keeps an immersive page's
     * information in (0.231 to 0.703 of the height, LiveAlertScene.INFO_*).
     */
    private static final float WORDS_TOP = 0.235f;
    private static final float ART_CENTRE = 0.505f;
    private static final float TRACK_Y = 0.585f;
    /** OPPO's landmark pictures are 807x378, their names 423x66. */
    private static final float ART_ASPECT = 378f / 807f;
    private static final float LABEL_ASPECT = 66f / 423f;
    /** A default rather than a landmark stays in the background. */
    private static final int FALLBACK_ALPHA = 110;

    /** The ground: the line's colour at the top into near-black, and the still landmark. */
    static final class Ground {
        private Ground() {
        }

        private static final int BOTTOM = 0xff07080b;

        /** At half the screen's size: it is a soft gradient, and the still is shown under a veil. */
        static Bitmap make(int w, int h, int lineBg, Art.Pick pick, Bitmap still) {
            int bw = Math.max(1, w / 2);
            int bh = Math.max(1, h / 2);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, bh,
                    new int[] {blend(lineBg, BOTTOM, 0.62f), blend(lineBg, BOTTOM, 0.86f), BOTTOM},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            if (still != null) {
                RectF r = artRect(bw, bh);
                Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                ip.setAlpha(pick.fallback ? FALLBACK_ALPHA : 255);
                c.drawBitmap(still, null, r, ip);
            }
            return b;
        }
    }

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

    /**
     * The station overview (cardStationOverview): two or three stops on a bar in the line's
     * colour, filled up to the train - between the first two on 下一站, at the middle one
     * otherwise - a transfer stop a ⇄ pill with the next line's badge over it, the names below.
     */
    static final class Track {
        final float dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint node = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Track(float dp) {
            this.dp = dp;
            node.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            node.setTextAlign(Paint.Align.CENTER);
        }

        void draw(Canvas canvas, AmapTransitCard.Card c, float left, float right, float y) {
            List<AmapTransitCard.Node> nodes = c.stations;
            if (nodes == null || nodes.isEmpty()) return;
            int n = nodes.size();
            float[] xs = new float[n];
            for (int i = 0; i < n; i++) {
                xs[i] = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1);
            }
            // curIndex is 1: the train is at the second stop, or on its way to it.
            int cur = Math.min(1, n - 1);
            float trainX = c.atStation || cur == 0 ? xs[cur] : (xs[cur - 1] + xs[cur]) / 2f;
            fill.setStyle(Paint.Style.FILL);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeWidth(5f * dp);
            fill.setColor(0x33ffffff);
            canvas.drawLine(left, y, right, y, fill);
            fill.setColor(c.lineBg);
            canvas.drawLine(left, y, trainX, y, fill);
            node.setTextSize(13f * dp);
            Paint.FontMetrics fn = node.getFontMetrics();
            float slot = (right - left) / Math.max(1, n - 1) - 6f * dp;
            for (int i = 0; i < n; i++) {
                AmapTransitCard.Node s = nodes.get(i);
                boolean reached = xs[i] <= trainX + 0.5f;
                int tint = reached ? s.color : 0xff4a4d55;
                if (s.transfer) {
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
                    float r = i == cur ? 6.5f : 5f;
                    fill.setColor(tint);
                    canvas.drawCircle(xs[i], y, r * dp, fill);
                    fill.setColor(Color.WHITE);
                    canvas.drawCircle(xs[i], y, r * 0.42f * dp, fill);
                }
                if (!s.badge.isEmpty()) drawBadge(canvas, s.badge, s.badgeColor, xs[i], y - 15f * dp);
                node.setColor(i == cur ? 0xf2ffffff : 0x99ffffff);
                node.setFakeBoldText(i == cur);
                String name = TextUtils.ellipsize(s.name, node, slot, TextUtils.TruncateAt.END).toString();
                canvas.drawText(name, xs[i], y + 16f * dp - fn.top, node);
            }
            node.setFakeBoldText(false);
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

        /** A line-number square in its colour, white number: 五一路's green 5. */
        private void drawBadge(Canvas canvas, String code, int color, float cx, float bottom) {
            node.setTextSize(11f * dp);
            node.setFakeBoldText(true);
            node.setColor(0xffffffff);
            float tw = node.measureText(code);
            float pad = 4f * dp;
            float bw = Math.max(16f * dp, tw + 2f * pad);
            float bh = 16f * dp;
            rect.set(cx - bw / 2f, bottom - bh, cx + bw / 2f, bottom);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
            canvas.drawRoundRect(rect, 4f * dp, 4f * dp, fill);
            Paint.FontMetrics fm = node.getFontMetrics();
            canvas.drawText(code, cx, rect.centerY() - (fm.ascent + fm.descent) / 2f, node);
            node.setFakeBoldText(false);
        }
    }

    // ---------------------------------------------------------------- the page

    /** The words, the overview and the moving landmark, over the ground. */
    final class TransitView extends View {

        private final float mDp;
        private final TextPaint mPrimary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mSecondary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mLine = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mDirection = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Track mTrack;

        private AmapTransitCard.Card mC;
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
            mLine.setTypeface(bold);
            mDirection.setTypeface(plain);
            mDirection.setColor(0xccffffff);
            mTrack = new Track(mDp);
        }

        /** The new card; a different one plays the landmark once more. */
        void setCard(AmapTransitCard.Card c, boolean again) {
            mC = c;
            if (again) replayArt();
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

        /** The landmark plays once and stops: a new state is one pass, not a loop. */
        private void startArt() {
            if (mLive && mArt instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable a = (AnimatedImageDrawable) mArt;
                a.setRepeatCount(0);
                a.start();
            }
            paintGround();
        }

        void replayArt() {
            if (!(mArt instanceof AnimatedImageDrawable)) return;
            AnimatedImageDrawable a = (AnimatedImageDrawable) mArt;
            stopArt();
            if (mLive) a.start();
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
            AmapTransitCard.Card c = mC;
            int w = getWidth();
            int h = getHeight();
            if (c == null || w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float maxW = w - 48f * mDp;

            // The landmark, moving, over the ground's own still of it.
            if (animating()) {
                RectF r = artRect(w, h);
                mArt.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right),
                        Math.round(r.bottom));
                mArt.setAlpha(mPick.fallback ? FALLBACK_ALPHA : 255);
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

            float y = h * WORDS_TOP;
            mLine.setTextSize(14f * mDp);
            mDirection.setTextSize(14f * mDp);
            Paint.FontMetrics fm = mLine.getFontMetrics();
            float pillH = (fm.bottom - fm.top) + 8f * mDp;
            // The line's pill and where it is going, one row, centred - for a ride.
            if (!c.line.isEmpty()) {
                float lineW = mLine.measureText(c.line) + 20f * mDp;
                String dir = TextUtils.ellipsize(c.direction, mDirection,
                        Math.max(0f, maxW - lineW - 8f * mDp), TextUtils.TruncateAt.END).toString();
                float dirW = dir.isEmpty() ? 0f : mDirection.measureText(dir);
                float x = cx - (lineW + (dirW > 0 ? 8f * mDp + dirW : 0f)) / 2f;
                float baseline = y + pillH / 2f - (fm.ascent + fm.descent) / 2f;
                mRect.set(x, y, x + lineW, y + pillH);
                mFill.setColor(c.lineBg);
                canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mFill);
                mLine.setColor(c.lineText);
                canvas.drawText(c.line, x + 10f * mDp, baseline, mLine);
                if (dirW > 0) canvas.drawText(dir, x + lineW + 8f * mDp, baseline, mDirection);
            }
            y += pillH + 14f * mDp;

            // The card's primary line.
            mPrimary.setTextSize(30f * mDp);
            Paint.FontMetrics fp = mPrimary.getFontMetrics();
            String primary = TextUtils.ellipsize(c.primary.isEmpty() ? c.lockTitle : c.primary,
                    mPrimary, maxW, TextUtils.TruncateAt.END).toString();
            canvas.drawText(primary, cx, y - fp.top, mPrimary);
            y += fp.bottom - fp.top + 6f * mDp;

            // The secondary line: the line-coloured chip ColorOS puts first (the next line at a
            // transfer, the exit at 到站), then the words; on the waiting card, the next train.
            mSecondary.setTextSize(15f * mDp);
            String words = c.secondary.trim();
            if (c.waiting != null && !c.waiting.isEmpty()) {
                AmapTransitCard.WaitLine wl = c.waiting.get(0);
                words = wl.realtime1 + (wl.realtime2.isEmpty() ? "" : "  " + wl.realtime2);
            }
            String chip = c.secondaryLine.trim();
            Paint.FontMetrics fs = mSecondary.getFontMetrics();
            float chipW = chip.isEmpty() ? 0f : mSecondary.measureText(chip) + 14f * mDp;
            String sec = TextUtils.ellipsize(words, mSecondary,
                    Math.max(0f, maxW - chipW - (chipW > 0 ? 8f * mDp : 0f)), TextUtils.TruncateAt.END).toString();
            float secW = sec.isEmpty() ? 0f : mSecondary.measureText(sec);
            float rowW = chipW + (chipW > 0 && secW > 0 ? 8f * mDp : 0f) + secW;
            float x = cx - rowW / 2f;
            float rowH = (fs.bottom - fs.top) + 6f * mDp;
            float base = y + rowH / 2f - (fs.ascent + fs.descent) / 2f;
            if (chipW > 0) {
                mRect.set(x, y, x + chipW, y + rowH);
                mFill.setColor(c.secondaryLineColor);
                canvas.drawRoundRect(mRect, 6f * mDp, 6f * mDp, mFill);
                mSecondary.setColor(Color.WHITE);
                canvas.drawText(chip, x + 7f * mDp, base, mSecondary);
                x += chipW + 8f * mDp;
            }
            if (secW > 0) {
                mSecondary.setColor(0xb3ffffff);
                canvas.drawText(sec, x, base, mSecondary);
            }

            if (c.stations != null) mTrack.draw(canvas, c, w * 0.17f, w * 0.83f, h * TRACK_Y);
        }
    }

    // ---------------------------------------------------------------- the demo

    /**
     * A ride on Beijing's line 1 towards 四惠东, coming to 天安门东 (by the Forbidden City, so the
     * landmark shows), getting off at 王府井 to change to line 8 - so the overview's last stop is a
     * transfer with an 8 on it. The shape is the entity AmapTransitShare sends.
     */
    static final String DEMO = "{\"status\":\"3\",\"entityId\":\"demo\",\"destCitycode\":\"010\","
            + "\"destStation\":\"南锣鼓巷\",\"exitName\":\"A口\",\"guideInfo\":\"\","
            + "\"deepLink\":\"amapuri://amap\",\"totalDuration\":1500,"
            + "\"naviInfo\":[{\"transportType\":\"0\",\"walkingOrRideLength\":\"420\","
            + "\"walkingOrRideDuration\":\"360\"},"
            + "{\"isCurrent\":true,\"transportType\":\"2\",\"lineName\":\"1号线\","
            + "\"lineDirection\":\"四惠东\",\"lineBgColor\":\"#C23A30\",\"lineTextColor\":\"#FFFFFF\","
            + "\"remainStations\":2,"
            + "\"on_station\":{\"stationName\":\"西单\"},"
            + "\"off_station\":{\"stationName\":\"王府井\",\"isTransferStation\":true,"
            + "\"coord\":{\"lat\":39.908,\"lng\":116.411},"
            + "\"port_list\":[{\"name\":\"A口\",\"coord\":{\"lat\":39.9085,\"lng\":116.4106}}]},"
            + "\"via_st_list\":[{\"name\":\"天安门西\",\"coord\":{\"lat\":39.9075,\"lng\":116.3912}},"
            + "{\"name\":\"天安门东\",\"coord\":{\"lat\":39.9078,\"lng\":116.4013}}]},"
            + "{\"transportType\":\"0\",\"walkingOrRideLength\":\"120\",\"walkingOrRideDuration\":\"120\"},"
            + "{\"transportType\":\"2\",\"lineName\":\"8号线\",\"lineDirection\":\"瀛海\","
            + "\"lineBgColor\":\"#009B6B\",\"on_station\":{\"stationName\":\"王府井\"},"
            + "\"off_station\":{\"stationName\":\"南锣鼓巷\"},"
            + "\"via_st_list\":[{\"name\":\"金鱼胡同\"},{\"name\":\"中国美术馆\"}]}]}";
}
