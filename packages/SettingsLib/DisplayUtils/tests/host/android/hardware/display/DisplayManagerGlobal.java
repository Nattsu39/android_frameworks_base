// SPDX-License-Identifier: Apache-2.0

package android.hardware.display;

import android.view.DisplayInfo;

/** Host fixture: one configurable internal panel and one external display. */
public final class DisplayManagerGlobal {
    private static final DisplayManagerGlobal INSTANCE = new DisplayManagerGlobal();

    public static DisplayManagerGlobal getInstance() {
        return INSTANCE;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        if (displayId < 0 || displayId > 1) {
            return null;
        }
        DisplayInfo info = new DisplayInfo();
        info.uniqueId = displayId == 0
                ? System.getProperty("test.panel", "local:4630947168392018835")
                : "local:external-panel";
        return info;
    }
}
