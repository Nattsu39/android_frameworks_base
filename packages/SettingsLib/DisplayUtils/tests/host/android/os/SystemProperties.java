// SPDX-License-Identifier: Apache-2.0

package android.os;

/** Host fixture: properties are supplied by each test JVM. */
public final class SystemProperties {
    public static String get(String key) {
        return System.getProperty(key, "");
    }

    public static boolean getBoolean(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
