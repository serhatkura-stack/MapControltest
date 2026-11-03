package com.mapcontrol.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mapcontrol.ui.builder.FileUploadTabBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web sunucusunun (7462) süreç genelindeki tek sahibi. FrostNav'ın Aktar sekmesi ve launcher (web paneli widget'ı, Wi-Fi
 * paneli) aynı örneği başlatır/durdurur ve durumunu dinler; sunucu FrostNav ekranı kapanınca durmaz, kullanıcı kapatana ya
 * da süreç ölene dek çalışır.
 *
 * <ul>
 *   <li>Yalnızca uygulama bağlamını tutar; etkinlik referansı {@link ActionHost} olarak zayıf tutulur.</li>
 *   <li>Başlat/durdur sıralı bir arka plan iş parçacığında çalışır (durdurma 1 sn'ye kadar bekleyebilir; ana iş parçacığı
 *       bloklanmaz). Aynı anda gelen çağrılar sırayla işlenir; çalışırken başlat, dururken durdur etkisizdir.</li>
 *   <li>Durum ve günlük dinleyicileri ana iş parçacığında çağrılır; yeni dinleyici güncel durumu hemen alır.</li>
 *   <li>APK kurma / uygulama silme / açma, FrostNav açıkken onun ekranına ({@link #setActionHost}) gider; değilse
 *       {@link WebServerActions} uygulama bağlamıyla yapar (APK kurulumu için FrostNav'ı öne getirir).</li>
 * </ul>
 */
public final class WebServerHub {

    /** Sunucu durumu: [url] çalışırken FrostNav'ın gösterdiği "http://ip:port"; [error] son başlatma/çalışma hatası. */
    public static final class State {
        public static final State STOPPED = new State(false, null, null);

        public final boolean running;
        public final String url;
        public final String error;

        public State(boolean running, String url, String error) {
            this.running = running;
            this.url = url;
            this.error = error;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State s = (State) o;
            return running == s.running && eq(url, s.url) && eq(error, s.error);
        }

        @Override
        public int hashCode() {
            int h = running ? 1 : 0;
            h = 31 * h + (url != null ? url.hashCode() : 0);
            return 31 * h + (error != null ? error.hashCode() : 0);
        }

        @Override
        public String toString() {
            return "State{running=" + running + ", url=" + url + ", error=" + error + "}";
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** Ana iş parçacığında çağrılır. */
    public interface StateListener {
        void onStateChanged(State state);

        /** Sunucunun günlük satırları (FrostNav'ın ekran günlüğü için). */
        default void onLog(String message) {
        }
    }

    /** Etkinlik gerektiren / FrostNav ekranındaki işler; ana iş parçacığında çağrılır. */
    public interface ActionHost {
        void installApk(String fileName);

        void deleteApp(String packageName);

        void launchApp(String packageName);
    }

    /** Başlatma sonucu (ana iş parçacığında): null = çalışıyor, değilse hata metni. */
    public interface StartCallback {
        void onResult(String error);
    }

    /** Sunucu arka ucu ({@link WebServerManager}); testlerde sahtesi verilir. */
    interface Backend {
        void setListener(WebServerManager.WebServerListener listener);

        void start();

        void stop();

        boolean isRunning();

        String currentHost();
    }

    private static volatile WebServerHub instance;

    public static WebServerHub get(Context context) {
        WebServerHub h = instance;
        if (h != null) return h;
        synchronized (WebServerHub.class) {
            if (instance == null) {
                Context app = context.getApplicationContext();
                Handler main = new Handler(Looper.getMainLooper());
                ExecutorService io = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "WebServerHub");
                    t.setDaemon(true);
                    return t;
                });
                instance = new WebServerHub(app, managerBackend(new WebServerManager(app)), main::post, io);
            }
            return instance;
        }
    }

    private static Backend managerBackend(WebServerManager m) {
        return new Backend() {
            @Override public void setListener(WebServerManager.WebServerListener l) { m.setListener(l); }
            @Override public void start() { m.startServer(); }
            @Override public void stop() { m.stopServer(); }
            @Override public boolean isRunning() { return m.isRunning(); }
            @Override public String currentHost() { return m.currentHost(); }
        };
    }

    private final Context appContext;
    private final Backend backend;
    private final Executor main;
    private final Executor io;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final List<StartCallback> pendingStarts = new ArrayList<>();
    private volatile State state = State.STOPPED;
    private volatile WeakReference<ActionHost> actionHost = new WeakReference<>(null);
    private WebServerActions defaultActions;
    /** Web panelinden yüklenip FrostNav ekranında kurulmayı bekleyen APK (intent ile taşınmaz; dışarıdan sahte intent kuramaz). */
    private volatile String pendingInstall;

    /** [appContext] testlerde null olabilir (varsayılan eylemler ve tercih okuması kullanılmaz). */
    WebServerHub(Context appContext, Backend backend, Executor main, Executor io) {
        this.appContext = appContext;
        this.backend = backend;
        this.main = main;
        this.io = io;
        backend.setListener(new ServerEvents());
    }

    // --- Denetim ---

    public void start() {
        start(null);
    }

    /** Sunucuyu başlatır (çalışıyorsa bir şey yapmaz); [callback] varsa sonucu bildirilir. */
    public void start(StartCallback callback) {
        if (callback != null) {
            synchronized (pendingStarts) {
                pendingStarts.add(callback);
            }
        }
        io.execute(() -> {
            if (backend.isRunning()) {
                resolveStarts(null);
                return;
            }
            backend.start();
        });
    }

    /** Sunucuyu durdurur (çalışmıyorsa bir şey yapmaz); bekleme arka planda. */
    public void stop() {
        io.execute(() -> {
            if (backend.isRunning()) {
                backend.stop();
            }
        });
    }

    public synchronized void toggle() {
        if (isRunning()) stop();
        else start();
    }

    public boolean isRunning() {
        return backend.isRunning();
    }

    /** Çalışırken "http://ip:port", değilse null. */
    public String currentUrl() {
        return state.url;
    }

    public State state() {
        return state;
    }

    /** Telefonun bağlanacağı güncel adres (hotspot sonradan açılırsa değişmiş olabilir). */
    public String currentHost() {
        return backend.currentHost();
    }

    /** Kullanıcı Aktar sekmesinde otomatik başlatmayı seçtiyse başlatır; varsayılan kapalı. */
    public void startIfPreferred() {
        if (appContext == null || isRunning()) return;
        if (appContext.getSharedPreferences("MapControlPrefs", Context.MODE_PRIVATE)
                .getBoolean(FileUploadTabBuilder.KEY_WEB_SERVER_AUTO_START, false)) {
            start();
        }
    }

    // --- Dinleyiciler ---

    /** Ekler ve güncel durumu hemen (ana iş parçacığında) bildirir. */
    public void addStateListener(StateListener l) {
        if (l == null || !listeners.addIfAbsent(l)) return;
        main.execute(() -> {
            if (listeners.contains(l)) l.onStateChanged(state);
        });
    }

    public void removeStateListener(StateListener l) {
        listeners.remove(l);
    }

    /** Son kaydedilen kazanır (aynı anda iki FrostNav penceresi olabilir). */
    public void setActionHost(ActionHost host) {
        actionHost = new WeakReference<>(host);
    }

    /** Yalnızca hâlâ [host] kayıtlıysa temizler. */
    public void clearActionHost(ActionHost host) {
        if (actionHost.get() == host) {
            actionHost = new WeakReference<>(null);
        }
    }

    void setPendingInstall(String fileName) {
        pendingInstall = fileName;
    }

    /** Bekleyen APK adını bir kez verir ve temizler; yoksa null. */
    public String takePendingInstall() {
        String f = pendingInstall;
        pendingInstall = null;
        return f;
    }

    // --- İç ---

    private void setState(State next) {
        synchronized (this) {
            if (next.equals(state)) return;
            state = next;
        }
        for (StateListener l : listeners) {
            main.execute(() -> {
                if (listeners.contains(l)) l.onStateChanged(next);
            });
        }
    }

    private void log(String message) {
        if (listeners.isEmpty()) return;
        main.execute(() -> {
            for (StateListener l : listeners) l.onLog(message);
        });
    }

    private void resolveStarts(String error) {
        List<StartCallback> done;
        synchronized (pendingStarts) {
            if (pendingStarts.isEmpty()) return;
            done = new ArrayList<>(pendingStarts);
            pendingStarts.clear();
        }
        main.execute(() -> {
            for (StartCallback cb : done) cb.onResult(error);
        });
    }

    /** Kayıtlı FrostNav ekranı yoksa uygulama bağlamıyla çalışan eylemler. */
    private synchronized WebServerActions defaults() {
        if (defaultActions == null) {
            defaultActions = new WebServerActions(appContext, this::log, this::setPendingInstall);
        }
        return defaultActions;
    }

    private void onAction(java.util.function.Consumer<ActionHost> action) {
        main.execute(() -> {
            ActionHost host = actionHost.get();
            if (host == null && appContext != null) host = defaults();
            if (host != null) action.accept(host);
        });
    }

    /** Sunucu ve istemci iş parçacıklarından gelir. */
    private final class ServerEvents implements WebServerManager.WebServerListener {
        @Override
        public void onServerStarted(int port, String localIp) {
            String url = "http://" + localIp + ":" + port;
            setState(new State(true, url, null));
            log("Web Server başlatıldı: " + url);
            resolveStarts(null);
        }

        @Override
        public void onServerStopped() {
            setState(State.STOPPED);
            log("Web Server durduruldu");
        }

        @Override
        public void onError(String error) {
            State s = state;
            setState(new State(s.running, s.url, error));
            log("Web Server hatası: " + error);
            resolveStarts(error);
        }

        @Override
        public void onInstallApk(String fileName) {
            onAction(h -> h.installApk(fileName));
        }

        @Override
        public void onDeleteApp(String packageName) {
            onAction(h -> h.deleteApp(packageName));
        }

        @Override
        public void onLaunchApp(String packageName) {
            onAction(h -> h.launchApp(packageName));
        }

        @Override
        public void onLog(String message) {
            log(message);
        }

        @Override
        public void onOpenMapUrl(String url) {
            if (appContext == null) return;
            main.execute(() -> defaults().openUrl(url));
        }

        @Override
        public void onTypeKeyboardText(String text, boolean showDeviceFeedback) {
            if (appContext == null) return;
            main.execute(() -> defaults().typeText(text, showDeviceFeedback));
        }
    }
}
