package app.morphe.extension.music.patches.spoof;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;
import com.google.android.gms.common.internal.IGmsServiceBroker;
import com.google.android.gms.potokens.PoToken;
import com.google.android.gms.potokens.internal.IPoTokensService;
import com.google.android.gms.potokens.internal.ITokenCallbacks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicPoTokenClient {
    private static final String TAG = "morphe: MusicPoTokenClient";
    private static final String POT_HELPER_PACKAGE = "app.morphe.pot.helper";
    private static final String POT_SERVICE_ACTION = "com.google.android.gms.potokens.service.START";
    private static final int POT_SERVICE_ID = 285;
    private static final long TOKEN_TTL_MS = 10 * 60 * 1000L;
    private static final long TIMEOUT_MS = 4500L;

    private static final class CachedToken {
        final PoToken token;
        final long expiresAt;

        CachedToken(PoToken token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return token != null && System.currentTimeMillis() < expiresAt;
        }
    }

    private static final Map<String, CachedToken> CACHE = new ConcurrentHashMap<>();

    private MusicPoTokenClient() {
    }

    public static PoToken fetchPoToken(Context context, String cacheKey, byte[] requestBytes) {
        if (context == null || requestBytes == null || requestBytes.length == 0) {
            return null;
        }

        String key = (cacheKey != null && !cacheKey.isEmpty())
                ? cacheKey
                : new String(requestBytes, StandardCharsets.UTF_8);

        CachedToken cached = CACHE.get(key);
        if (cached != null && cached.isValid()) {
            return cached.token;
        }

        Context appContext = context.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PoToken> tokenRef = new AtomicReference<>(null);

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IGmsServiceBroker broker = IGmsServiceBroker.Stub.asInterface(binder);
                    if (broker == null) {
                        latch.countDown();
                        return;
                    }

                    GetServiceRequest request = new GetServiceRequest(POT_SERVICE_ID);
                    request.packageName = appContext.getPackageName();

                    IGmsCallbacks gmsCallbacks = new IGmsCallbacks.Stub() {
                        @Override
                        public void onPostInitComplete(int statusCode, IBinder serviceBinder, Bundle connectionInfo) {
                            requestTokenFromService(serviceBinder, requestBytes, tokenRef, latch);
                        }

                        @Override
                        public void onAccountValidationComplete(int statusCode, Bundle validationData) {
                            latch.countDown();
                        }

                        @Override
                        public void onPostInitCompleteWithConnectionInfo(
                                int statusCode,
                                IBinder serviceBinder,
                                ConnectionInfo info
                        ) {
                            requestTokenFromService(serviceBinder, requestBytes, tokenRef, latch);
                        }
                    };

                    broker.getService(gmsCallbacks, request);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to query IGmsServiceBroker", ex);
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latch.countDown();
            }
        };

        try {
            Intent intent = new Intent(POT_SERVICE_ACTION);
            intent.setPackage(POT_HELPER_PACKAGE);

            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "bindService returned false for " + POT_HELPER_PACKAGE);
                return null;
            }

            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            PoToken fetched = tokenRef.get();
            if (fetched != null) {
                CACHE.put(key, new CachedToken(fetched, System.currentTimeMillis() + TOKEN_TTL_MS));
                return fetched;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while fetching PoToken", ie);
        } catch (Exception ex) {
            Log.e(TAG, "Error fetching PoToken", ex);
        } finally {
            try {
                appContext.unbindService(connection);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    public static PoToken fetchMusicPoToken(Context context, String videoId, String clientName) {
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }
        String tag = (clientName != null && !clientName.isEmpty()) ? clientName : "ANDROID_MUSIC";
        String key = tag + ":" + videoId;
        return fetchPoToken(context, key, key.getBytes(StandardCharsets.UTF_8));
    }

    private static void requestTokenFromService(
            IBinder serviceBinder,
            byte[] requestBytes,
            AtomicReference<PoToken> tokenRef,
            CountDownLatch latch
    ) {
        try {
            IPoTokensService service = IPoTokensService.Stub.asInterface(serviceBinder);
            if (service == null) {
                latch.countDown();
                return;
            }

            service.responseStatusToken(new ITokenCallbacks.Stub() {
                @Override
                public void responseToken(Status status, PoToken token) {
                    if (status != null && status.isSuccess() && token != null) {
                        tokenRef.set(token);
                    } else {
                        Log.w(TAG, "Unsuccessful responseToken status=" + status);
                    }
                    latch.countDown();
                }

                @Override
                public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        return super.onTransact(code, data, reply, flags);
                    } catch (Exception ex) {
                        latch.countDown();
                        return false;
                    }
                }
            }, 0, requestBytes);
        } catch (Exception ex) {
            Log.e(TAG, "responseStatusToken call failed", ex);
            latch.countDown();
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
