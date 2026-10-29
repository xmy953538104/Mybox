package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import java.util.LinkedHashMap;
import java.util.Map;

/** Component changes belong to the authenticated process's own current APK generation. */
final class ServerComponentStates extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.ComponentStates.v1";
    private final Engine engine;
    ServerComponentStates(Engine engine) { this.engine = engine; attachInterface(null, DESCRIPTOR); }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        EngineProtocol.requireCaller(); data.enforceInterface(DESCRIPTOR); Bundle result;
        try {
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || input.getInt("user", -1) != 0) throw new SecurityException("Invalid component state user or protocol");
            ProcessRegistry.Identity owner = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            if (!owner.packageName.equals(input.getString("package"))) throw new SecurityException("Component mutation caller differs from its process");
            String operation = input.getString("operation");
            if ("category_hint".equals(operation)) {
                if (!(input.get("target") instanceof String) || !(input.get("category") instanceof Integer))
                    throw new IllegalArgumentException("Invalid category hint request");
                engine.packages().setCategoryHint(owner.packageName, owner.packageGeneration,
                        input.getString("target"), input.getInt("category"));
                EngineDiagnostics.event("packages", "category_hint_persisted", owner.generation, input.getInt("category"));
                reply.writeNoException(); reply.writeBundle(EngineProtocol.response()); return true;
            }
            if (operation != null) throw new UnsupportedOperationException("Unknown package state operation");
            String[] components = input.getStringArray("components"); int[] states = input.getIntArray("states"), options = input.getIntArray("flags");
            if (components == null || states == null || options == null || components.length == 0 || components.length > 256
                    || states.length != components.length || options.length != components.length) throw new IllegalArgumentException("Invalid component state batch");
            Map<String, Integer> changes = new LinkedHashMap<>();
            for (int index = 0; index < components.length; index++) {
                ComponentName component = ComponentName.unflattenFromString(components[index]);
                if (component == null || !owner.packageName.equals(component.getPackageName())) throw new SecurityException("Component changes cannot target another package");
                // shortcut: only DONT_KILL_APP (and synchronous durability); add restart semantics before flags zero.
                if ((options[index] & 1) == 0 || (options[index] & ~3) != 0) throw new UnsupportedOperationException("Component changes requiring process termination are not implemented");
                if (changes.put(component.getClassName(), states[index]) != null) throw new IllegalArgumentException("Duplicate component state target");
            }
            engine.packages().setComponentEnabledSettings(owner.packageName, owner.packageGeneration, changes);
            result = EngineProtocol.response();
        } catch (Exception failure) { result = EngineProtocol.failure(failure); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }
}
