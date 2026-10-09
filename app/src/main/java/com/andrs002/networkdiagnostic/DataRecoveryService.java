package com.andrs002.networkdiagnostic;

import android.content.Context;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Runs as Shizuku's ADB shell identity, NEVER as an ordinary application.
 * Only cycles mobile data (not airplane mode, Wi-Fi or the modem radio).
 */
public final class DataRecoveryService extends IDataRecovery.Stub {
    public DataRecoveryService() {}
    public DataRecoveryService(Context ignored) {}

    @Override public void destroy() { System.exit(0); }

    private static String svc(String action) throws Exception {
        Process p = new ProcessBuilder("/system/bin/svc", "data", action)
                .redirectErrorStream(true).start();
        boolean finished = p.waitFor(8, TimeUnit.SECONDS);
        if (!finished) { p.destroyForcibly(); return action + ":TIMEOUT"; }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = p.getInputStream()) {
            byte[] buffer = new byte[512];
            int n;
            while ((n = stream.read(buffer)) >= 0 && bytes.size() < 4096) {
                bytes.write(buffer, 0, n);
            }
        }
        String output = bytes.toString("UTF-8").trim().replace('\n', ' ');
        return action + ":exit=" + p.exitValue() + (output.isEmpty() ? "" : ":" + output);
    }

    @Override public synchronized String cycleMobileData() {
        final int uid = Os.getuid();
        if (uid != 2000 && uid != 0) return "DENIED_NOT_SHELL_UID:" + uid;
        String down = "NOT_RUN";
        String up = "NOT_RUN";
        try {
            down = svc("disable");
            // Always try to turn data back on even if 'disable' returned an error.
            Thread.sleep(1800);
        } catch (Exception e) {
            down += ":ERROR:" + e.getClass().getSimpleName();
        } finally {
            try {
                up = svc("enable");
            } catch (Exception e) {
                up = "enable:ERROR:" + e.getClass().getSimpleName();
            }
        }
        return "DATA_CYCLE_CMD:" + down + "|" + up + ":NOT_VERIFIED";
    }
}
