package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The notification shade's background: the album cover as a slowly moving mesh gradient, in the
 * manner of Apple Music's player. See {@link MeshGradient} for where the look comes from.
 *
 * It lives in a window of its own BELOW the shade window. That is what lets the shade's official
 * glass - the background blur and the cards' pass-through material - sample it, so nothing of
 * SystemUI's presentation is touched. The window is a trusted overlay: an untrusted untouchable
 * overlay is capped at 0.8 opacity and the app behind would tint the glass.
 *
 * Cost. This runs while the shade is being dragged, which is when SystemUI's main thread has the
 * least to spare, so the whole shape is about keeping work off that thread:
 * - A SurfaceView, not a TextureView. A TextureView makes the main thread redraw its window for
 *   every frame the GL thread produces; a SurfaceView's buffer goes straight to the compositor.
 *   The first version used a TextureView and the pull-down stuttered.
 * - The buffer is fixed at a fraction of the screen and the compositor stretches it. The picture
 *   has no detail to lose, and the official blur goes over it anyway.
 * - The fade is a shader uniform. The main thread writes one float per frame, no view property.
 * - The mesh is built on Main's worker once per track and uploaded to buffer objects once; a frame
 *   is a handful of uniforms and one draw call.
 * - The EGL context, program, texture and buffers outlive the surface. Only the window surface is
 *   made again when the window comes back.
 * - The window is hidden a moment after the shade shuts, not on the frame it shuts, so a quick
 *   second pull-down does not pay for a relayout and a new surface.
 * - It renders only while the shade is open, on its own thread and Choreographer, capped at 60fps.
 */
final class ShadeBackdrop {

    private ShadeBackdrop() {
    }

    private static final String TAG = "[MCBackdrop] ";

    /** The render buffer is the screen divided by this. */
    private static final int DOWNSCALE = 4;
    /** The expansion at which the background is fully opaque. */
    private static final float FADE_END = 0.35f;
    /** How long a shut shade keeps the window, in case it is pulled again. */
    private static final long LINGER_MS = 800L;
    /** Animation seconds per real second. The source's pace: slow enough to read as still. */
    private static final float TIME_SCALE = 0.2f;
    /** Brightness gain over the source's look. 1.0 is theirs. */
    private static final float GAIN = 1.25f;
    /** Saturation over the source's look. 1.0 is theirs. */
    private static final float SATURATION = 1.15f;
    /** How long a new cover takes to cross-fade over the old one. */
    private static final long CROSSFADE_MS = 1000L;
    /**
     * The fastest the background may go from opaque to gone. The shade's progress normally falls
     * slower than this and is followed exactly; this only catches a progress that jumps straight
     * to 0 - seen after switching between the shade and the control centre sideways and then
     * closing - which otherwise cut the background off in one frame.
     */
    private static final long FADE_OUT_MS = 200L;

    private static volatile MeshGradient.Frame sPending;
    /**
     * When the pending cover arrived. The cross-fade is timed from here and not from the upload,
     * so a track that changed while the shade was shut is simply there on the next pull-down
     * instead of fading in late.
     */
    private static volatile long sPendingAt;
    private static volatile boolean sHasArt;
    /**
     * The fade the shade asks for, read by the GL thread every frame. What is drawn falls towards
     * it no faster than {@link #FADE_OUT_MS} allows.
     */
    private static volatile float sAlpha;

    private static SurfaceView sView;
    private static boolean sFailed;
    private static boolean sShowing;
    private static Renderer sRenderer;

    private static final Handler UI = new Handler(android.os.Looper.getMainLooper());

    // ------------------------------------------------------------------ inputs

    /**
     * The shade's composed cover changed. Called on Main's worker thread, before the old bitmap
     * is recycled, so reading `b` here is safe. The whole mesh is built here, off every thread that
     * draws.
     */
    static void setArt(Bitmap b) {
        MeshGradient.Frame frame = null;
        if (b != null && !b.isRecycled()) {
            try {
                frame = MeshGradient.build(b);
            } catch (Throwable t) {
                Xp.log(TAG + "mesh could not be built: " + t);
            }
        }
        sHasArt = frame != null;
        sPendingAt = android.os.SystemClock.uptimeMillis();
        sPending = frame;
        final Renderer r = sRenderer;
        if (r != null) r.requestUpload();
        if (!sHasArt) UI.post(HIDE_NOW);
    }

