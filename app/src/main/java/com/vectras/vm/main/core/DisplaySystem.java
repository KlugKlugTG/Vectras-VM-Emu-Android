package com.vectras.vm.main.core;

import static android.os.Build.VERSION.SDK_INT;
import static com.vectras.vm.utils.LibraryChecker.isPackageInstalled2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.app.TermuxService;
import com.vectras.qemu.Config;
import com.vectras.qemu.MainSettingsManager;
import com.vectras.qemu.MainVNCActivity;
import com.vectras.vm.R;
import com.vectras.vm.VectrasApp;
import com.vectras.vm.core.ShellExecutor;
import com.vectras.vm.core.TermuxX11;
import com.vectras.vm.utils.DialogUtils;
import com.vectras.vm.utils.FileUtils;
import com.vectras.vm.utils.PackageUtils;
import com.vectras.vm.x11.X11Activity;
import com.vectras.vterm.Terminal;
import com.vectras.vterm.Terminal2;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class DisplaySystem {
    private static final String TAG = "DisplaySystem";
    private static boolean isTermuxClassLoaded = false;

    public static boolean isUseBuiltInX11() {
        //return SDK_INT < 34 && DeviceUtils.isArm();
        return !MainSettingsManager.getExternalX11(VectrasApp.getContext());
    }

    public static void launch(Context context) {
        if (MainSettingsManager.getVmUi(context).equals("VNC")) {
            context.startActivity(new Intent(context, MainVNCActivity.class));
        } else if (MainSettingsManager.getVmUi(context).equals("X11")) {
            DisplaySystem.launchX11(context, false);
        }
    }

    public static void reLaunchVNC(Activity activity) {
        if (MainSettingsManager.getVmUi(activity).equals("VNC") &&
                FileUtils.isFileExists(Config.getLocalQMPSocketPath()) &&
                !activity.isFinishing() &&
                MainVNCActivity.started)
            activity.startActivity(new Intent(activity, MainVNCActivity.class));
    }

    public static void launchX11(Context context, boolean isKill) {
        if (!isUseBuiltInX11() && !PackageUtils.isInstalled("com.termux.x11", context)) {
            DialogUtils.needInstallTermuxX11(context);
            return;
        }

        // XFCE4 meta-package
        String necessaryPackage = "fluxbox";

        // Check if XFCE4 is installed
        isPackageInstalled2(context, necessaryPackage, (output, errors) -> {
            boolean isInstalled = false;

            // Check if the package exists in the installed packages output
            if (output != null) {
                Set<String> installedPackages = new HashSet<>();
                for (String installedPackage : output.split("\n")) {
                    installedPackages.add(installedPackage.trim());
                }

                isInstalled = installedPackages.contains(necessaryPackage.trim());
            }

            // If not installed, show a dialog to install it
            if (!isInstalled) {
                DialogUtils.twoDialog(
                        context,
                        context.getString(R.string.action_needed),
                        context.getString(R.string.the_required_package_is_not_installed_content),
                        context.getString(R.string.install),
                        context.getString(R.string.cancel),
                        true,
                        R.drawable.desktop_24px,
                        true,
                        () -> {
                            String installCommand = "apk add " + necessaryPackage + " && echo \"Installed: " + necessaryPackage + "\"";

                            Terminal2 terminal2 = new Terminal2(context);
                            terminal2.setShowProgressDialog(true);
                            terminal2.execute(installCommand, new Terminal2.Terminal2Callback() {
                                @Override
                                public void onRunning(String command, String newLine) {
                                    // Nothing to do.
                                }

                                @Override
                                public void onFinished(String command, String log, int status) {
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        if (status == terminal2.SUCCESS) {
                                            launchX11(context, isKill);
                                        } else {
                                            DialogUtils.oopsDialog(context, log);
                                        }
                                    });
                                }

                                @Override
                                public void onError(String command, Exception exception) {
                                    new Handler(Looper.getMainLooper()).post(() -> DialogUtils.oopsDialog(context, exception.getMessage()));
                                }
                            });
                        },
                        null,
                        null
                );
            } else {
                if (!isUseBuiltInX11() ) {
                    if (!PackageUtils.isInstalled("com.termux.x11", context)) {
                        DialogUtils.needInstallTermuxX11(context);
                        return;
                    }

                    Log.d(TAG, "launchX11: Opened: com.termux.x11.MainActivity.");
                    Intent intent = new Intent();
                    intent.setClassName("com.termux.x11", "com.termux.x11.MainActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    context.startActivity(intent);

                    startTermuxX11(context);
                } else {
                    context.startActivity(new Intent(context, X11Activity.class));
                }
                startDesktop(context);
            }
        });
    }

    /**
     * Builds the Mesa/OpenGL environment used by the host-side X11/VNC desktop and by
     * any GUI app launched from it.
     *
     * <p>On many devices (Adreno 750 included) the default Mesa loader tries the hardware
     * DRI3/DRI2 path and then the Zink driver (OpenGL over Vulkan). Inside the proot
     * environment there is no DRM render node and Zink cannot enumerate a Vulkan physical
     * device, which produces:
     * <pre>
     *   libEGL warning: DRI3: Screen seems not DRI3 capable
     *   MESA: error: ZINK: failed to choose pdev
     *   libEGL warning: egl: failed to create dri2 screen
     * </pre>
     * and leaves OpenGL completely broken.
     *
     * <p>To make OpenGL work reliably we:
     * <ul>
     *   <li>disable DRI3 probing ({@code LIBGL_DRI3_DISABLE=1}) so the "not DRI3 capable"
     *       warnings and the failed dri2 screen fallback go away;</li>
     *   <li>default to the software rasterizer (llvmpipe), which always renders;</li>
     *   <li>only switch to GPU-accelerated Zink when a working Vulkan device is actually
     *       reported by {@code vulkaninfo} <b>and</b> the user opted in by creating the file
     *       {@code ~/.vectras_gpu}. This prevents re-introducing the
     *       "failed to choose pdev" crash on devices where Zink/Turnip cannot pick a
     *       physical device.</li>
     * </ul>
     */
    public static String getGlEnvSetup() {
        return "export DISPLAY=:0; "
                + "export LIBGL_DRI3_DISABLE=1; "
                + "if [ -f \"$HOME/.vectras_gpu\" ] && command -v vulkaninfo >/dev/null 2>&1 "
                + "&& vulkaninfo --summary 2>/dev/null | grep -qi deviceName; then "
                + "export MESA_LOADER_DRIVER_OVERRIDE=zink; "
                + "export GALLIUM_DRIVER=zink; "
                + "export ZINK_DESCRIPTORS=lazy; "
                + "export TU_DEBUG=noconform; "
                + "export MESA_VK_WSI_PRESENT_MODE=immediate; "
                + "_VECTRAS_GL_MODE=gpu; "
                + "else "
                + "export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe; "
                + "export GALLIUM_DRIVER=llvmpipe; "
                + "export LIBGL_ALWAYS_SOFTWARE=1; "
                + "_VECTRAS_GL_MODE=software; "
                + "fi; "
                + "export MESA_GL_VERSION_OVERRIDE=4.6; "
                + "export MESA_GLSL_VERSION_OVERRIDE=460; "
                + "export vblank_mode=0; "
                // ---- GL diagnostics printed to the terminal ----
                // Mirrors the style seen in QEMU/mesapt output so the user can
                // immediately see which driver is active and what GL version Mesa reports.
                + "echo ''; "
                + "echo '=== Vectras GL init ==='; "
                + "echo \"vectras: mode         : $_VECTRAS_GL_MODE\"; "
                + "echo \"vectras: GALLIUM_DRIVER: $GALLIUM_DRIVER\"; "
                + "echo \"vectras: GL override   : $MESA_GL_VERSION_OVERRIDE (GLSL $MESA_GLSL_VERSION_OVERRIDE)\"; "
                // Try glxinfo -B first (gives vendor / renderer / version in one shot).
                // Fall back to printing the raw env vars when glxinfo is not installed.
                + "if command -v glxinfo >/dev/null 2>&1; then "
                + "  echo 'vectras: --- glxinfo -B ---'; "
                + "  glxinfo -B 2>&1 | grep -iE 'OpenGL|display|renderer|vendor|version|Mesa|llvmpipe|zink|turnip|freedreno' | sed 's/^/vectras: /'; "
                + "else "
                + "  echo 'vectras: (glxinfo not found — install mesa-demos for full GL info)'; "
                + "  echo \"vectras: MESA_LOADER_DRIVER_OVERRIDE=$MESA_LOADER_DRIVER_OVERRIDE\"; "
                + "fi; "
                + "echo '======================='; "
                + "echo ''";
    }

    /**
     * Returns true only when the Zink/GPU path will actually be used —
     * i.e. ~/.vectras_gpu exists AND vulkaninfo reports a device.
     *
     * This is used by StartVM to decide whether to pass gl=on and
     * virtio-vga-gl to QEMU.  With llvmpipe (software mode) gl=on causes
     * QEMU 11 to exit immediately with code 1 because llvmpipe does not
     * expose a usable EGL platform for the QEMU SDL/GTK display backend.
     */
    public static boolean isGpuModeAvailable(Context context) {
        String filesDir = context.getFilesDir().getAbsolutePath();
        java.io.File gpuFlag = new java.io.File(filesDir + "/distro/root/.vectras_gpu");
        return gpuFlag.exists();
    }

    public static void startDesktop(Context context) {
        Terminal2 terminal2 = new Terminal2(context);
        terminal2.setDefaultShellBash();
        terminal2.execute(getGlEnvSetup() + " && fluxbox > /dev/null");
    }

    public static void startTermuxX11(Context context) {
        if (isTermuxClassLoaded || !MainSettingsManager.getVmUi(context).equals("X11")) return;
        isTermuxClassLoaded = true;

        Log.d(TAG, "startTermuxX11...");
        if (isUseBuiltInX11()) {
            if (SDK_INT >= 34) {
                File loaderApk = new File(TermuxService.PREFIX_PATH + "/libexec/termux-x11/loader.apk");
                loaderApk.setWritable(false, false);
            }

            ShellExecutor shellExec = new ShellExecutor();
            shellExec.exec(TermuxService.PREFIX_PATH + "/bin/termux-x11 :0");
        } else {
            if (PackageUtils.isInstalled("com.termux.x11", context)){
                try {
                    TermuxX11.main(new String[]{":0"});
                } catch (Exception e) {
                    Log.e(TAG, "TermuxX11.main: ", e);
                }
            }
        }
    }
}
