package com.vectras.vm;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.vectras.vm.main.core.MainStartVM;
import com.vectras.vm.manager.VmServiceManager;
import com.vectras.vm.utils.ClipboardUltils;
import com.vectras.vm.utils.DialogUtils;
import com.vectras.vterm.Terminal;
import com.vectras.vterm.Terminal2;

import java.util.Objects;

public class MainService extends Service {
    public static String CHANNEL_ID = "Vectras VM Service";
    private final int NOTIFICATION_ID = 1;
    public static String vmName = "Vectras VM";
    public static String env = null;
    private static String TAG = "MainService";
    public static MainService service;
    public static Context activityContext;

    private final String STOP_ACTION = "STOP";

    @Override
    public void onCreate() {
        super.onCreate();
        service = this;
        createNotificationChannel();

        Intent stopSelf = new Intent(this, MainService.class);
        stopSelf.setAction(STOP_ACTION);
        PendingIntent pStopSelf = PendingIntent.getService(
                this, 0, stopSelf, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("The virtual machines are running...")
                .setSmallIcon(R.drawable.ic_vectras_vm_48)
                .addAction(R.drawable.close_24px, getString(R.string.stop), pStopSelf)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (env != null) {
            if (service != null) {
                startCommand(vmName, env, activityContext);
            }
        } else {
            Log.e(TAG, "env is null");
        }
    }

    public static void stopService() {
        new Thread(() -> {
            if (service != null) {
                service.stopForeground(true);
                service.stopSelf();
                VMManager.killallqemuprocesses(activityContext);
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Objects.equals(intent.getAction(), STOP_ACTION)) {
            new Thread(() -> {
                VMManager.killallqemuprocesses(this);
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopForeground(true);
                    stopSelf();
                });
            }).start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_ID,
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    public static void startCommand(String vmName, String env, Context context) {
        Terminal2 terminal2 = new Terminal2(activityContext);
        terminal2.setDefaultShellBash();
        terminal2.setStartup("export XDG_RUNTIME_DIR=/tmp && unset PULSE_SERVER && "
                + com.vectras.vm.main.core.DisplaySystem.getGlEnvSetup());
        terminal2.execute(env, new Terminal2.Terminal2Callback() {
            @Override
            public void onRunning(String command, String newLine) {
                // Nothing to do.
            }

            @Override
            public void onFinished(String command, String log, int status) {
                if (context instanceof Activity activity) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                } else {
                    Log.e(TAG, "context is not an Activity");
                    return;
                }

                // Strip GL diagnostics before any processing so they don't
                // pollute error messages or trip pattern-matching logic.
                String cleanLog = VMManager.stripGlDiagnostics(log);

                // Check exit code FIRST — regardless of whether there is output.
                // QEMU can print BIOS/kernel lines to stdout and still be killed by
                // Android OOM mid-boot, leaving a non-empty log. Without this check
                // the OOM reason was never shown; instead BIOS output appeared as the
                // "error message".
                if (status == 137 || status == -9) {
                    // SIGKILL — Android OOM killer or explicit kill
                    final String oomReason = "QEMU was killed by Android (out of memory).\n"
                            + "Exit code: " + status + "\n\n"
                            + "Try reducing VM RAM or closing background apps.";
                    new Handler(Looper.getMainLooper()).post(() ->
                            DialogUtils.oneDialog(context,
                                    context.getString(R.string.problem_has_been_detected),
                                    oomReason,
                                    R.drawable.error_96px));
                    VmServiceManager.stopService(context);
                    return;
                }

                if (status == 127) {
                    final String reason = "QEMU binary not found (exit code 127).\n"
                            + "The Termux environment may not be installed correctly.";
                    new Handler(Looper.getMainLooper()).post(() ->
                            DialogUtils.oneDialog(context,
                                    context.getString(R.string.problem_has_been_detected),
                                    reason, R.drawable.error_96px));
                    VmServiceManager.stopService(context);
                    return;
                }

                if (status == 126) {
                    final String reason = "QEMU binary is not executable (exit code 126).\n"
                            + "Try reinstalling the app.";
                    new Handler(Looper.getMainLooper()).post(() ->
                            DialogUtils.oneDialog(context,
                                    context.getString(R.string.problem_has_been_detected),
                                    reason, R.drawable.error_96px));
                    VmServiceManager.stopService(context);
                    return;
                }

                // For all other non-zero exits: show the QEMU output if available,
                // or a generic message with the exit code if the log is empty.
                if (status != 0 && cleanLog.trim().isEmpty()) {
                    final String reason = "QEMU exited unexpectedly with no output.\n"
                            + "Exit code: " + status;
                    new Handler(Looper.getMainLooper()).post(() ->
                            DialogUtils.oneDialog(context,
                                    context.getString(R.string.problem_has_been_detected),
                                    reason, R.drawable.error_96px));
                    VmServiceManager.stopService(context);
                    return;
                }

                if (!(cleanLog.trim().isEmpty() || cleanLog.trim().equals(MainStartVM.TAG_FINISHED_WITHOUT_ERROR))) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!VMManager.isExecutedCommandError(command, cleanLog, context)) {
                            String finalLog = cleanLog.contains(MainStartVM.TAG_FINISHED_WITHOUT_ERROR)
                                    ? cleanLog.substring(0, cleanLog.lastIndexOf(MainStartVM.TAG_FINISHED_WITHOUT_ERROR) - 1)
                                    : cleanLog;
                            if (finalLog.trim().isEmpty()) return;

                            // Append exit code to the log when non-zero so the user
                            // always sees it alongside the QEMU output.
                            if (status != 0) {
                                finalLog += "\n\nExit code: " + status;
                            }

                            final String displayLog = finalLog;
                            DialogUtils.twoDialog(context, vmName, displayLog,
                                    context.getString(R.string.copy),
                                    context.getString(R.string.close),
                                    true, R.drawable.stack_24px, true,
                                    () -> ClipboardUltils.copyToClipboard(context, log),
                                    null, null);
                        }
                    });
                }

                VmServiceManager.stopService(context);
            }

            @Override
            public void onError(String command, Exception exception) {
                if (context instanceof Activity activity) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                } else {
                    Log.e(TAG, "context is not an Activity");
                    return;
                }

                new Handler(Looper.getMainLooper()).post(() -> DialogUtils.twoDialog(context, "Execution Result", exception.getMessage(), context.getString(R.string.copy), context.getString(R.string.close), true, R.drawable.round_terminal_24, true,
                        () -> ClipboardUltils.copyToClipboard(context, exception.getMessage()), null, null));
            }
        });
    }
}
