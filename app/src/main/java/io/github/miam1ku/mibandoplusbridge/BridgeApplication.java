// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.app.Application;
import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.LegacyPrefsMigration;

public final class BridgeApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Content providers open preferences before Application.onCreate runs.
        LegacyPrefsMigration.run(base);
    }

    @Override public void onCreate() {
        super.onCreate();
        io.github.miam1ku.mibandoplusbridge.service.CoexistSync.schedule(this);
    }
}
