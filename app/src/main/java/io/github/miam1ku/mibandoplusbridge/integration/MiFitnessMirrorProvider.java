// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.MacIds;
import io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import io.github.miam1ku.mibandoplusbridge.service.HealthReplay;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;

/** Selected-device copies only. The official app retains its transport, acknowledgements and data. */
public final class MiFitnessMirrorProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.mi-mirror");
    public static final int MAX_FRAGMENT_BYTES = 128 * 1024;
    private BandHistoryParser parser;
    private String parserDevice = "";
    private String parserFirmware = "";
    private HealthReplay replay;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle input) {
        boolean self = HostIdentity.isSelf();
        if (!self) HostIdentity.requireMiCaller(getContext());
        if (!getContext().getSystemService(UserManager.class).isUserUnlocked()
                || !new OwnershipController(getContext()).coexistReady()
                || !new BandStateRepository(getContext()).isRegistered()) {
            parser = null;
            if (replay != null) { replay.close(); replay = null; }
            return status("MI_MIRROR_INACTIVE");
        }
        try {
            BindingStore.Identity binding = new BindingStore(getContext()).readIdentity();
            if (binding == null || binding.did().isBlank()) return status("MI_MIRROR_DID_REQUIRED");
            if (replay == null) {
                replay = new HealthReplay(getContext(), code -> {
                    getContext().getSharedPreferences("live-service", 0).edit()
                            .putString("healthCollectionStatus", code).apply();
                    SessionLog.line(getContext(), "mirror " + code);
                }, () -> {});
                replay.request();
            }
            if ("target".equals(method)) {
                Bundle target = status("MI_MIRROR_ACTIVE");
                target.putString("did", binding.did());
                target.putString("address", binding.address());
                return target;
            }
            if (self) throw new SecurityException("MI_CALLER_REQUIRED");
            if (input == null || !binding.did().equals(input.getString("did"))) {
                throw new SecurityException("MI_MIRROR_DEVICE_MISMATCH");
            }
            BandStateRepository repository = new BandStateRepository(getContext());
            if ("state".equals(method)) {
                if (!MacIds.same(binding.address(), input.getString("address", ""))) {
                    throw new SecurityException("MI_MIRROR_DEVICE_MISMATCH");
                }
                boolean connected = input.getBoolean("connected", false);
                repository.recordCompanionState(binding.address(), connected, input.getString("firmware"),
                        input.getInt("battery", -1), input.getInt("chargerState", 0));
                if (!connected && parser != null) parser.reset();
                return status("MI_MIRROR_STATE_SAVED");
            }
            if (!"fragment".equals(method)) throw new IllegalArgumentException("UNSUPPORTED_MIRROR_OPERATION");
            byte[] data = input.getByteArray("data");
            if (data == null || data.length < 5 || data.length > MAX_FRAGMENT_BYTES) {
                throw new IllegalArgumentException("INVALID_HISTORY_FRAGMENT");
            }
            String firmware = LocalPrefs.open(getContext(), "band-state").getString("verifiedFirmware", "");
            if (firmware.isBlank()) firmware = new BindingStore(getContext()).read().optString("firmware", "");
            if (firmware.isBlank()) return status("MI_MIRROR_FIRMWARE_REQUIRED");
            if (parser == null || !parserDevice.equals(binding.deviceId()) || !parserFirmware.equals(firmware)) {
                parser = new BandHistoryParser(firmware, binding.deviceId(), binding.did());
                parserDevice = binding.deviceId();
                parserFirmware = firmware;
            }
            // A new file also recovers from a provider restart or a failed previous IPC.
            if ((data[2] & 0xff) == 1 && data[3] == 0) parser.reset();
            BandHistoryParser.FileResult file = parser.acceptFragment(data);
            if (file == null) return status("MI_MIRROR_FRAGMENT_ACCEPTED");
            new RawFitnessFileStore(getContext()).persist(file, binding.deviceId(), firmware,
                    System.currentTimeMillis());
            repository.recordCompanionState(binding.address(), true, firmware, -1, 0);
            repository.recordSyncCompleted();
            replay.request();
            SessionLog.line(getContext(), "mirror file " + file.parseStatus + " records=" + file.measurements.size());
            return status("PARSED".equals(file.parseStatus) ? "MI_MIRROR_FILE_SAVED" : "MI_MIRROR_FORMAT_UNSUPPORTED");
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception failure) {
            if (parser != null) parser.reset();
            SessionLog.line(getContext(), "mirror paused " + failure.getClass().getSimpleName());
            return status("MI_MIRROR_RETRY_NEXT_FILE");
        }
    }

    private static Bundle status(String code) {
        Bundle result = new Bundle();
        result.putString("status", code);
        return result;
    }

    @Override public synchronized void shutdown() {
        if (replay != null) { replay.close(); replay = null; }
        super.shutdown();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
