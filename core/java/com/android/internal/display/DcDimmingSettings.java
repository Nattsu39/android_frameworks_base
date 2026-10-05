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

package com.android.internal.display;

/**
 * Per-user Settings.System keys for software DC dimming.
 * @hide
 */
public final class DcDimmingSettings {
    /** Requested state; changes take effect at the next actual screen-off transition. */
    public static final String ENABLED = "backlight_dc_on";

    /** Applied switch state, including periods when AOD or brightness protection bypasses it. */
    public static final String APPLIED = "backlight_dc_work";

    private DcDimmingSettings() {}
}
