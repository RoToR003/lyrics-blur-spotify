package app.southstar21.extension.spotify.lyricsblur;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;
import android.view.View;

import java.util.WeakHashMap;

/**
 * Lyrics blur for Spotify: blurs every lyric line except the one being sung.
 * <p>
 * Spotify 9.2.4.2644 draws lyrics with Jetpack Compose. The patch calls
 * {@link #shouldBlur(boolean, boolean)} from the lyric line composable and feeds the answer to
 * Spotify's own animated blur modifier, which fades the blur in and out and skips it below
 * Android 12 by itself. This class therefore never touches Compose types.
 * <p>
 * {@link #setViewBlur(View, boolean)} is the same idea for a View based lyrics list; the
 * Compose patch doesn't use it.
 */
@SuppressLint("NewApi")
@SuppressWarnings("unused")
public final class LyricsBlurPatch {
    private static final String TAG = "LyricsBlur";

    private static final float VIEW_BLUR_RADIUS_PX = 12f;
    private static final long VIEW_ANIM_MS = 200L;

    private static int lastCombo = -1;
    private static int calls;

    // Debug log without adb: rewritten to Download/lyricsblur-debug.log via MediaStore (API 29+).
    // Read it with any file manager / Files app. Only used when the Debug logging option is on.
    private static final String DEBUG_FILE = "lyricsblur-debug.log";
    private static final int DEBUG_BUFFER_MAX = 15000;
    private static final StringBuilder debugBuffer = new StringBuilder();
    private static int toastsShown;
    private static final int TOAST_LIMIT = 12;
    private static final java.util.HashSet<String> seenMarkers = new java.util.HashSet<>();

    private LyricsBlurPatch() {
    }

    // The methods below are overwritten by the patch with the chosen option values.

    /** Only blur while the lyrics are time synced (else a plain text lyrics page would be all blurred). */
    public static boolean requireSynced() {
        return true;
    }

    /** Logs the (active, synced) values the hook sees whenever they change, to check it in logcat. */
    public static boolean debugLogging() {
        return false;
    }

    /**
     * Called for every composed lyric line.
     *
     * @param active this line is the one being sung
     * @param synced the lyrics are time synced
     * @return whether this line is blurred
     */
    public static boolean shouldBlur(boolean active, boolean synced) {
        try {
            if (debugLogging()) {
                calls++;
                int combo = (active ? 2 : 0) | (synced ? 1 : 0);
                if (calls == 1) {
                    debugWrite("hook alive sdk=" + Build.VERSION.SDK_INT
                            + " requireSynced=" + requireSynced()
                            + " isSupported=" + isSupported());
                    debugToast("LB: hook alive sdk=" + Build.VERSION.SDK_INT
                            + (isSupported() ? "" : " UNSUPPORTED(<12)"));
                }
                if (combo != lastCombo) {
                    lastCombo = combo;
                    String line = "line hook: active=" + active + " synced=" + synced + " calls=" + calls;
                    Log.d(TAG, line);
                    debugWrite(line);
                    debugToast("LB: active=" + active + " synced=" + synced);
                }
            }
            if (!isSupported()) return false;
            if (requireSynced() && !synced) return false;
            return !active;
        } catch (Throwable t) {
            // Never take the app down over a cosmetic patch.
            Log.e(TAG, "shouldBlur failed", t);
            if (debugLogging()) {
                debugToast("LB: error " + t);
            }
            return false;
        }
    }

    /** Blur needs Android 12. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Shows a short toast when hook events occur — the only debug channel that needs nothing at
     * all (no adb, no file manager). Capped at {@link #TOAST_LIMIT} per process; debug-only.
     * Posted to the main looper: composables may run off the UI thread, and Toast requires one.
     */
    private static void debugToast(String line) {
        try {
            if (toastsShown >= TOAST_LIMIT) return;
            toastsShown++;
            postToast(line, android.widget.Toast.LENGTH_SHORT);
        } catch (Throwable ignored) {
            // Never take the app down over a debug toast.
        }
    }

