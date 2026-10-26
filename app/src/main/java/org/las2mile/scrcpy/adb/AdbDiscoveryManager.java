package org.las2mile.scrcpy.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AdbDiscoveryManager automatically discovers Android devices on the local Wi-Fi network
 * advertising Wireless Debugging services via mDNS (DNS-SD):
 * 1. _adb-tls-connect._tcp (Android 11+ TLS active connection)
 * 2. _adb-tls-pairing._tcp (Android 11+ TLS pairing session)
 * 3. _adb._tcp (Standard/legacy ADB daemon on port 5555)
 */
public class AdbDiscoveryManager {
    private static final String TAG = "AdbDiscoveryManager";

    public static final String SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp";
    public static final String SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp";
    public static final String SERVICE_LEGACY_CONNECT = "_adb._tcp";

    public enum DeviceType {
        TLS_CONNECT,
        TLS_PAIRING,
        LEGACY_CONNECT
    }

    public static class DiscoveredDevice {
        private final String serviceName;
        private final String host;
        private final int port;
        private final DeviceType type;
        private final long timestamp;

        public DiscoveredDevice(String serviceName, String host, int port, DeviceType type) {
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getAddress() {
            return host + ":" + port;
        }

        public DeviceType getType() {
            return type;
        }

        public boolean isPairing() {
            return type == DeviceType.TLS_PAIRING;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getDisplayName() {
            if (serviceName != null && !serviceName.isEmpty()) {
                String name = serviceName;
                if (name.startsWith("adb-")) {
                    name = name.substring(4);
                }
                int dot = name.indexOf('.');
                if (dot != -1) {
                    name = name.substring(0, dot);
                }
                return name;
            }
            return host;
        }
    }

    public interface DiscoveryCallback {
        void onDeviceDiscovered(DiscoveredDevice device);
        void onDeviceLost(DiscoveredDevice device);
    }

    private final Context context;
    private final NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private DiscoveryCallback callback;
    private final Handler mainHandler;

    private final Map<String, DiscoveredDevice> discoveredDevices = new ConcurrentHashMap<>();
    private final Set<String> resolvingOrResolvedKeys = Collections.synchronizedSet(new HashSet<>());

    private final Queue<NsdServiceInfo> resolveQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isResolving = new AtomicBoolean(false);

    private final AtomicBoolean tlsConnectDiscovering = new AtomicBoolean(false);
    private final AtomicBoolean tlsPairingDiscovering = new AtomicBoolean(false);
    private final AtomicBoolean legacyConnectDiscovering = new AtomicBoolean(false);

    private NsdManager.DiscoveryListener tlsConnectListener;
    private NsdManager.DiscoveryListener tlsPairingListener;
    private NsdManager.DiscoveryListener legacyConnectListener;

    private Runnable resolveTimeoutRunnable;

    public AdbDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());

        try {
            WifiManager wm = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("scrcpy_mdns_lock");
                multicastLock.setReferenceCounted(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to create MulticastLock", e);
        }
    }

    public void setCallback(DiscoveryCallback callback) {
        this.callback = callback;
    }

    public List<DiscoveredDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }

    public DiscoveredDevice getDeviceByAddress(String address) {
        if (address == null) return null;
        for (DiscoveredDevice d : discoveredDevices.values()) {
            if (address.equalsIgnoreCase(d.getAddress()) || address.equalsIgnoreCase(d.getHost())) {
                return d;
            }
        }
        return null;
    }

    public synchronized void startDiscovery() {
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager is null, cannot start discovery");
            return;
        }

        acquireMulticastLock();

        // 1. Discover TLS Connect (_adb-tls-connect._tcp)
        if (tlsConnectDiscovering.compareAndSet(false, true)) {
            tlsConnectListener = createDiscoveryListener(SERVICE_TLS_CONNECT, tlsConnectDiscovering);
            try {
                nsdManager.discoverServices(SERVICE_TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, tlsConnectListener);
                Log.d(TAG, "Started discovery for " + SERVICE_TLS_CONNECT);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start discovery for " + SERVICE_TLS_CONNECT, e);
                tlsConnectDiscovering.set(false);
            }
        }

        // 2. Discover TLS Pairing (_adb-tls-pairing._tcp)
        if (tlsPairingDiscovering.compareAndSet(false, true)) {
            tlsPairingListener = createDiscoveryListener(SERVICE_TLS_PAIRING, tlsPairingDiscovering);
            try {
                nsdManager.discoverServices(SERVICE_TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, tlsPairingListener);
                Log.d(TAG, "Started discovery for " + SERVICE_TLS_PAIRING);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start discovery for " + SERVICE_TLS_PAIRING, e);
                tlsPairingDiscovering.set(false);
            }
        }

