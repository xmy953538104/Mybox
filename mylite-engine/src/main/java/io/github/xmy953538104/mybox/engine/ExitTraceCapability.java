package io.github.xmy953538104.mybox.engine;

import android.app.ApplicationExitInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import java.lang.reflect.Method;

/** An exact-record capability; never returns the OS's shared argument-bearing trace Binder. */
@android.annotation.TargetApi(30)
final class ExitTraceCapability extends Binder {
    private final ServerExitHistory history;
    private final ProcessRegistry.Identity issuer;
    private final String epoch, key, descriptor;
    private final ApplicationExitInfo physical;
    private final Object retriever;
    private final Method retrieve;
    private final int transaction;
    private final boolean nativeTrace;

    private ExitTraceCapability(ServerExitHistory history, Object retriever, ApplicationExitInfo physical,
                                ProcessRegistry.Identity issuer, String epoch, String key, boolean nativeTrace) throws Exception {
        this.history = history; this.retriever = retriever; this.physical = physical;
        this.issuer = issuer; this.epoch = epoch; this.key = key; this.nativeTrace = nativeTrace;
        descriptor = ((IInterface) retriever).asBinder().getInterfaceDescriptor();
        String expected = nativeTrace ? "android.app.IParcelFileDescriptorRetriever" : "android.app.IAppTraceRetriever";
        if (!expected.equals(descriptor)) throw new UnsupportedOperationException("Unknown OS exit trace contract");
        Class<?> contract = Class.forName(expected), stub = Class.forName(expected + "$Stub");
        String name = nativeTrace ? "getPfd" : "getTraceFileDescriptor";
        retrieve = nativeTrace ? contract.getMethod(name) : contract.getMethod(name, String.class, int.class, int.class);
        transaction = RuntimeReflection.field(stub, "TRANSACTION_" + name).getInt(null);
        attachInterface(null, descriptor);
    }

    static void install(ServerExitHistory history, ApplicationExitInfo physical, ApplicationExitInfo copy,
                        ProcessRegistry.Identity caller, String epoch, String key) throws Exception {
        installField(history, physical, copy, caller, epoch, key, "mAppTraceRetriever", false);
        if (Build.VERSION.SDK_INT >= 31) installField(history, physical, copy, caller, epoch, key, "mNativeTombstoneRetriever", true);
    }

    private static void installField(ServerExitHistory history, ApplicationExitInfo physical, ApplicationExitInfo copy,
                                     ProcessRegistry.Identity caller, String epoch, String key, String field, boolean nativeTrace) throws Exception {
        Object raw = RuntimeReflection.get(physical, field);
        if (raw == null) return;
        ExitTraceCapability capability = new ExitTraceCapability(history, raw, physical, caller, epoch, key, nativeTrace);
        Object scoped = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(capability.descriptor + "$Stub"),
                "asInterface", IBinder.class), capability);
        RuntimeReflection.set(copy, field, scoped);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(descriptor); return true; }
        if (code != transaction) return false;
        EngineProtocol.requireCaller(); data.enforceInterface(descriptor);
        ParcelFileDescriptor file = null;
        try {
            if (!nativeTrace) {
                String pkg = data.readString(); int uid = data.readInt(), pid = data.readInt();
                if (!issuer.packageName.equals(pkg) || uid != physical.getPackageUid() || pid != physical.getPid()) {
                    throw new SecurityException("Exit trace arguments differ from the selected record");
                }
            }
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Trailing exit trace arguments");
            history.authorizeTrace(issuer, epoch, key, physical.getPid());
            long token = Binder.clearCallingIdentity();
            try {
                file = (ParcelFileDescriptor) (nativeTrace ? RuntimeReflection.invoke(retriever, retrieve)
                        : RuntimeReflection.invoke(retriever, retrieve, RuntimeReflection.get(physical, "mPackageName"),
                                physical.getPackageUid(), physical.getPid()));
            } catch (android.os.RemoteException unavailable) {
                // ApplicationExitInfo.getTraceInputStream itself returns null for this exact OS failure.
                file = null;
            } finally { Binder.restoreCallingIdentity(token); }
            // The OS retriever accepts PID, not generation: reject a replacement while opening its fd.
            history.authorizeTrace(issuer, epoch, key, physical.getPid());
            reply.writeNoException(); reply.writeTypedObject(file, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } catch (RuntimeException failure) { reply.writeException(failure); }
        catch (Exception failure) { reply.writeException(new IllegalStateException("Actual exit trace is unavailable", failure)); }
        finally {
            if (file != null) try { file.close(); }
            catch (java.io.IOException failure) { android.util.Log.w("MyliteExitHistory", "Exit trace fd close failed", failure); }
        }
        return true;
    }
}
