package com.mapcontrol.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.mapcontrol.R;
import com.mapcontrol.service.GlobalBackService;
import com.mapcontrol.ui.activity.MainActivity;
import com.mapcontrol.util.AppLaunchHelper;
import com.mapcontrol.util.MapsLink;

import java.util.List;
import java.util.function.Consumer;

/**
 * Web sunucusu isteklerinin uygulama bağlamıyla yapılan işleri ({@link WebServerHub}): bağlantı açma, odaklı alana yazma ve
 * FrostNav ekranı yokken uygulama silme/açma. APK kurulumu PackageInstaller oturumu için etkinlik ister; FrostNav
 * {@link MainActivity#EXTRA_INSTALL_APK} ile öne getirilip kurulum orada yapılır. Tüm yöntemler ana iş parçacığında çağrılır.
 */
final class WebServerActions implements WebServerHub.ActionHost {

    private final Context context;
    private final Consumer<String> log;
    private final Consumer<String> pendingInstall;
    private final Handler handler = new Handler(Looper.getMainLooper());

    WebServerActions(Context appContext, Consumer<String> log, Consumer<String> pendingInstall) {
        this.context = appContext;
        this.log = log;
        this.pendingInstall = pendingInstall;
    }

    // --- Uygulamalar (FrostNav ekranı yokken) ---

