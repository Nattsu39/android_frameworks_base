// SPDX-License-Identifier: Apache-2.0

package android.util;

/** Host fixture for the platform math primitives used by the production class. */
public final class MathUtils {
    public static float norm(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    public static float lerp(float start, float stop, float fraction) {
        return start + (stop - start) * fraction;
    }

    public static float constrain(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static float sq(float value) { return value * value; }
    public static float exp(float value) { return (float) Math.exp(value); }
    public static float log(float value) { return (float) Math.log(value); }
    public static float sqrt(float value) { return (float) Math.sqrt(value); }
}