    private static void postToast(final String line, final int duration) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = (Context) Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                        if (ctx == null) return;
                        android.widget.Toast.makeText(ctx, line, duration).show();
                    } catch (Throwable ignored) {
                        // Never take the app down over a debug toast.
                    }
                }
            });
        } catch (Throwable ignored) {
            // Never take the app down over a debug toast.
        }
    }

    /**
     * Debug anchor tracker from the main-line composable (p.ksh0.a): fires per call at the
     * blur insertion point, capped. Tells us the body actually ran — and with which (p2, p3)
     * booleans — even when the process only reaches the skip path otherwise. Raw booleans, no
     * semantics claimed. No-op when Debug logging is off; never throws.
     */
    public static void anchor(boolean p1, boolean p2) {
        try {
            if (!debugLogging()) return;
            anchorCalls++;
            if (anchorCalls > 200) return;
            String line = "anchor p1=" + p1 + " p2=" + p2 + " #" + anchorCalls;
            Log.d(TAG, line);
            debugWrite(line);
        } catch (Throwable t) {
            Log.e(TAG, "anchor failed", t);
        }
    }

    private static int anchorCalls;

    /**
     * Debug anchor tracker for the translate-mode line wrapper (p.ksh0.b): same idea as
     * {@link #anchor(boolean, boolean)} for its blur insertion point. Logs the two Z
     * parameters (p5, p6). No-op when Debug logging is off; never throws.
     */
    public static void anchorB(boolean p5, boolean p6) {
        try {
            if (!debugLogging()) return;
            anchorBCalls++;
            if (anchorBCalls > 200) return;
            String line = "bAnchor p5=" + p5 + " p6=" + p6 + " #" + anchorBCalls;
            Log.d(TAG, line);
            debugWrite(line);
        } catch (Throwable t) {
            Log.e(TAG, "anchorB failed", t);
        }
    }

    private static int anchorBCalls;

    /**
     * Debug entry tracker from the patch: logs EVERY call into the target method with the
     * two Z arguments (p2, p3) — as raw booleans, no semantics claimed — tells us whether
     * composition reaches the method at all, even if the body exits before any later
     * instrumentation point. Capped; no toast; no-op when Debug logging is off; never throws.
     */
    public static void entry(boolean active, boolean synced) {
        try {
            if (!debugLogging()) return;
            entryCalls++;
            if (entryCalls > 200) return;
            String line = "entry: #" + entryCalls + " p2=" + active + " p3=" + synced;
            Log.d(TAG, line);
            debugWrite(line);
        } catch (Throwable t) {
            Log.e(TAG, "entry failed", t);
        }
    }

    private static int entryCalls;

    /**
     * Debug marker from the patch itself: fires once per {@code name} per process; logcat + file +
     * toast. Used to bisect where control flow reaches in the target method. No-op when Debug
     * logging is off; never throws.
     */
    public static void marker(String name) {
        try {
            if (!debugLogging()) return;
            synchronized (seenMarkers) {
                if (seenMarkers.contains(name)) return;
                seenMarkers.add(name);
            }
            Log.d(TAG, name);
            debugWrite(name);
            postToast(name, android.widget.Toast.LENGTH_LONG);
        } catch (Throwable t) {
            Log.e(TAG, "marker failed", t);
        }
    }

    /**
     * Appends a line to the debug buffer and rewrites Download/lyricsblur-debug.log. Needs no
     * permissions on API 29+ (MediaStore). Silent no-op below that; logcat still works.
     */
    private static void debugWrite(String line) {
        try {
            debugBuffer.append(line).append('\n');
            if (debugBuffer.length() > DEBUG_BUFFER_MAX) {
                debugBuffer.delete(0, debugBuffer.length() - DEBUG_BUFFER_MAX);
            }
            if (Build.VERSION.SDK_INT < 29) return;
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (ctx == null) return;
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri uri = null;
            try (android.database.Cursor c = cr.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Downloads._ID},
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{DEBUG_FILE}, null)) {
                if (c != null && c.moveToFirst()) {
                    uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                }
            }
            if (uri == null) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, DEBUG_FILE);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            }
            if (uri == null) return;
            java.io.OutputStream out = cr.openOutputStream(uri, "rwt");
            if (out != null) {
                out.write(debugBuffer.toString().getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "debugWrite failed", t);
        }
    }

    // region View based lyrics (not used by the Compose patch)

    private static final WeakHashMap<View, ViewBlur> viewStates = new WeakHashMap<>();

    private static final class ViewBlur {
        ValueAnimator animator;
        float radius;
    }

    /** Smoothly blurs ({@code blur}) or sharpens a view. Does nothing below Android 12. Call on the UI thread. */
    public static void setViewBlur(final View view, final boolean blur) {
        if (view == null || !isSupported()) return;
        try {
            ViewBlur state = viewStates.get(view);
            if (state == null) {
                state = new ViewBlur();
                viewStates.put(view, state);
            }
            if (state.animator != null) state.animator.cancel();

            final ViewBlur s = state;
            final float to = blur ? VIEW_BLUR_RADIUS_PX : 0f;
            if (s.radius == to) {
                applyRadius(view, to);
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(s.radius, to);
            animator.setDuration(VIEW_ANIM_MS);
            animator.addUpdateListener(a -> {
                s.radius = (Float) a.getAnimatedValue();
                applyRadius(view, s.radius);
            });
            s.animator = animator;
            animator.start();
        } catch (Throwable t) {
            Log.e(TAG, "setViewBlur failed", t);
        }
    }

    private static void applyRadius(View view, float radius) {
        if (radius <= 0.5f) {
            view.setRenderEffect(null);
        } else {
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    // endregion
}
