package io.github.xmy953538104.mybox.engine;

import android.app.LocaleConfig;
import android.os.LocaleList;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.lang.reflect.Method;

public final class LocaleBoundaryTest extends InstrumentationTestCase {
    public static final class Backend {
        int calls;
        public void setApplicationLocales(String pkg, int user, LocaleList locales, boolean delegate) { calls++; }
        public LocaleList getApplicationLocales(String pkg, int user) { calls++; return null; }
        public void setOverrideLocaleConfig(String pkg, int user, LocaleConfig config) { calls++; }
        public LocaleConfig getOverrideLocaleConfig(String pkg, int user) { calls++; return null; }
        public LocaleList getSystemLocales() { calls++; return LocaleList.forLanguageTags("en-US"); }
    }
    public void testPreferencesSurviveReloadWithoutMutatingHostOrOtherGuest() throws Throwable {
        if (android.os.Build.VERSION.SDK_INT != 34) return;
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(), "locale-boundary-" + java.util.UUID.randomUUID() + ".properties");
        Backend backend = new Backend(); LocaleList[] applied = {null};
        GuestLocaleCalls calls = new GuestLocaleCalls(backend, "guest", file, locales -> applied[0] = locales);
        Method set = Backend.class.getMethod("setApplicationLocales", String.class, int.class, LocaleList.class, boolean.class);
        Method get = Backend.class.getMethod("getApplicationLocales", String.class, int.class);
        assertEquals(LocaleList.getEmptyLocaleList(), calls.invoke(null, get, new Object[]{"guest", 0}));
        LocaleList requested = LocaleList.forLanguageTags("fr-FR,ar-EG");
        Object[] args = {"guest", 0, requested, false}; calls.invoke(null, set, args);
        assertEquals(requested, applied[0]); assertSame(requested, args[2]); assertEquals(0, backend.calls);
        GuestLocaleCalls reloaded = new GuestLocaleCalls(backend, "guest", file, locales -> applied[0] = locales);
        assertEquals(requested, reloaded.invoke(null, get, new Object[]{"guest", 0}));
        for (Object[] invalid : new Object[][]{{"host", 0, requested, false}, {"other.guest", 0, requested, false}, {"guest", 1, requested, false}}) {
            try { calls.invoke(null, set, invalid); fail("Foreign locale mutation was accepted"); } catch (SecurityException expected) { }
        }
        assertEquals(requested, reloaded.readLocales());
        calls.invoke(null, set, new Object[]{"guest", 0, LocaleList.getEmptyLocaleList(), false});
        assertTrue(reloaded.readLocales().isEmpty()); assertTrue(applied[0].isEmpty());
        Method setOverride = Backend.class.getMethod("setOverrideLocaleConfig", String.class, int.class, LocaleConfig.class);
        Method getOverride = Backend.class.getMethod("getOverrideLocaleConfig", String.class, int.class);
        assertNull(calls.invoke(null, getOverride, new Object[]{"guest", 0}));
        calls.invoke(null, setOverride, new Object[]{"guest", 0, new LocaleConfig(requested)});
        assertEquals(requested, ((LocaleConfig) reloaded.invoke(null, getOverride, new Object[]{"guest", 0})).getSupportedLocales());
        assertTrue(reloaded.readLocales().isEmpty());
        calls.invoke(null, setOverride, new Object[]{"guest", 0, null}); assertNull(reloaded.invoke(null, getOverride, new Object[]{"guest", 0}));
        assertEquals(0, backend.calls);
        assertEquals(LocaleList.forLanguageTags("en-US"), calls.invoke(null, Backend.class.getMethod("getSystemLocales"), null));
        assertEquals(1, backend.calls);
        java.nio.file.Files.deleteIfExists(file.toPath()); java.nio.file.Files.deleteIfExists(new File(file.getPath() + ".lock").toPath());
    }
}
