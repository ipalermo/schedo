/*
 * Copyright 2015 Schedo Inc. All rights reserved.
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
package com.ncode.android.apps.schedo.ui.debug.actions;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.ncode.android.apps.schedo.Config;
import com.ncode.android.apps.schedo.ui.NfcBadgeActivity;
import com.ncode.android.apps.schedo.ui.debug.DebugAction;

/**
 * Simulates a badge scan. For debug/testing purposes.
 */
public class SimulateBadgeScannedAction implements DebugAction {
    private static int sNext = 0;

    @Override
    public void run(final Context context, final Callback callback) {
        if (Config.DEBUG_SIMULATED_BADGE_URLS == null || 0 >= Config.DEBUG_SIMULATED_BADGE_URLS.length) {
            return;
        }
        final String url = Config.DEBUG_SIMULATED_BADGE_URLS[sNext % Config.DEBUG_SIMULATED_BADGE_URLS.length];
        sNext++;
        context.startActivity(new Intent(NfcBadgeActivity.ACTION_SIMULATE, Uri.parse(url),
                context, NfcBadgeActivity.class));
        Toast.makeText(context, "Simulating badge scan: " + url, Toast.LENGTH_LONG).show();
    }

    @Override
    public String getLabel() {
        return "simulate NFC badge scan";
    }
}
