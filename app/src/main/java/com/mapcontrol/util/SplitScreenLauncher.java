package com.mapcontrol.util;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mapcontrol.admin.MapControlDeviceAdminReceiver;

/**
 * Ekranı iki bitişik pencereye böler. Android'in hazır split API'si kullanılmaz; kullanılabilir alan
 * iki dikdörtgene ayrılır ve her uygulama {@code WINDOWING_MODE_FREEFORM} (5) ile kendi
 * dikdörtgeninde açılır. Mantık MyCar'ın freeform yolundan ({@code C0320p4.u/r}) alınmıştır.
 *
 * <p>Mode 6 (Desay launcher oturumu) bilerek taşınmadı: hidden API muafiyeti ve launcher
 * sözleşmesi gerektirir, her araç imajında yoktur.
 */
public final class SplitScreenLauncher {

    private static final String TAG = "SplitScreen";

    /** {@code Settings.Global}: freeform pencere desteğini açar (MyCar {@code RealApplication.b}). */
    private static final String SETTING_ENABLE_FREEFORM = "enable_freeform_support";
    private static final String FEATURE_FREEFORM = "android.software.freeform_window_management";

    /** {@code ActivityOptions.WINDOWING_MODE_FREEFORM}. */
    private static final int WINDOWING_MODE_FREEFORM = 5;
    /** Süreç düşürüldükten sonra beklenen süre (MyCar ile aynı). */
    private static final long RECYCLE_WAIT_MS = 600L;
    /** NEW_TASK | RESET_TASK_IF_NEEDED | REORDER_TO_FRONT (MyCar: 270663680). */
    private static final int LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;

    private SplitScreenLauncher() {
    }

    /** İlk uygulamanın ekrandaki payı. Kimlikler MyCar ile aynıdır. */
    public enum Ratio {
        LEFT_THIRD("left_third", 1f / 3f),
        HALF("half", 0.5f),
        RIGHT_THIRD("right_third", 2f / 3f);

        public final String id;
        public final float firstShare;

        Ratio(String id, float firstShare) {
            this.id = id;
            this.firstShare = firstShare;
        }

        public static Ratio fromId(@Nullable String id) {
            for (Ratio r : values()) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
            return HALF;
        }
    }

    public enum Result {
        STARTED,
        /** Freeform açık değil ve açılamadı. */
        NO_FREEFORM,
        SAME_APP,
        NOT_INSTALLED,
        BAD_BOUNDS,
        LAUNCH_FAILED
    }

    public interface Callback {
        /** Ana iş parçacığında çağrılır. */
        void onResult(@NonNull Result result);
    }

    /** Başlatmadan önce kullanıcıya gösterilen uyumluluk özeti. */
    public static final class Capability {
        /** Sistem şu anda freeform pencere kabul ediyor. */
        public final boolean freeformOn;
        /** Kapalıysa uygulama açabilir ({@code WRITE_SECURE_SETTINGS}). */
        public final boolean canEnableFreeform;
        /** Device owner ise çalışan uygulamalar yeniden başlatılıp boyutu doğru alır. */
        public final boolean deviceOwner;

        Capability(boolean freeformOn, boolean canEnableFreeform, boolean deviceOwner) {
            this.freeformOn = freeformOn;
            this.canEnableFreeform = canEnableFreeform;
            this.deviceOwner = deviceOwner;
        }

        public boolean usable() {
            return freeformOn || canEnableFreeform;
        }
    }

    @NonNull
    public static Capability probe(@NonNull Context context) {
        boolean on = isFreeformOn(context);
        boolean canEnable = !on && hasSecureSettingsPermission(context);
        return new Capability(on, canEnable, isDeviceOwner(context));
    }

