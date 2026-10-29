// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;

/** After boot, unlock, or a package replace, resume the live link while this module still owns the band. A locked user returns first. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
        PendingResult pending = goAsync();
        Thread restore = new Thread(() -> {
            try {
                context.getContentResolver().notifyChange(
                        io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
                CoexistSync.request(context);
                OwnershipController owner = new OwnershipController(context);
                BandStateRepository repository = new BandStateRepository(context);
                if (repository.isRegistered() && owner.nativeReady()) {
                    BandLiveService.startBlocking(context);
                } else {
                    repository.markSessionClosed();
                    BandLiveService.stop(context);
                    SppDiagnosticClient.stopAllAndWait();
                }
            } catch (RuntimeException unavailable) {
                Log.i("OplusBandBridge", "BOOT_OWNERSHIP_RECOVERY_REQUIRED");
            } finally {
                pending.finish();
            }
        }, "OplusBandBootRecovery");
        restore.setDaemon(true);
        restore.start();
    }
}
