package app.southstar21.extension.spotify.lyricsblur;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
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
                if (combo != lastCombo) {
                    lastCombo = combo;
                    Log.d(TAG, "line hook: active=" + active + " synced=" + synced + " calls=" + calls);
                }
            }
            if (!isSupported()) return false;
            if (requireSynced() && !synced) return false;
            return !active;
        } catch (Throwable t) {
            // Never take the app down over a cosmetic patch.
            Log.e(TAG, "shouldBlur failed", t);
            return false;
        }
    }

    /** Blur needs Android 12. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
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