    static boolean hasArt() {
        return sHasArt;
    }

    /** Every expansion frame, on the main thread. */
    static void drive(float f, boolean allowed) {
        if (!allowed || !sHasArt || f <= 0f) {
            if (sShowing) {
                sShowing = false;
                sAlpha = 0f;
                // The renderer fades what is left out, draws a last frame at zero and stops.
                if (sRenderer != null) sRenderer.setRunning(false);
                UI.removeCallbacks(HIDE_NOW);
                UI.postDelayed(HIDE_NOW, LINGER_MS);
            }
            return;
        }
        final SurfaceView v = ensureAdded();
        if (v == null) return;
        sAlpha = f >= FADE_END ? 1f : f / FADE_END;
        if (!sShowing) {
            sShowing = true;
            UI.removeCallbacks(HIDE_NOW);
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            if (sRenderer != null) sRenderer.setRunning(true);
        }
    }

    /** Right away, for the master switch and a cover that is gone. Main thread. */
    static void hide() {
        UI.removeCallbacks(HIDE_NOW);
        sShowing = false;
        sAlpha = 0f;
        if (sRenderer != null) sRenderer.stopNow();
        final SurfaceView v = sView;
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }

    private static final Runnable HIDE_NOW = new Runnable() {
        @Override
        public void run() {
            if (!sShowing) hide();
        }
    };

    // ------------------------------------------------------------------ window