        // 3. Discover Legacy ADB (_adb._tcp)
        if (legacyConnectDiscovering.compareAndSet(false, true)) {
            legacyConnectListener = createDiscoveryListener(SERVICE_LEGACY_CONNECT, legacyConnectDiscovering);
            try {
                nsdManager.discoverServices(SERVICE_LEGACY_CONNECT, NsdManager.PROTOCOL_DNS_SD, legacyConnectListener);
                Log.d(TAG, "Started discovery for " + SERVICE_LEGACY_CONNECT);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start discovery for " + SERVICE_LEGACY_CONNECT, e);
                legacyConnectDiscovering.set(false);
            }
        }
    }

    public synchronized void stopDiscovery() {
        if (nsdManager == null) return;

        stopListenerSafely(tlsConnectListener, tlsConnectDiscovering, SERVICE_TLS_CONNECT);
        stopListenerSafely(tlsPairingListener, tlsPairingDiscovering, SERVICE_TLS_PAIRING);
        stopListenerSafely(legacyConnectListener, legacyConnectDiscovering, SERVICE_LEGACY_CONNECT);

        resolveQueue.clear();
        isResolving.set(false);
        if (resolveTimeoutRunnable != null) {
            mainHandler.removeCallbacks(resolveTimeoutRunnable);
            resolveTimeoutRunnable = null;
        }

        releaseMulticastLock();
    }

    private void stopListenerSafely(NsdManager.DiscoveryListener listener, AtomicBoolean state, String name) {
        if (listener != null && state.compareAndSet(true, false)) {
            try {
                nsdManager.stopServiceDiscovery(listener);
                Log.d(TAG, "Stopped discovery for " + name);
            } catch (Exception e) {
                Log.w(TAG, "Exception while stopping " + name + ": " + e.getMessage());
            }
        }
    }

    private NsdManager.DiscoveryListener createDiscoveryListener(String expectedServiceType, AtomicBoolean activeState) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Discovery started for: " + regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service found: " + service.getServiceName() + ", type: " + service.getServiceType());
                String key = service.getServiceType() + "::" + service.getServiceName();
                if (!resolvingOrResolvedKeys.contains(key)) {
                    resolvingOrResolvedKeys.add(key);
                    resolveQueue.add(service);
                    processNextResolve();
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.d(TAG, "Service lost: " + service.getServiceName());
                String key = service.getServiceType() + "::" + service.getServiceName();
                resolvingOrResolvedKeys.remove(key);

                DiscoveredDevice lostDevice = null;
                for (Map.Entry<String, DiscoveredDevice> entry : discoveredDevices.entrySet()) {
                    if (service.getServiceName().equals(entry.getValue().getServiceName())) {
                        lostDevice = entry.getValue();
                        discoveredDevices.remove(entry.getKey());
                        break;
                    }
                }

                if (lostDevice != null && callback != null) {
                    final DiscoveredDevice finalLost = lostDevice;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onDeviceLost(finalLost);
                        }
                    });
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped: " + serviceType);
                activeState.set(false);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed for " + serviceType + ", errorCode: " + errorCode);
                activeState.set(false);
                try {
                    nsdManager.stopServiceDiscovery(this);
                } catch (Exception ignored) {}
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed for " + serviceType + ", errorCode: " + errorCode);
                activeState.set(false);
            }
        };
    }

    private void processNextResolve() {
        if (!isResolving.compareAndSet(false, true)) {
            return;
        }

        NsdServiceInfo serviceInfo = resolveQueue.poll();
        if (serviceInfo == null) {
            isResolving.set(false);
            return;
        }

        // Set safety timeout for resolve call (5s)
        resolveTimeoutRunnable = () -> {
            Log.w(TAG, "Resolve timed out for: " + serviceInfo.getServiceName());
            isResolving.set(false);
            processNextResolve();
        };
        mainHandler.postDelayed(resolveTimeoutRunnable, 5000);

        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "Resolve failed for " + serviceInfo.getServiceName() + ", code: " + errorCode);
                    if (resolveTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(resolveTimeoutRunnable);
                        resolveTimeoutRunnable = null;
                    }
                    isResolving.set(false);
                    processNextResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolvedService) {
                    if (resolveTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(resolveTimeoutRunnable);
                        resolveTimeoutRunnable = null;
                    }

                    try {
                        InetAddress host = resolvedService.getHost();
                        int port = resolvedService.getPort();
                        if (host != null && port > 0) {
                            String hostAddress = host.getHostAddress();
                            // Filter out loopback or IPv6 bracket formatting if needed
                            if (hostAddress != null && hostAddress.startsWith("/")) {
                                hostAddress = hostAddress.substring(1);
                            }
                            if (hostAddress != null && hostAddress.contains("%")) {
                                hostAddress = hostAddress.substring(0, hostAddress.indexOf('%'));
                            }

                            String typeStr = resolvedService.getServiceType();
                            DeviceType type = DeviceType.LEGACY_CONNECT;
                            if (typeStr != null) {
                                if (typeStr.contains("_adb-tls-pairing")) {
                                    type = DeviceType.TLS_PAIRING;
                                } else if (typeStr.contains("_adb-tls-connect")) {
                                    type = DeviceType.TLS_CONNECT;
                                }
                            }

                            DiscoveredDevice device = new DiscoveredDevice(
                                    resolvedService.getServiceName(),
                                    hostAddress,
                                    port,
                                    type
                            );

                            discoveredDevices.put(device.getAddress(), device);
                            Log.i(TAG, "Discovered device ready: " + device.getAddress() + " (" + device.getType() + ")");

                            final DiscoveredDevice finalDevice = device;
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onDeviceDiscovered(finalDevice);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling resolved service", e);
                    } finally {
                        isResolving.set(false);
                        processNextResolve();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception calling resolveService", e);
            if (resolveTimeoutRunnable != null) {
                mainHandler.removeCallbacks(resolveTimeoutRunnable);
                resolveTimeoutRunnable = null;
            }
            isResolving.set(false);
            processNextResolve();
        }
    }

    private void acquireMulticastLock() {
        try {
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
                Log.d(TAG, "MulticastLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire MulticastLock: " + e.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                Log.d(TAG, "MulticastLock released");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release MulticastLock: " + e.getMessage());
        }
    }
}
