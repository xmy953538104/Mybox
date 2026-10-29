package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android owns the durable records; signed launch data never trusts a guest-supplied owner. */
final class ServerShortcuts extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.Shortcuts.v1";
    static final String ACTION = "io.github.xmy953538104.mybox.engine.SHORTCUT";
    private static final String PAYLOAD = "mylite.shortcut.payload", MAC = "mylite.shortcut.mac";
    private final Engine engine;
    private final OsPendingStore capabilities;
    private final ShortcutManager manager;
    private final ComponentName launcher;

    ServerShortcuts(Engine engine, OsPendingStore capabilities) {
        this.engine = engine; this.capabilities = capabilities;
        Context context = engine.hostContext(); manager = context.getSystemService(ShortcutManager.class);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (manager == null || launch == null || launch.getComponent() == null) throw new EngineException("Physical shortcut publisher is unavailable");
        launcher = launch.getComponent(); attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        data.enforceInterface(DESCRIPTOR);
        try {
            CallerIdentity caller = EngineServerProvider.resolveCaller();
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || input.getInt("user", -1) != 0
                    || !caller.packageName.equals(input.getString("package"))) throw new SecurityException("Shortcut owner differs from the registered process");
            InstalledPackage installed = engine.packages().get(caller.packageName);
            if (installed == null) throw new SecurityException("Shortcut publisher is no longer installed");
            new OsPendingStore.Creator(installed).requireCaller(caller);
            long identity = Binder.clearCallingIdentity();
            Bundle result;
            try { synchronized (this) { result = route(input, installed); } }
            finally { Binder.restoreCallingIdentity(identity); }
            reply.writeNoException(); reply.writeBundle(result);
        } catch (Exception failure) {
            EngineDiagnostics.failure("shortcuts", "request", 0, failure);
            reply.writeException(failure instanceof RuntimeException ? failure : new IllegalStateException("Shortcut operation failed", failure));
        }
        return true;
    }

    private Bundle route(Bundle input, InstalledPackage owner) throws Exception {
        String operation = input.getString("operation"); Bundle out = EngineProtocol.response();
        if ("getDynamicShortcuts".equals(operation) || "getPinnedShortcuts".equals(operation) || "getShortcuts".equals(operation)) {
            List<ShortcutInfo> physical;
            if ("getDynamicShortcuts".equals(operation)) physical = manager.getDynamicShortcuts();
            else if ("getPinnedShortcuts".equals(operation)) physical = manager.getPinnedShortcuts();
            else if (Build.VERSION.SDK_INT >= 30) {
                int match = input.getInt("match");
                if ((match & ShortcutManager.FLAG_MATCH_MANIFEST) != 0) requireNoStaticShortcuts(owner);
                physical = manager.getShortcuts(match);
            }
            else throw new UnsupportedOperationException("Shortcut match queries require Android 11");
            ArrayList<ShortcutInfo> values = new ArrayList<>();
            for (ShortcutInfo item : physical) if (belongs(owner, item.getId())) values.add(guest(item, owner));
            out.putParcelable("shortcuts", slice(values));
        } else if ("getManifestShortcuts".equals(operation)) {
            requireNoStaticShortcuts(owner);
            out.putParcelable("shortcuts", slice(new ArrayList<ShortcutInfo>()));
        } else if ("setDynamicShortcuts".equals(operation) || "addDynamicShortcuts".equals(operation) || "updateShortcuts".equals(operation)) {
            Object batch = input.getParcelable("shortcuts");
            if (!Class.forName("android.content.pm.ParceledListSlice").isInstance(batch)) throw new IllegalArgumentException("Invalid shortcut batch");
            List<?> supplied = (List<?>) RuntimeReflection.invoke(batch, RuntimeReflection.method(batch.getClass(), "getList"));
            if (supplied == null || supplied.size() > 32) throw new IllegalArgumentException("Invalid shortcut batch");
            ArrayList<ShortcutInfo> mapped = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (Object value : supplied) {
                if (!(value instanceof ShortcutInfo)) throw new IllegalArgumentException("Invalid shortcut entry");
                ShortcutInfo item = (ShortcutInfo) value;
                if (!owner.packageName.equals(item.getPackage()) || !ids.add(id(item.getId())))
                    throw new SecurityException("Shortcut batch has an invalid owner or duplicate ID");
                mapped.add(physical(item, owner, "updateShortcuts".equals(operation)));
            }
            boolean accepted;
            if ("setDynamicShortcuts".equals(operation)) {
                // A virtual set must not delete another guest's or the host's dynamic records.
                for (ShortcutInfo item : manager.getDynamicShortcuts()) if (!belongs(owner, item.getId())) mapped.add(item);
                accepted = manager.setDynamicShortcuts(mapped);
            } else if ("addDynamicShortcuts".equals(operation)) accepted = manager.addDynamicShortcuts(mapped);
            else accepted = manager.updateShortcuts(mapped);
            out.putBoolean("value", accepted);
        } else if ("removeAllDynamicShortcuts".equals(operation)) {
            ArrayList<String> own = new ArrayList<>();
            for (ShortcutInfo item : manager.getDynamicShortcuts()) if (belongs(owner, item.getId())) own.add(item.getId());
            manager.removeDynamicShortcuts(own);
        } else {
            ArrayList<String> ids = input.getStringArrayList("ids");
            if (ids == null || ids.size() > 256) throw new IllegalArgumentException("Invalid shortcut ID batch");
            ArrayList<String> mapped = new ArrayList<>(); for (String value : ids) mapped.add(physicalId(owner, value));
            if ("enableShortcuts".equals(operation)) manager.enableShortcuts(mapped);
            else if ("disableShortcuts".equals(operation)) manager.disableShortcuts(mapped, input.getCharSequence("message"));
            else if ("removeDynamicShortcuts".equals(operation)) manager.removeDynamicShortcuts(mapped);
            else if ("removeLongLivedShortcuts".equals(operation) && Build.VERSION.SDK_INT >= 30) manager.removeLongLivedShortcuts(mapped);
            else if ("reportShortcutUsed".equals(operation) && mapped.size() == 1) manager.reportShortcutUsed(mapped.get(0));
            else throw new UnsupportedOperationException("Unsupported virtual shortcut operation: " + operation);
        }
        EngineDiagnostics.event("shortcuts", operation, owner.appId, 0); return out;
    }

    private void requireNoStaticShortcuts(InstalledPackage owner) throws Exception {
        PackageInfo info = engine.packages().getPackageInfo(owner.packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
        if (info == null) throw new SecurityException("Shortcut publisher metadata is unavailable");
        if (info.activities != null) for (ActivityInfo activity : info.activities)
            if (activity.metaData != null && activity.metaData.containsKey("android.app.shortcuts"))
                throw new UnsupportedOperationException("Guest static shortcut XML has not been reconstructed");
    }

    private ShortcutInfo physical(ShortcutInfo source, InstalledPackage owner, boolean update) throws Exception {
        Intent[] intents = source.getIntents();
        ComponentName activity = source.getActivity();
        if (update && intents == null) {
            List<ShortcutInfo> previous = new ArrayList<>(manager.getDynamicShortcuts()); previous.addAll(manager.getPinnedShortcuts());
            for (ShortcutInfo item : previous) if (physicalId(owner, source.getId()).equals(item.getId())) {
                ShortcutInfo saved = guest(item, owner); intents = saved.getIntents();
                if (activity == null) activity = saved.getActivity(); break;
            }
            if (intents == null) return new ShortcutInfo.Builder(engine.hostContext(), physicalId(owner, source.getId())).build();
        }
        // shortcut: explicit same-app roots only; implement authenticated task stacks before accepting multi-Intent shortcuts.
        if (intents == null || intents.length != 1) throw new UnsupportedOperationException("A single shortcut root Intent is required");
        requireActivity(intents[0], owner);
        if (activity != null && !owner.packageName.equals(activity.getPackageName())) throw new SecurityException("Foreign shortcut Activity");
        Intent capsule = new Intent(ACTION).putExtra("package", owner.packageName).putExtra("appId", owner.appId)
                .putExtra("certificates", String.join(",", owner.certificateSha256)).putExtra("id", id(source.getId()))
                .putExtra("activity", activity == null ? null : activity.flattenToString())
                .putExtra("intent", OsPendingStore.encodeIntent(intents[0]));
        byte[] packed = OsPendingStore.encodeIntent(capsule);
        String physicalId = physicalId(owner, source.getId());
        Intent envelope = new Intent(engine.hostContext(), OsPendingActivity.class).setAction(ACTION)
                .setData(Uri.parse("mylite-shortcut://record/" + physicalId))
                .putExtra(PAYLOAD, Base64.encodeToString(packed, Base64.NO_WRAP))
                .putExtra(MAC, Base64.encodeToString(capabilities.signShortcut(packed), Base64.NO_WRAP));
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(engine.hostContext(), physicalId).setActivity(launcher).setIntent(envelope);
        if ((Boolean) RuntimeReflection.invoke(source, RuntimeReflection.method(ShortcutInfo.class, "hasRank"))) builder.setRank(source.getRank());
        if (source.getShortLabel() != null) builder.setShortLabel(source.getShortLabel());
        if (source.getLongLabel() != null) builder.setLongLabel(source.getLongLabel());
        if (source.getDisabledMessage() != null) builder.setDisabledMessage(source.getDisabledMessage());
        android.graphics.drawable.Icon icon = (android.graphics.drawable.Icon) RuntimeReflection.get(source, "mIcon");
        if (icon != null) { requireBitmap(icon); builder.setIcon(icon); }
        if (source.getCategories() != null) builder.setCategories(source.getCategories());
        if (source.getExtras() != null) builder.setExtras(source.getExtras());
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.Person[] persons = (android.app.Person[]) RuntimeReflection.invoke(source, RuntimeReflection.method(ShortcutInfo.class, "getPersons"));
            if (persons != null && persons.length > 0) {
                for (android.app.Person person : persons) if (person != null && person.getIcon() != null) requireBitmap(person.getIcon());
                builder.setPersons(persons);
            }
            if (source.getLocusId() != null) builder.setLocusId(source.getLocusId());
            builder.setLongLived((Boolean) RuntimeReflection.invoke(source, RuntimeReflection.method(ShortcutInfo.class, "isLongLived")));
        }
        return builder.build();
    }

    private ShortcutInfo guest(ShortcutInfo physical, InstalledPackage owner) throws Exception {
        Intent capsule = capsule(physical.getIntent(), owner);
        if (!physicalId(owner, capsule.getStringExtra("id")).equals(physical.getId())) throw new SecurityException("Shortcut record ID changed");
        ShortcutInfo copy = copy(physical);
        RuntimeReflection.set(copy, "mId", capsule.getStringExtra("id"));
        RuntimeReflection.set(copy, "mPackageName", owner.packageName);
        String activity = capsule.getStringExtra("activity");
        RuntimeReflection.set(copy, "mActivity", activity == null ? null : ComponentName.unflattenFromString(activity));
        Intent target = OsPendingStore.decodeIntent(capsule.getByteArrayExtra("intent"));
        RuntimeReflection.invoke(copy, RuntimeReflection.method(ShortcutInfo.class, "setIntents", Intent[].class), (Object) new Intent[]{target});
        return copy;
    }

    private Intent capsule(Intent envelope, InstalledPackage expected) throws Exception {
        if (envelope == null || !ACTION.equals(envelope.getAction()) || envelope.getSelector() != null || envelope.getClipData() != null
                || !new ComponentName(engine.hostContext(), OsPendingActivity.class).equals(envelope.getComponent()))
            throw new SecurityException("Invalid shortcut launch envelope");
        String encoded = envelope.getStringExtra(PAYLOAD), signature = envelope.getStringExtra(MAC);
        if (encoded == null || encoded.length() > 44_000 || signature == null || signature.length() > 64)
            throw new SecurityException("Missing or oversized shortcut capability");
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP), mac = Base64.decode(signature, Base64.NO_WRAP);
        if (!MessageDigest.isEqual(mac, capabilities.signShortcut(bytes))) throw new SecurityException("Invalid shortcut signature");
        Intent value = OsPendingStore.decodeIntent(bytes);
        if (!ACTION.equals(value.getAction())) throw new SecurityException("Unknown shortcut capability schema");
        InstalledPackage owner = engine.packages().get(value.getStringExtra("package"));
        if (owner == null || expected != null && !owner.packageName.equals(expected.packageName)
                || value.getIntExtra("appId", -1) != owner.appId
                || !String.join(",", owner.certificateSha256).equals(value.getStringExtra("certificates")))
            throw new SecurityException("Shortcut publisher is no longer installed with the same identity");
        String physicalId = physicalId(owner, value.getStringExtra("id"));
        if (!Uri.parse("mylite-shortcut://record/" + physicalId).equals(envelope.getData())) throw new SecurityException("Shortcut capability URI changed");
        return value;
    }

    void deliver(Intent envelope, ServerActivities activities) throws Exception {
        Intent value;
        InstalledPackage owner;
        synchronized (this) {
            value = capsule(envelope, null); owner = engine.packages().get(value.getStringExtra("package"));
            String wanted = physicalId(owner, value.getStringExtra("id")); boolean current = false;
            List<ShortcutInfo> records = new ArrayList<>(manager.getDynamicShortcuts()); records.addAll(manager.getPinnedShortcuts());
            for (ShortcutInfo item : records) if (wanted.equals(item.getId()) && item.isEnabled()
                    && item.getIntent() != null && envelope.getStringExtra(MAC).equals(item.getIntent().getStringExtra(MAC))) current = true;
            if (!current) throw new SecurityException("Shortcut was removed, disabled or replaced");
        }
        Intent target = requireActivity(OsPendingStore.decodeIntent(value.getByteArrayExtra("intent")), owner)
                .removeFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        activities.pendingActivity(target, new OsPendingStore.Creator(owner));
        EngineDiagnostics.event("shortcuts", "launch_queued", owner.appId, 0);
    }

    private Intent requireActivity(Intent original, InstalledPackage owner) throws Exception {
        if (original == null || original.getAction() == null || original.getSelector() != null || original.getClipData() != null
                || (original.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0
                || original.getPackage() != null && !owner.packageName.equals(original.getPackage()))
            throw new SecurityException("Shortcut cannot carry foreign targets or URI grants");
        Intent result = new Intent(original);
        if (result.getComponent() == null) {
            result.setPackage(owner.packageName);
            List<ResolveInfo> matches = ManifestIntents.query(engine, result, result.getType(), PackageManager.GET_ACTIVITIES, 0);
            if (matches.size() != 1) throw new IllegalArgumentException("Shortcut target is not a unique enabled Activity");
            result.setComponent(new ComponentName(owner.packageName, matches.get(0).activityInfo.name));
        }
        if (!owner.packageName.equals(result.getComponent().getPackageName())) throw new SecurityException("Foreign shortcut component");
        PackageInfo info = engine.packages().getPackageInfo(owner.packageName, PackageManager.GET_ACTIVITIES);
        if (info.activities != null) for (ActivityInfo activity : info.activities)
            if (activity.name.equals(result.getComponent().getClassName()) && GuestPackageQueries.matchesComponent(engine, activity, 0)) return result;
        throw new IllegalArgumentException("Shortcut Activity is absent or disabled");
    }

    static ShortcutInfo copy(ShortcutInfo source) {
        Parcel parcel = Parcel.obtain();
        try { source.writeToParcel(parcel, 0); parcel.setDataPosition(0); return ShortcutInfo.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private static android.os.Parcelable slice(List<ShortcutInfo> values) throws Exception {
        return (android.os.Parcelable) Class.forName("android.content.pm.ParceledListSlice").getConstructor(List.class).newInstance(values);
    }
    private static void requireBitmap(android.graphics.drawable.Icon icon) throws Exception {
        int type = (Integer) RuntimeReflection.invoke(icon, RuntimeReflection.method(android.graphics.drawable.Icon.class, "getType"));
        if (type != 1 && type != 5) throw new SecurityException("Shortcut icons must be resolved in the guest process");
    }
    private static String id(String id) {
        if (id == null || id.isEmpty() || id.length() > 1024) throw new IllegalArgumentException("Invalid shortcut ID"); return id;
    }
    private static String prefix(InstalledPackage owner) { return "mylite.sc." + owner.appId + "."; }
    private static boolean belongs(InstalledPackage owner, String id) { return id != null && id.startsWith(prefix(owner)); }
    private static String physicalId(InstalledPackage owner, String original) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(id(original).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return prefix(owner) + hex;
    }
}
