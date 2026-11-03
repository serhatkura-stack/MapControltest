package com.mapcontrol.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** {@link WebServerHub} durum makinesi: sahte sunucu, eşzamanlı yürütücüler (ana ve arka plan doğrudan çalışır). */
public class WebServerHubTest {

    /** {@link WebServerManager} gibi davranır: başlatma geri çağrıyı gönderir, başarısızlıkta yalnızca onError. */
    private static final class FakeBackend implements WebServerHub.Backend {
        WebServerManager.WebServerListener listener;
        boolean running;
        int starts;
        int stops;
        String failWith;

        @Override public void setListener(WebServerManager.WebServerListener l) { listener = l; }

        @Override public void start() {
            starts++;
            if (failWith != null) {
                listener.onError(failWith);
                return;
            }
            running = true;
            listener.onServerStarted(7462, "192.168.1.5");
        }

        @Override public void stop() {
            stops++;
            running = false;
            listener.onServerStopped();
        }

        @Override public boolean isRunning() { return running; }

        @Override public String currentHost() { return "192.168.1.5"; }
    }

    private static final class Recorder implements WebServerHub.StateListener {
        final List<WebServerHub.State> states = new ArrayList<>();
        final List<String> logs = new ArrayList<>();

        @Override public void onStateChanged(WebServerHub.State state) { states.add(state); }

        @Override public void onLog(String message) { logs.add(message); }

        WebServerHub.State last() { return states.get(states.size() - 1); }
    }

    private FakeBackend backend;
    private WebServerHub hub;

    @Before
    public void setUp() {
        backend = new FakeBackend();
        hub = new WebServerHub(null, backend, Runnable::run, Runnable::run);
    }

    @Test
    public void newListenerGetsCurrentStateImmediately() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        assertEquals(1, r.states.size());
        assertEquals(WebServerHub.State.STOPPED, r.last());

        hub.start();
        Recorder late = new Recorder();
        hub.addStateListener(late);
        assertEquals(1, late.states.size());
        assertTrue(late.last().running);
        assertEquals("http://192.168.1.5:7462", late.last().url);
    }

    @Test
    public void startAndStopNotifyAndExposeState() {
        Recorder r = new Recorder();
        hub.addStateListener(r);

        hub.start();
        assertTrue(hub.isRunning());
        assertEquals("http://192.168.1.5:7462", hub.currentUrl());
        assertEquals(2, r.states.size());
        assertTrue(r.last().running);
        assertTrue(r.logs.contains("Web Server başlatıldı: http://192.168.1.5:7462"));

        hub.stop();
        assertFalse(hub.isRunning());
        assertNull(hub.currentUrl());
        assertEquals(3, r.states.size());
        assertEquals(WebServerHub.State.STOPPED, r.last());
    }

    @Test
    public void startIsIdempotent() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        hub.start();
        hub.start();
        hub.start();
        assertEquals(1, backend.starts);
        assertEquals(2, r.states.size());
    }

    @Test
    public void stopWhenStoppedDoesNothing() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        hub.stop();
        hub.start();
        hub.stop();
        hub.stop();
        assertEquals(1, backend.stops);
        assertEquals(3, r.states.size());
    }

    @Test
    public void toggleFlipsState() {
        hub.toggle();
        assertTrue(hub.isRunning());
        hub.toggle();
        assertFalse(hub.isRunning());
        assertEquals(1, backend.starts);
        assertEquals(1, backend.stops);
    }

    @Test
    public void startCallbackReportsSuccessAndAlreadyRunning() {
        List<String> results = new ArrayList<>();
        hub.start(results::add);
        hub.start(results::add);
        assertEquals(2, results.size());
        assertNull(results.get(0));
        assertNull(results.get(1));
        assertEquals(1, backend.starts);
    }

    @Test
    public void startFailureReportsErrorAndKeepsStopped() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        backend.failWith = "Server başlatma hatası: Address already in use";
        List<String> results = new ArrayList<>();
        hub.start(results::add);

        assertEquals(1, results.size());
        assertEquals("Server başlatma hatası: Address already in use", results.get(0));
        assertFalse(hub.isRunning());
        assertFalse(r.last().running);
        assertEquals("Server başlatma hatası: Address already in use", r.last().error);

        // Sonraki başarılı başlatma hatayı temizler.
        backend.failWith = null;
        hub.start();
        assertTrue(r.last().running);
        assertNull(r.last().error);
    }

    @Test
    public void removedListenerGetsNothing() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        hub.removeStateListener(r);
        hub.start();
        hub.stop();
        assertEquals(1, r.states.size());
        assertTrue(r.logs.isEmpty());
    }

    @Test
    public void listenerAddedTwiceIsNotifiedOnce() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        hub.addStateListener(r);
        hub.start();
        assertEquals(2, r.states.size());
    }

    @Test
    public void unchangedStateIsNotRepeated() {
        Recorder r = new Recorder();
        hub.addStateListener(r);
        hub.start();
        backend.listener.onServerStarted(7462, "192.168.1.5");
        assertEquals(2, r.states.size());
    }

    @Test
    public void listenerRemovedBeforeDeliveryIsSkipped() {
        List<Runnable> queued = new ArrayList<>();
        WebServerHub deferred = new WebServerHub(null, new FakeBackend(), queued::add, Runnable::run);
        Recorder r = new Recorder();
        deferred.addStateListener(r);
        deferred.removeStateListener(r);
        for (Runnable q : queued) q.run();
        assertTrue(r.states.isEmpty());
    }

    @Test
    public void actionHostIsClearedOnlyByItsOwner() {
        List<String> calls = new ArrayList<>();
        WebServerHub.ActionHost first = recordingHost("first", calls);
        WebServerHub.ActionHost second = recordingHost("second", calls);
        hub.setActionHost(first);
        hub.setActionHost(second);
        hub.clearActionHost(first);
        backend.listener.onLaunchApp("com.example");
        hub.clearActionHost(second);
        backend.listener.onLaunchApp("com.example");
        assertEquals(1, calls.size());
        assertEquals("second:launch:com.example", calls.get(0));
    }

    private static WebServerHub.ActionHost recordingHost(String name, List<String> calls) {
        return new WebServerHub.ActionHost() {
            @Override public void installApk(String fileName) { calls.add(name + ":install:" + fileName); }
            @Override public void deleteApp(String packageName) { calls.add(name + ":delete:" + packageName); }
            @Override public void launchApp(String packageName) { calls.add(name + ":launch:" + packageName); }
        };
    }
}
