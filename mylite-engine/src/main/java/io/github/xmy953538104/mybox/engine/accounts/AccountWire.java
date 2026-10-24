package io.github.xmy953538104.mybox.engine.accounts;

import android.accounts.Account;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AccountWire {
    static final int VERSION = 1;
    static final String AUTHENTICATOR = "android.accounts.IAccountAuthenticator";
    static final String AUTHENTICATOR_RESPONSE = "android.accounts.IAccountAuthenticatorResponse";
    static final String MANAGER_RESPONSE = "android.accounts.IAccountManagerResponse";

    static AccountKey key(Bundle request) {
        Account account = request.getParcelable("account");
        if (account == null) throw new IllegalArgumentException("An account is required");
        return new AccountKey(account.name, account.type);
    }

    static String required(Bundle request, String key) {
        String value = request.getString(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Required account request field is absent");
        return value;
    }

    static Account[] accounts(List<AccountKey> keys) {
        Account[] result = new Account[keys.size()];
        for (int i = 0; i < result.length; i++) result[i] = new Account(keys.get(i).name, keys.get(i).type);
        return result;
    }

    static Map<String, String> strings(Bundle values) {
        Map<String, String> result = new HashMap<>();
        if (values != null) for (String key : values.keySet()) {
            Object value = values.get(key);
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Account userdata must contain strings");
            result.put(key, (String) value);
        }
        return result;
    }

    static Map<String, Integer> visibility(Bundle values) {
        Map<String, Integer> result = new HashMap<>();
        if (values != null) for (String key : values.keySet()) {
            Object value = values.get(key);
            if (!(value instanceof Integer)) throw new IllegalArgumentException("Account visibility must contain integers");
            result.put(key, (Integer) value);
        }
        return result;
    }

    static void nullable(Parcel parcel, Parcelable value) {
        parcel.writeInt(value == null ? 0 : 1);
        if (value != null) value.writeToParcel(parcel, 0);
    }

    static void callback(IBinder target, String descriptor, Bundle result, int error, String message) throws RemoteException {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        long identity = android.os.Binder.clearCallingIdentity();
        try {
            input.writeInterfaceToken(descriptor);
            if (result != null) nullable(input, result);
            else { input.writeInt(error); input.writeString(message); }
            int code = result != null ? 1 : MANAGER_RESPONSE.equals(descriptor) ? 2 : 3;
            if (!target.transact(code, input, output, 0)) throw new RemoteException("Account response transaction was rejected");
            if (output.dataSize() > 0) output.readException();
        } finally {
            android.os.Binder.restoreCallingIdentity(identity);
            output.recycle(); input.recycle();
        }
    }

    private AccountWire() {}
}