    @Override
    public void installApk(String fileName) {
        try {
            // Dosya adı intent'te değil hub'da bekler; extra yalnızca işarettir.
            pendingInstall.accept(fileName);
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_INSTALL_APK, true));
            log.accept("APK kurulumu için FrostNav açılıyor: " + fileName);
        } catch (Exception e) {
            log.accept("[ERROR] APK kurulumu için FrostNav açılamadı: " + e.getMessage());
        }
    }

    @Override
    public void deleteApp(String packageName) {
        if (context.getPackageName().equals(packageName)) {
            log.accept("Bu uygulama silinemez: " + packageName);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            log.accept("Uygulama silme başlatıldı: " + packageName);
        } catch (Exception e) {
            log.accept("Uygulama silme hatası: " + e.getMessage());
        }
    }

    @Override
    public void launchApp(String packageName) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                log.accept("Uygulama açılamadı (launch intent bulunamadı): " + packageName);
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launchIntent);
            log.accept("Uygulama açıldı: " + packageName);
        } catch (Exception e) {
            log.accept("Uygulama açma hatası: " + e.getMessage());
        }
    }

    // --- Klavye ---

    void typeText(String text, boolean showDeviceFeedback) {
        if (!GlobalBackService.isRegisteredInSystemAccessibilitySettings(context)) {
            if (showDeviceFeedback) {
                Toast.makeText(context,
                        "Ayarlar > Erişilebilirlik: Global Back servisini açın; diğer uygulamada girdi alanının odaklı olması gerekir.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (GlobalBackService.typeIntoFocusedField(context, text)) {
            if (showDeviceFeedback) {
                Toast.makeText(context, R.string.web_text_sent, Toast.LENGTH_SHORT).show();
            }
        } else {
            if (showDeviceFeedback) {
                Toast.makeText(context,
                        "Metin eklenemedi. Hedef uygulamada arama/Metin alanına bir kez dokunup odağın açık olduğundan emin olun (bazı uygulamalar ağaçta desteklemez).",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Bağlantılar ---

    void openUrl(String url) {
        try {
            String u = url != null ? url.trim() : "";
            if (u.isEmpty()) {
                return;
            }
            // Google Haritalar bağlantısı: tarayıcı seçicisine düşmeden doğrudan Google Haritalar'a ver.
            if (MapsLink.isGoogleMaps(u) && openGoogleMapsLink(u)) {
                return;
            }
            openLinkViaSystem(u);
        } catch (Exception e) {
            Toast.makeText(context, "Link açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
            log.accept("Link açma hatası: " + e.getMessage());
        }
    }

    /**
     * Aynı host’ta /q açılırken /maps gibi yollarda Yandex Haritalar (veya App Link) intent’i alıp
     * ekran göstermeyebiliyor; doğrudan tarayıcı hiç seçilmiyor. Bu URL’lerde önce seçici gösterilir.
     */
    /** Bağlantıyı sistemin seçtiği uygulamayla açar; bazı harita bağlantılarında önce seçici gösterir. */
    private void openLinkViaSystem(String u) {
        try {
            Uri uri = Uri.parse(u);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String scheme = uri.getScheme();
            if (scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                i.addCategory(Intent.CATEGORY_BROWSABLE);
            }
            if (shouldShowChooserToPickBrowserOrMaps(uri)) {
                // Yandex /maps, goo.gl harita kısası: önce tek hedef uyg. sessizce çalışıp
                // ekran göstermeyebiliyor; listeden tarayıcı seçilebilir.
                Intent chooser = Intent.createChooser(i, "Linki aç (tarayıcı önerilir)");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
            } else {
                try {
                    context.startActivity(i);
                } catch (android.content.ActivityNotFoundException noHandler) {
                    Intent chooser = Intent.createChooser(i, "Bağlantıyı aç");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                }
            }
        } catch (Exception e) {
            Toast.makeText(context, "Link açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
            log.accept("Link açma hatası: " + e.getMessage());
        }
    }

    /**
     * Google Haritalar bağlantısını doğrudan Google Haritalar'a verir (açık paket adıyla, seçici yok).
     * Sırayla dener: bağlantının kendisi, koordinat varsa rota/konum, yer adı varsa arama. Kısa bağlantıda
     * (maps.app.goo.gl) koordinat bağlantıda yoktur; yönlendirme arka planda çözülür.
     *
     * @return Google Haritalar kurulu değilse false (çağıran sistemin yoluna döner); aksi halde true
     */
    private boolean openGoogleMapsLink(String u) {
        if (!isPackageInstalled(AppLaunchHelper.GOOGLE_MAPS_PACKAGE)) {
            log.accept("Google Haritalar kurulu değil; sistem yoluyla açılıyor");
            return false;
        }
        if (startGoogleMaps(Uri.parse(u), "bağlantı")) {
            return true;
        }
        if (openGoogleMapsTarget(u)) {
            return true;
        }
        if (MapsLink.isShortLink(u)) {
            log.accept("Kısa bağlantı çözülüyor: " + u);
            new Thread(() -> {
                String resolved = MapsLink.resolve(u);
                handler.post(() -> {
                    log.accept("Kısa bağlantı çözüldü: " + resolved);
                    if (!resolved.equals(u) && (startGoogleMaps(Uri.parse(resolved), "çözülen bağlantı")
                            || openGoogleMapsTarget(resolved))) {
                        return;
                    }
                    openLinkViaSystem(u);
                });
            }, "MapsLinkResolve").start();
            return true;
        }
        return false;
    }

    /** Bağlantıdan koordinat ya da yer adı çıkarıp Google Haritalar'ın anladığı bir adresle açar. */
    private boolean openGoogleMapsTarget(String url) {
        double[] c = MapsLink.coordinates(url);
        if (c != null) {
            return startGoogleMaps(Uri.parse(MapsLink.navigationUri(c[0], c[1])), "rota")
                    || startGoogleMaps(Uri.parse(MapsLink.geoUri(c[0], c[1])), "konum");
        }
        String name = MapsLink.placeName(url);
        return name != null && startGoogleMaps(Uri.parse(MapsLink.searchUri(name)), "yer araması");
    }

    private boolean startGoogleMaps(Uri uri, String what) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.setPackage(AppLaunchHelper.GOOGLE_MAPS_PACKAGE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            i.addCategory(Intent.CATEGORY_BROWSABLE);
        }
        List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(i, 0);
        if (handlers.isEmpty()) {
            // Hangi biçimleri anladığı araçta bilinmediği için tanı kaydı: Günlük sekmesinde görünür.
            log.accept("Google Haritalar bu biçimi açmıyor (" + what + "): " + uri.getScheme() + ":");
            return false;
        }
        try {
            context.startActivity(i);
            log.accept("Google Haritalar'a verildi (" + what + "): " + uri);
            return true;
        } catch (Exception e) {
            log.accept("Google Haritalar açılamadı (" + what + "): " + e.getMessage());
            return false;
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean shouldShowChooserToPickBrowserOrMaps(Uri uri) {
        if (uri == null) {
            return false;
        }
        String h = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        String p = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
        if (h.contains("yandex") && p.contains("maps")) {
            return true;
        }
        if (h.equals("maps.app.goo.gl")) {
            return true;
        }
        return false;
    }
}
