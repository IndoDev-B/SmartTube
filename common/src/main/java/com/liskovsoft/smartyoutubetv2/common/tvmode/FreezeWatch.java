package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Notes what the main thread is doing when it stops answering for more than 2 seconds. Android
 * closes an app after 5 seconds of that, and keeps its own record of why where nothing can read it.
 * Written to the log and to Android/data/(app)/files/tv_freezes.txt, newest last, kept small.
 */
public final class FreezeWatch {
    private static final String TAG = "FreezeWatch";
    private static final long STUCK_MS = 2_000;
    private static final long MAX_FILE_BYTES = 200_000;
    private static volatile boolean sStarted;
    private static volatile long sLastBeatMs;

    private FreezeWatch() {
    }

    public static void start(Context context) {
        if (sStarted || context == null) {
            return;
        }

        sStarted = true;
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable beat = () -> sLastBeatMs = android.os.SystemClock.uptimeMillis();
        sLastBeatMs = android.os.SystemClock.uptimeMillis();

        Thread watcher = new Thread(() -> {
            boolean reported = false;

            while (true) {
                main.post(beat);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }

                long stuck = android.os.SystemClock.uptimeMillis() - sLastBeatMs;

                if (stuck < STUCK_MS) {
                    reported = false;
                } else if (!reported) {
                    reported = true; // once per freeze: its stack at the moment it passed 2 s
                    report(app, stuck);
                }
            }
        }, "FreezeWatch");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MIN_PRIORITY);
        watcher.start();
    }

    private static void report(Context context, long stuckMs) {
        StringBuilder text = new StringBuilder();
        text.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("  main thread stuck ").append(stuckMs).append(" ms\n");

        for (StackTraceElement line : Looper.getMainLooper().getThread().getStackTrace()) {
            text.append("    at ").append(line).append('\n');
        }

        Runtime runtime = Runtime.getRuntime();
        text.append("    memory: ").append((runtime.totalMemory() - runtime.freeMemory()) / 1_048_576)
                .append(" of ").append(runtime.maxMemory() / 1_048_576).append(" MB\n\n");

        Log.e(TAG, text.toString());

        try {
            File folder = context.getExternalFilesDir(null);
            if (folder == null) {
                return;
            }
            File file = new File(folder, "tv_freezes.txt");
            boolean append = file.length() < MAX_FILE_BYTES; // full: start over rather than grow without end
            FileWriter writer = new FileWriter(file, append);
            try {
                writer.write(text.toString());
            } finally {
                writer.close();
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't write the freeze note: %s", e.getMessage());
        }
    }
}
