/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.connectivity;

import android.content.res.Resources;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.res.R;

/** Device-configurable LTE/NR signal bars, independent of telephony reporting criteria. */
public final class MobileSignalStrength {
    private static final int CUSTOM_MAX_LEVEL = 5;
    private final int[] mRsrpThresholds;

    public MobileSignalStrength(Resources resources) {
        this(resources.getIntArray(R.array.config_mobileSignalStrengthRsrpThresholds));
    }

    @VisibleForTesting
    MobileSignalStrength(int[] thresholds) {
        boolean valid = thresholds.length == CUSTOM_MAX_LEVEL;
        for (int i = 0; valid && i < thresholds.length; i++) {
            valid = thresholds[i] >= -156 && thresholds[i] <= -31
                    && (i == 0 || thresholds[i] < thresholds[i - 1]);
        }
        mRsrpThresholds = valid ? thresholds.clone() : new int[0];
    }

    public boolean isEnabled() {
        return mRsrpThresholds.length != 0;
    }

    public int getNumLevels() {
        return isEnabled() ? CUSTOM_MAX_LEVEL + 1 : CellSignalStrength.getNumSignalStrengthLevels();
    }

    public int getLevel(SignalStrength signalStrength) {
        if (signalStrength == null) {
            return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
        if (!isEnabled()) {
            return signalStrength.getLevel();
        }
        boolean hasRsrp = !signalStrength.getCellSignalStrengths(CellSignalStrengthLte.class).isEmpty()
                || !signalStrength.getCellSignalStrengths(CellSignalStrengthNr.class).isEmpty();
        if (hasRsrp) {
            // getDbm() uses the framework's primary signal, including its LTE/NR NSA policy.
            // LTE and NR use the same display curve; neither RAT is preferred here.
            int rsrp = signalStrength.getDbm();
            if (rsrp < -156 || rsrp > -31) {
                return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            }
            for (int i = 0; i < mRsrpThresholds.length; i++) {
                // The OEM display curve uses strict comparison, including at each boundary.
                if (rsrp > mRsrpThresholds[i]) {
                    return CUSTOM_MAX_LEVEL - i;
                }
            }
            return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
        return scaleLevel(signalStrength.getLevel());
    }

    /** Retain the relative standard level for RATs without a custom RSRP curve. */
    public int scaleLevel(int level) {
        return isEnabled() ? Math.round(level * CUSTOM_MAX_LEVEL / 4.0f) : level;
    }
}
