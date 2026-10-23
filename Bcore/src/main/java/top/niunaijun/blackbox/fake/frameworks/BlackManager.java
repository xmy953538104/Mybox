package top.niunaijun.blackbox.fake.frameworks;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.ParameterizedType;
import java.util.NoSuchElementException;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Created by BlackBox on 2022/3/23.
 */
public abstract class BlackManager<Service extends IInterface> {
    public static final String TAG = "BlackManager";

    private Service mService;
    private IBinder mBinder;
    private IBinder.DeathRecipient mDeathRecipient;

    protected abstract String getServiceName();

    protected IBinder lookupService() {
        return BlackBoxCore.get().getService(getServiceName());
    }

    public synchronized Service getService() {
        // A service can reject PING_TRANSACTION while its Binder remains alive.
        if (mService != null && mBinder.isBinderAlive()) {
            return mService;
        }
        clearService();
        try {
            final IBinder binder = lookupService();
            if (binder == null || !binder.isBinderAlive()) {
                throw new IllegalStateException("Virtual service unavailable: " + getServiceName());
            }
            Class<Service> serviceClass = getTClass();
            if (!serviceClass.getName().equals(binder.getInterfaceDescriptor())) {
                throw new IllegalStateException("Unexpected Binder interface for " + getServiceName());
            }
            Service service = Reflector.on(serviceClass.getName() + "$Stub").method("asInterface", IBinder.class).call(binder);
            if (service == null || service.asBinder() != binder) {
                throw new IllegalStateException("Invalid Binder interface for " + getServiceName());
            }
            IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    synchronized (BlackManager.this) {
                        if (mBinder == binder && mDeathRecipient == this) clearService();
                    }
                }
            };
            binder.linkToDeath(recipient, 0);
            mBinder = binder;
            mDeathRecipient = recipient;
            mService = service;
            if (!binder.isBinderAlive()) {
                clearService();
                throw new IllegalStateException("Virtual service died while connecting: " + getServiceName());
            }
            return service;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot connect to virtual service: " + getServiceName(), e);
        }
    }

    private void clearService() {
        IBinder binder = mBinder;
        IBinder.DeathRecipient recipient = mDeathRecipient;
        mService = null;
        mBinder = null;
        mDeathRecipient = null;
        if (binder != null && recipient != null) {
            try { binder.unlinkToDeath(recipient, 0); }
            catch (NoSuchElementException alreadyRemoved) { }
        }
    }

    private Class<Service> getTClass() {
        return (Class<Service>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }
}