    private static boolean isFreeformOn(Context context) {
        try {
            if (context.getPackageManager().hasSystemFeature(FEATURE_FREEFORM)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_ENABLE_FREEFORM, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasSecureSettingsPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isDeviceOwner(Context context) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Freeform kapalıysa açmayı dener. Sistem ayarı yalnızca bu çağrıda değişir ve geri alınmaz;
     * açık pencereler bozulmasın diye.
     *
     * @return freeform bu çağrıdan sonra açık mı
     */
    private static boolean ensureFreeform(Context context) {
        if (isFreeformOn(context)) {
            return true;
        }
        if (!hasSecureSettingsPermission(context)) {
            return false;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), SETTING_ENABLE_FREEFORM, 1);
            Log.i(TAG, "enable_freeform_support=1 yazıldı");
        } catch (Exception e) {
            Log.w(TAG, "enable_freeform_support yazılamadı", e);
            return false;
        }
        return isFreeformOn(context);
    }

    /**
     * Arka planda başlatır; sonuç ana iş parçacığında bildirilir.
     */
    public static void start(
            @NonNull Context context,
            @NonNull String pkg1,
            @NonNull String pkg2,
            @NonNull Ratio ratio,
            @Nullable Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result result;
            try {
                result = startBlocking(app, pkg1, pkg2, ratio);
            } catch (Exception e) {
                Log.w(TAG, "split start failed", e);
                result = Result.LAUNCH_FAILED;
            }
            final Result finalResult = result;
            if (callback != null) {
                main.post(() -> callback.onResult(finalResult));
            }
        }, "split-screen-start").start();
    }

    private static Result startBlocking(Context app, String pkg1, String pkg2, Ratio ratio)
            throws InterruptedException {
        if (pkg1.equals(pkg2)) {
            return Result.SAME_APP;
        }
        String self = app.getPackageName();
        if (!SplitSlot.isAllowed(pkg1, self) || !SplitSlot.isAllowed(pkg2, self)) {
            return Result.NOT_INSTALLED;
        }
        if (launchIntent(app, pkg1) == null || launchIntent(app, pkg2) == null) {
            return Result.NOT_INSTALLED;
        }

        boolean wasOn = isFreeformOn(app);
        if (!ensureFreeform(app)) {
            return Result.NO_FREEFORM;
        }

        // Açık görev yeni sınırları almaz; MyCar süreci düşürür. Yalnızca device owner iken mümkün.
        boolean recycled = recycleProcess(app, pkg1);
        recycled |= recycleProcess(app, pkg2);
        // Kendi Mini göstergemiz (singleTask, ayrı görev) süreç düşürmeyle yenilenemez; açık görevi kapatılır ki yeni
        // sınırlarla açılsın (yoksa eski tam ekran/boyutuyla öne gelir).
        recycled |= closeOwnMiniDashTask(app, pkg1, self);
        recycled |= closeOwnMiniDashTask(app, pkg2, self);
        if (recycled || !wasOn) {
            Thread.sleep(RECYCLE_WAIT_MS);
        }

        Rect usable = usableBounds(app);
        if (usable.width() <= 0 || usable.height() <= 0) {
            Log.e(TAG, "Invalid usable bounds: " + usable);
            return Result.BAD_BOUNDS;
        }
        Rect[] parts = split(usable, ratio);
        // Serbest pencere başlığı (küçült/kapat, ~42 px) pencerenin içinde çizilir; üst kenarı kullanılabilir alanın üstüne
        // değen yarılar başlık kadar yukarı alınır ki başlık durum çubuğunun altında kalsın ve içerik tam yarıyı alsın.
        int caption = captionHeightPx(app);
        for (Rect part : parts) {
            part.top = tuckedTop(part.top, usable.top, caption);
        }
        boolean first = launchFreeform(app, pkg1, parts[0]);
        boolean second = launchFreeform(app, pkg2, parts[1]);
        Log.d(TAG, "first=" + first + " second=" + second + " usable=" + usable);
        return first && second ? Result.STARTED : Result.LAUNCH_FAILED;
    }

    /**
     * Kutu için açılış intent'i: "pkg/cls" ise açık bileşen, değilse paketin launcher intent'i.
     */
    @Nullable
    private static Intent launchIntent(Context context, String slot) {
        PackageManager pm = context.getPackageManager();
        if (!SplitSlot.isComponent(slot)) {
            return pm.getLaunchIntentForPackage(slot);
        }
        ComponentName cn = new ComponentName(SplitSlot.packageOf(slot), SplitSlot.classOf(slot));
        try {
            pm.getActivityInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return new Intent(Intent.ACTION_MAIN).setComponent(cn);
    }

    /**
     * Mini gösterge solda, verilen diğer uygulama sağda, yarı yarıya bölünmüş ekran açar.
     * Launcher bunu ileride çağırır; arayüz bağlanmadı. Diğer kutu boşsa ya da Mini göstergenin
     * kendisiyse {@link Result#NOT_INSTALLED} döner.
     *
     * @param otherApp paket adı ya da "pkg/cls" bileşen dizesi; genelde {@link #lastOtherApp}
     */
    public static void startWithMiniDash(
            @NonNull Context context, @Nullable String otherApp, @Nullable Callback callback) {
        Context app = context.getApplicationContext();
        String self = app.getPackageName();
        if (otherApp == null || !SplitSlot.isAllowed(otherApp, self) || SplitSlot.isMiniDash(otherApp, self)) {
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(Result.NOT_INSTALLED));
            }
            return;
        }
        start(app, SplitSlot.miniDash(self), otherApp, Ratio.HALF, callback);
    }

    /** Kayıtlı bölünmüş ekran çiftinden Mini gösterge olmayan, en son seçilen uygulama (sağ kutu önce). */
    @Nullable
    public static String lastOtherApp(@NonNull Context context) {
        String self = context.getApplicationContext().getPackageName();
        String a = SplitScreenStore.getPkg1(context);
        String b = SplitScreenStore.getPkg2(context);
        if (b != null && !SplitSlot.isMiniDash(b, self)) {
            return b;
        }
        if (a != null && !SplitSlot.isMiniDash(a, self)) {
            return a;
        }
        return null;
    }

    /** Kutu Mini göstergeyse ve onun görevi açıksa görev kapatılır (kendi görevlerimiz; yetki gerekmez). */
    private static boolean closeOwnMiniDashTask(Context context, String slot, String self) {
        if (!SplitSlot.isMiniDash(slot, self)) {
            return false;
        }
        boolean closed = false;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            String cls = SplitSlot.classOf(slot);
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ComponentName base;
                try {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    base = info.baseIntent != null ? info.baseIntent.getComponent() : null;
                    if (base == null) {
                        base = info.baseActivity;
                    }
                } catch (IllegalArgumentException e) {
                    continue; // görev bu arada kapandı
                }
                if (base != null && self.equals(base.getPackageName()) && base.getClassName().equals(cls)) {
                    task.finishAndRemoveTask();
                    closed = true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mini dash task close failed", e);
        }
        return closed;
    }

    /** Paketi askıya alıp geri açarak çalışan süreci düşürür (MyCar {@code A()}). */
    private static boolean recycleProcess(Context context, String slot) {
        String packageName = SplitSlot.packageOf(slot);
        if (packageName.equals(context.getPackageName()) || !isDeviceOwner(context)) {
            return false;
        }
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, MapControlDeviceAdminReceiver.class);
            dpm.setPackagesSuspended(admin, new String[]{packageName}, true);
            dpm.setPackagesSuspended(admin, new String[]{packageName}, false);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "recycle failed for " + packageName, e);
            return false;
        }
    }

    /**
     * Android 11 serbest pencere başlığının yüksekliği (dp): {@code decor_caption.xml} 32 dp düğme + 2 × 5 dp kenar.
     * Araçta (160 dpi) 42 px; ekran görüntüsünde simgeler pencere üstünden ~21 px aşağıda ortalanıyor.
     */
    static final int CAPTION_HEIGHT_DP = 42;

    static int captionHeightPx(@NonNull Context context) {
        return Math.round(CAPTION_HEIGHT_DP * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Saf kural (birim testlenir): üst kenarı kullanılabilir alanın üstüne ({@code usableTop}, durum çubuğunun altı)
     * denk gelen pencere başlık kadar yukarı başlar; başlık durum çubuğunun altında kalır (durum çubuğu uygulama
     * pencerelerinin üstünde çizilir). Üstte başlığı saklayacak kadar alan yoksa ya da yarı aşağıdaysa (üst/alt bölmede
     * alttaki) değişmez: alttaki yarının başlığı üsttekinin içeriğini örtmesin. Pencere boyutu değiştirme kenarları aynen kalır.
     */
    static int tuckedTop(int partTop, int usableTop, int captionPx) {
        if (captionPx <= 0 || partTop != usableTop || usableTop < captionPx) {
            return partTop;
        }
        return partTop - captionPx;
    }

    /** Sistem çubukları ve kesim düşülmüş kullanılabilir alan (MyCar {@code o()}). */
    @NonNull
    static Rect usableBounds(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return new Rect();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowMetrics metrics = wm.getMaximumWindowMetrics();
                Rect rect = new Rect(metrics.getBounds());
                Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.navigationBars()
                                | WindowInsets.Type.statusBars());
                rect.left += insets.left;
                rect.top += insets.top;
                rect.right -= insets.right;
                rect.bottom -= insets.bottom;
                return rect;
            } catch (Throwable t) {
                Log.w(TAG, "WindowMetrics failed, falling back", t);
            }
        }
        try {
            Display display = wm.getDefaultDisplay();
            Rect rect = new Rect();
            display.getRectSize(rect);
            if (rect.width() <= 0 || rect.height() <= 0) {
                Point size = new Point();
                display.getRealSize(size);
                rect.set(0, 0, size.x, size.y);
            }
            return rect;
        } catch (Throwable t) {
            Log.e(TAG, "usable bounds failed", t);
            return new Rect();
        }
    }

    /** Tiggo 8 sınıfı ekran: uzun kenar bu pikselden uzun. */
    static final int TOP_BOTTOM_LONG_SIDE_PX = 2400;
    /** Tiggo 8 sınıfı ekran: en-boy oranı bundan küçük (Tiggo 7 1920x720 = 2,67). */
    static final float TOP_BOTTOM_MAX_ASPECT = 2.2f;

    /**
     * Saf kural (birim testlenir): yatay ve Tiggo 8 sınıfı (uzun kenar &gt;= 2400 px, oran &lt; 2,2) ekran üst/alt,
     * diğer yatay ekran sol/sağ, dikey ekran üst/alt bölünür. Böylece 2560x1440 ekranda her yarı 2560x~720 olur
     * (Tiggo 7 ekranıyla aynı oran).
     */
    static boolean sideBySideFor(int width, int height) {
        if (width < height) {
            return false;
        }
        if (height <= 0) {
            return true;
        }
        boolean tiggo8Class = width >= TOP_BOTTOM_LONG_SIDE_PX
                && (float) width / height < TOP_BOTTOM_MAX_ASPECT;
        return !tiggo8Class;
    }

    /** Sol/sağ ya da üst/alt; bölme ekseni {@link #sideBySideFor}. Oran bu eksene uygulanır. */
    @NonNull
    static Rect[] split(@NonNull Rect usable, @NonNull Ratio ratio) {
        float share = ratio.firstShare;
        if (sideBySideFor(usable.width(), usable.height())) {
            int edge = usable.left + (int) (usable.width() * share);
            return new Rect[]{
                    new Rect(usable.left, usable.top, edge, usable.bottom),
                    new Rect(edge, usable.top, usable.right, usable.bottom)};
        }
        int edge = usable.top + (int) (usable.height() * share);
        return new Rect[]{
                new Rect(usable.left, usable.top, usable.right, edge),
                new Rect(usable.left, edge, usable.right, usable.bottom)};
    }

    /** Ekranın yan yana mı bölüneceği; önizleme ile gerçek bölme aynı kuralı kullanır. */
    public static boolean splitsSideBySide(@NonNull Context context) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return sideBySideFor(dm.widthPixels, dm.heightPixels);
    }

    /** MyCar {@code r()}: pencere modu, sınırlar ve ekran 0. Anahtarlar birebir korunur. */
    private static boolean launchFreeform(Context context, String packageName, Rect bounds) {
        try {
            Intent intent = launchIntent(context, packageName);
            if (intent == null) {
                return false;
            }
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(LAUNCH_FLAGS);

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(new Rect(bounds));
            options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            Bundle bundle = options.toBundle();
            if (bundle == null) {
                bundle = new Bundle();
            }
            bundle.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            bundle.putInt("android:windowingMode", WINDOWING_MODE_FREEFORM);
            bundle.putParcelable("android.activity.launchBounds", new Rect(bounds));
            bundle.putParcelable("android:launchBounds", new Rect(bounds));
            bundle.putInt("android:freeformWindowSize", 1);
            bundle.putInt("android:multiWindowMode", 1);
            bundle.putBoolean("android:overrideTransition", true);
            context.startActivity(intent, bundle);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "launch failed: " + packageName, e);
            return false;
        }
    }
}
