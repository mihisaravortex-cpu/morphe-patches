package app.morphe.extension.music.patches.spoof;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

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

    private static final String GMS_BROKER_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsServiceBroker";
    private static final String GMS_CALLBACKS_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks";
    private static final String PO_TOKENS_SERVICE_DESCRIPTOR = "com.google.android.gms.potokens.internal.IPoTokensService";
    private static final String TOKEN_CALLBACKS_DESCRIPTOR = "com.google.android.gms.potokens.internal.ITokenCallbacks";

    private static final int TRANSACTION_GET_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 45;
    private static final int TRANSACTION_ON_POST_INIT_COMPLETE = 1;
    private static final int TRANSACTION_ON_POST_INIT_COMPLETE_WITH_INFO = 2;
    private static final int TRANSACTION_RESPONSE_STATUS_TOKEN = 2;
    private static final int TRANSACTION_RESPONSE_TOKEN = 1;

    private static final int POT_SERVICE_ID = 285;
    private static final long TOKEN_TTL_MS = 10 * 60 * 1000L;
    private static final long TIMEOUT_MS = 4500L;

    public static final class TokenData {
        public final byte[] rawBytes;
        public final long fetchedAtMs;

        public TokenData(byte[] rawBytes, long fetchedAtMs) {
            this.rawBytes = rawBytes;
            this.fetchedAtMs = fetchedAtMs;
        }

        public boolean isValid() {
            return rawBytes != null && rawBytes.length > 0;
        }
    }

    private static final class CachedToken {
        final TokenData data;
        final long expiresAt;

        CachedToken(TokenData data, long expiresAt) {
            this.data = data;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return data == null || !data.isValid() || System.currentTimeMillis() >= expiresAt;
        }
    }

    private static final Map<String, CachedToken> CACHE = new ConcurrentHashMap<>();

    private MusicPoTokenClient() {
    }

    public static TokenData fetchPoToken(Context context, String cacheKey, byte[] requestBytes) {
        if (context == null || requestBytes == null || requestBytes.length == 0) {
            return null;
        }

        String key = (cacheKey != null && !cacheKey.isEmpty())
                ? cacheKey
                : new String(requestBytes, StandardCharsets.UTF_8);

        CachedToken cached = CACHE.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }

        Context appContext = context.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> tokenBytesRef = new AtomicReference<>(null);

        Binder gmsCallbacksBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == TRANSACTION_ON_POST_INIT_COMPLETE || code == TRANSACTION_ON_POST_INIT_COMPLETE_WITH_INFO) {
                    try {
                        data.enforceInterface(GMS_CALLBACKS_DESCRIPTOR);
                        int statusCode = data.readInt();
                        IBinder serviceBinder = data.readStrongBinder();
                        if (statusCode == 0 && serviceBinder != null) {
                            queryPoTokensService(serviceBinder, requestBytes, tokenBytesRef, latch);
                        } else {
                            latch.countDown();
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed parsing GMS callback", ex);
                        latch.countDown();
                    }
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder brokerBinder) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    if (brokerBinder == null) {
                        latch.countDown();
                        return;
                    }

                    data.writeInterfaceToken(GMS_BROKER_DESCRIPTOR);
                    data.writeStrongBinder(gmsCallbacksBinder);
                    data.writeInt(POT_SERVICE_ID);
                    data.writeString(appContext.getPackageName());

                    brokerBinder.transact(TRANSACTION_GET_SERVICE, data, reply, 0);
                    reply.readException();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed transacting with IGmsServiceBroker", ex);
                    latch.countDown();
                } finally {
                    data.recycle();
                    reply.recycle();
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
            byte[] raw = tokenBytesRef.get();
            if (raw != null && raw.length > 0) {
                TokenData tokenData = new TokenData(raw, System.currentTimeMillis());
                CACHE.put(key, new CachedToken(tokenData, System.currentTimeMillis() + TOKEN_TTL_MS));
                return tokenData;
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

    public static TokenData fetchMusicPoToken(Context context, String videoId, String clientName) {
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }
        String tag = (clientName != null && !clientName.isEmpty()) ? clientName : "ANDROID_MUSIC";
        String key = tag + ":" + videoId;
        return fetchPoToken(context, key, key.getBytes(StandardCharsets.UTF_8));
    }

    private static void queryPoTokensService(
            IBinder serviceBinder,
            byte[] requestBytes,
            AtomicReference<byte[]> tokenBytesRef,
            CountDownLatch latch
    ) {
        Binder tokenCallbackBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == TRANSACTION_RESPONSE_TOKEN) {
                    try {
                        data.enforceInterface(TOKEN_CALLBACKS_DESCRIPTOR);
                        // Read status + optional PoToken parcelable byte payload
                        int hasStatus = data.readInt();
                        if (hasStatus != 0) {
                            data.readInt(); // statusCode
                            data.readString(); // statusMessage
                        }
                        int hasToken = data.readInt();
                        if (hasToken != 0) {
                            byte[] bytes = data.createByteArray();
                            if (bytes != null && bytes.length > 0) {
                                tokenBytesRef.set(bytes);
                            }
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed reading token callback parcel", ex);
                    } finally {
                        latch.countDown();
                    }
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PO_TOKENS_SERVICE_DESCRIPTOR);
            data.writeStrongBinder(tokenCallbackBinder);
            data.writeInt(0);
            data.writeByteArray(requestBytes);

            serviceBinder.transact(TRANSACTION_RESPONSE_STATUS_TOKEN, data, reply, 0);
            reply.readException();
        } catch (Exception ex) {
            Log.e(TAG, "Failed calling responseStatusToken", ex);
            latch.countDown();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