    private static SurfaceView ensureAdded() {
        if (sView != null) return sView;
        if (sFailed) return null;
        final View root = ShadeLayer.shadeRoot();
        if (root == null) return null;
        try {
            // The APPLICATION context: a WindowManager from a context inside the shade window
            // parents the new window to it, and it would no longer be below the shade.
            final Context ctx = root.getContext().getApplicationContext();
            final android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            final int sw = Math.max(dm.widthPixels, 1);
            final int sh = Math.max(dm.heightPixels, 1);

            final SurfaceView v = new SurfaceView(ctx);
            v.setVisibility(View.GONE);
            final SurfaceHolder holder = v.getHolder();
            // Translucent, because the fade is written into the pixels.
            holder.setFormat(PixelFormat.TRANSLUCENT);
            final int bw = Math.max(1, sw / DOWNSCALE);
            final int bh = Math.max(1, sh / DOWNSCALE);
            holder.setFixedSize(bw, bh);
            final Renderer r = new Renderer((float) sw / sh, bw, bh);
            holder.addCallback(r);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setTitle("MusicCoverShadeBackdrop");
            try {
                WindowManager.LayoutParams.class.getMethod("setTrustedOverlay").invoke(lp);
            } catch (Throwable t) {
                Xp.w(TAG + "setTrustedOverlay unavailable, the backdrop is capped at 0.8: " + t);
            }

            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).addView(v, lp);
            sView = v;
            sRenderer = r;
            Xp.log(TAG + "window added");
            return v;
        } catch (Throwable t) {
            sFailed = true;
            Xp.log(TAG + "window could not be added, the shade keeps its own background: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ GL

    /**
     * The source's vertex shader, unchanged: the mesh is overscaled by 1.4 so its edges are never
     * on screen, gently undulated by two sine waves, and stretched to the screen's proportions.
     */
    private static final String VERTEX =
            "precision highp float;\n"
            + "attribute vec2 a_pos;\n"
            + "attribute vec3 a_color;\n"
            + "attribute vec2 a_uv;\n"
            + "varying vec3 v_c;\n"
            + "varying vec2 v_t;\n"
            + "uniform float u_aspect;\n"
            + "uniform float u_time;\n"
            + "void main() {\n"
            + "  v_c = a_color;\n"
            + "  v_t = a_uv;\n"
            + "  vec2 p = a_pos * 1.4;\n"
            + "  float t = u_time * 0.5;\n"
            + "  p.x += sin(p.y * 3.0 + t) * 0.05;\n"
            + "  p.y += cos(p.x * 2.5 + t * 1.2) * 0.05;\n"
            + "  p.x = (u_aspect > 1.0) ? p.x : p.x / u_aspect;\n"
            + "  p.y = (u_aspect > 1.0) ? p.y * u_aspect : p.y;\n"
            + "  gl_Position = vec4(p, 0.0, 1.0);\n"
            + "}\n";

    /**
     * The source's fragment shader with its volume input fixed at zero, and the output written
     * premultiplied for a translucent surface: the texture turning slowly under the mesh colours,
     * a vignette, and a dither.
     */
    private static final String FRAGMENT =
            "precision highp float;\n"
            + "varying vec3 v_c;\n"
            + "varying vec2 v_t;\n"
            + "uniform sampler2D u_tex;\n"
            + "uniform float u_time;\n"
            + "uniform float u_alpha;\n"
            + "float hash(vec2 p) {\n"
            + "  p = fract(p * vec2(123.34, 456.21));\n"
            + "  p += dot(p, p + 45.32);\n"
            + "  return fract(p.x * p.y);\n"
            + "}\n"
            + "vec2 rotate(vec2 v, float a) {\n"
            + "  float c = cos(a);\n"
            + "  float s = sin(a);\n"
            + "  return vec2(c * v.x - s * v.y, s * v.x + c * v.y);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 uv = rotate(v_t - 0.5, u_time * 2.0) + 0.5;\n"
            + "  vec3 col = texture2D(u_tex, uv).rgb * v_c;\n"
            // Ours, on top of the source: the product of two colours that each top out at 0.7
            // reads muddy, so a little gain and saturation back, and a lighter vignette.
            + "  col *= " + GAIN + ";\n"
            + "  float l = dot(col, vec3(0.299, 0.587, 0.114));\n"
            + "  col = mix(vec3(l), col, " + SATURATION + ");\n"
            + "  col += (hash(gl_FragCoord.xy) - 0.5) / 255.0;\n"
            + "  float vig = smoothstep(0.8, 0.3, length(v_t - 0.5));\n"
            + "  col *= 0.7 + 0.3 * vig;\n"
            + "  gl_FragColor = vec4(clamp(col, 0.0, 1.0) * u_alpha, u_alpha);\n"
            + "}\n";

    /** EGL on a thread of its own, drawing into the SurfaceView's surface. */
    private static final class Renderer implements SurfaceHolder.Callback,
            Choreographer.FrameCallback {

        private final HandlerThread mThread = new HandlerThread("MCBackdropGL");
        private final Handler mHandler;
        private final float mAspectValue;

        private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLConfig mConfig;
        private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mSurface = EGL14.EGL_NO_SURFACE;
        private boolean mGlReady;
        private int mProgram;
        private int aPos, aUv, aColor;
        private int uTime, uAspect, uAlpha, uTex;
        /**
         * Two slots of texture and mesh - the cover on screen and the one it is replacing - so a
         * track change is a cross-fade. The slots swap roles on every upload; nothing is freed.
         */
        private final int[] mTextures = new int[2];
        /** Per slot: position, uv, colour, index. */
        private final int[][] mBuffers = new int[2][4];
        private final int[] mIndexCount = new int[2];
        private int mCur;
        private long mFadeStart;
        private int mBw;
        private int mBh;

        private boolean mRunning;
        /** Asked to stop, still fading out what is on screen; stops itself once that is 0. */
        private boolean mStopping;
        /** The alpha actually drawn: sAlpha, except that it falls no faster than FADE_OUT_MS. */
        private float mShown;
        private long mShownAtNs;
        private boolean mUpload = true;
        private long mLastFrameNs;
        /** Animation time shown so far; paused while stopped, so it resumes where it left. */
        private float mClock;

        Renderer(float aspect, int bw, int bh) {
            mAspectValue = aspect;
            // The fixed size, so the first frame has a viewport before surfaceChanged lands.
            mBw = bw;
            mBh = bh;
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
        }

        /**
         * Off does not stop at once: frames keep coming until the drawn alpha has fallen to 0, so
         * a shade whose progress jumped to 0 still fades. {@link #stopNow} is the immediate one.
         */
        void setRunning(final boolean on) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!on) {
                        if (mRunning) mStopping = true;
                        return;
                    }
                    mStopping = false;
                    if (mRunning) return;
                    mRunning = true;
                    mLastFrameNs = 0;
                    mShownAtNs = 0;
                    final Choreographer ch = Choreographer.getInstance();
                    ch.removeFrameCallback(Renderer.this);
                    ch.postFrameCallback(Renderer.this);
                }
            });
        }

        /** For the master switch and a cover that is gone: transparent on the next frame. */
        void stopNow() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mShown = 0f;
                    if (mRunning) stop();
                }
            });
        }

        private void stop() {
            mRunning = false;
            mStopping = false;
            Choreographer.getInstance().removeFrameCallback(this);
            // The last frame, transparent, so nothing stale stays on the surface.
            if (mSurface != EGL14.EGL_NO_SURFACE) draw();
        }

        /** Moves the drawn alpha towards the target: up at once, down at most at FADE_OUT_MS. */
        private void stepShown(long frameTimeNanos) {
            final float target = sAlpha;
            if (target >= mShown || mShownAtNs == 0) {
                mShown = target;
            } else {
                final float dt = (frameTimeNanos - mShownAtNs) / 1e9f;
                mShown = Math.max(target, mShown - Math.max(0f, dt) * 1000f / FADE_OUT_MS);
            }
            mShownAtNs = frameTimeNanos;
        }

        void requestUpload() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mUpload = true;
                }
            });
        }

        @Override
        public void surfaceCreated(final SurfaceHolder holder) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ensureGl();
                        mSurface = EGL14.eglCreateWindowSurface(mDisplay, mConfig,
                                holder.getSurface(), new int[]{EGL14.EGL_NONE}, 0);
                        if (mSurface == null || mSurface == EGL14.EGL_NO_SURFACE) {
                            mSurface = EGL14.EGL_NO_SURFACE;
                            throw new RuntimeException("eglCreateWindowSurface");
                        }
                        EGL14.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
                        if (mRunning) Choreographer.getInstance().postFrameCallback(Renderer.this);
                    } catch (Throwable t) {
                        Xp.w(TAG + "GL surface failed: " + t);
                    }
                }
            });
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, final int w, final int h) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mBw = w;
                    mBh = h;
                }
            });
        }

        /** Blocks until the EGL surface is gone: the Surface must not be drawn to after this. */
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            final CountDownLatch done = new CountDownLatch(1);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mSurface != EGL14.EGL_NO_SURFACE) {
                            // The context stays; with no surface current it just waits.
                            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE,
                                    EGL14.EGL_NO_SURFACE, mContext);
                            EGL14.eglDestroySurface(mDisplay, mSurface);
                            mSurface = EGL14.EGL_NO_SURFACE;
                        }
                    } finally {
                        done.countDown();
                    }
                }
            });
            try {
                done.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            Choreographer.getInstance().postFrameCallback(this);
            if (mSurface == EGL14.EGL_NO_SURFACE) return;
            // 60fps is plenty for motion this slow, and halves the work on a 120Hz panel.
            if (mLastFrameNs != 0 && frameTimeNanos - mLastFrameNs < 14_000_000L) return;
            if (mLastFrameNs != 0) {
                mClock += Math.min(0.1f, (frameTimeNanos - mLastFrameNs) / 1e9f) * TIME_SCALE;
            }
            mLastFrameNs = frameTimeNanos;
            stepShown(frameTimeNanos);
            if (mStopping && mShown <= 0f) {
                stop();
                return;
            }
            draw();
        }

        /** Once for the life of SystemUI: display, config, context, program, texture, buffers. */
        private void ensureGl() {
            if (mGlReady) return;
            mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            final int[] ver = new int[2];
            if (!EGL14.eglInitialize(mDisplay, ver, 0, ver, 1)) {
                throw new RuntimeException("eglInitialize");
            }
            final int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE};
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] num = new int[1];
            if (!EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, num, 0)
                    || num[0] == 0) {
                throw new RuntimeException("eglChooseConfig");
            }
            mConfig = configs[0];
            mContext = EGL14.eglCreateContext(mDisplay, mConfig, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            // A tiny pbuffer, only so the context can be current while the program is built.
            final EGLSurface pb = EGL14.eglCreatePbufferSurface(mDisplay, mConfig,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(mDisplay, pb, pb, mContext);

            mProgram = program(VERTEX, FRAGMENT);
            aPos = GLES20.glGetAttribLocation(mProgram, "a_pos");
            aUv = GLES20.glGetAttribLocation(mProgram, "a_uv");
            aColor = GLES20.glGetAttribLocation(mProgram, "a_color");
            uTime = GLES20.glGetUniformLocation(mProgram, "u_time");
            uAspect = GLES20.glGetUniformLocation(mProgram, "u_aspect");
            uAlpha = GLES20.glGetUniformLocation(mProgram, "u_alpha");
            uTex = GLES20.glGetUniformLocation(mProgram, "u_tex");

            GLES20.glGenTextures(2, mTextures, 0);
            for (int slot = 0; slot < 2; slot++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[slot]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                // Mirrored: the turning texture reaches past its own edge.
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_MIRRORED_REPEAT);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_MIRRORED_REPEAT);
                GLES20.glGenBuffers(4, mBuffers[slot], 0);
            }

            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, mContext);
            EGL14.eglDestroySurface(mDisplay, pb);
            mGlReady = true;
            Xp.log(TAG + "GL ready");
        }

        /**
         * The new cover into the slot that is not on screen, which then becomes the current one;
         * the old cover stays in the other slot to fade out from.
         */
        private void upload() {
            final MeshGradient.Frame f = sPending;
            if (f == null || f.texture.isRecycled()) {
                mIndexCount[0] = 0;
                mIndexCount[1] = 0;
                return;
            }
            final int slot = 1 - mCur;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[slot]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, f.texture, 0);
            final MeshGradient.Mesh m = f.mesh;
            final int[] buf = mBuffers[slot];
            arrayBuffer(buf[0], m.pos);
            arrayBuffer(buf[1], m.uv);
            arrayBuffer(buf[2], m.color);
            final Buffer idx = ByteBuffer.allocateDirect(m.index.length * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer().put(m.index).position(0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buf[3]);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.index.length * 2, idx,
                    GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            mIndexCount[slot] = m.index.length;
            mCur = slot;
            mFadeStart = sPendingAt;
        }

        private static void arrayBuffer(int id, float[] data) {
            final Buffer b = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, b, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }

        private void draw() {
            if (mUpload) {
                mUpload = false;
                upload();
            }
            GLES20.glViewport(0, 0, mBw, mBh);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            final int prev = 1 - mCur;
            if (mIndexCount[mCur] > 0) {
                GLES20.glUseProgram(mProgram);
                GLES20.glUniform1f(uTime, mClock);
                GLES20.glUniform1f(uAspect, mAspectValue);
                GLES20.glUniform1f(uAlpha, mShown);
                GLES20.glUniform1i(uTex, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

                float p = mIndexCount[prev] > 0
                        ? (android.os.SystemClock.uptimeMillis() - mFadeStart) / (float) CROSSFADE_MS
                        : 1f;
                if (p >= 1f) {
                    // Done: the old slot is not drawn again until the next track reuses it.
                    mIndexCount[prev] = 0;
                    drawSlot(mCur);
                } else {
                    p = Math.max(0f, p);
                    p = p * p * (3f - 2f * p);
                    drawSlot(prev);
                    // A constant-alpha blend is an exact mix of the two, alpha channel included,
                    // so the shade's own fade still applies evenly to both covers.
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA);
                    GLES20.glBlendColor(0f, 0f, 0f, p);
                    drawSlot(mCur);
                    GLES20.glDisable(GLES20.GL_BLEND);
                }
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            }
            EGL14.eglSwapBuffers(mDisplay, mSurface);
        }

        private void drawSlot(int slot) {
            final int[] buf = mBuffers[slot];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[slot]);
            attrib(aPos, buf[0], 2);
            attrib(aUv, buf[1], 2);
            attrib(aColor, buf[2], 3);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buf[3]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mIndexCount[slot],
                    GLES20.GL_UNSIGNED_SHORT, 0);
        }

        private static void attrib(int loc, int buffer, int size) {
            if (loc < 0) return;
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer);
            GLES20.glEnableVertexAttribArray(loc);
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, 0, 0);
        }

        private static int program(String vs, String fs) {
            final int v = shader(GLES20.GL_VERTEX_SHADER, vs);
            final int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            final int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            final int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        private static int shader(int type, String src) {
            final int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            final int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }
}
