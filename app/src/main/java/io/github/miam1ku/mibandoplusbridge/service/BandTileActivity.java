// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.integration.NativePanel;
import io.github.miam1ku.mibandoplusbridge.MainActivity;

/** User-initiated tile tap. Wakes the bridge or opens it, then leaves no screen behind. */
public final class BandTileActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (CoexistSync.enabled(this)) {
                CoexistSync.request(this);
                var state = LocalPrefs.open(this, "band-state");
                Intent panel = new Intent(NativePanel.PANEL).addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra("device_id", state.getString("deviceId", ""))
                        .putExtra("device_mac_info", state.getString("mac", ""))
                        .putExtra("device_title", state.getString("name", "小米手环"));
                if (NativePanel.redirect(this, panel)) startActivity(panel);
                else {
                    Intent health = getPackageManager().getLaunchIntentForPackage(NativePanel.HEALTH);
                    if (health != null) startActivity(health);
                    else startActivity(new Intent(this, MainActivity.class));
                }
            } else if (BandTileState.click(BandLiveService.mayWake(this)) == BandTileState.Action.WAKE) {
                try {
                    BandLiveService.start(this);
                } catch (RuntimeException ignored) { }
            } else {
                startActivity(new Intent(this, MainActivity.class));
            }
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            android.widget.Toast.makeText(this, "请先打开 OPPO 健康完成设置。", android.widget.Toast.LENGTH_SHORT).show();
        } finally {
            finish();
        }
    }
}
