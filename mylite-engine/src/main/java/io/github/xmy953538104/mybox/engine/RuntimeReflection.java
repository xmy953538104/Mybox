package io.github.xmy953538104.mybox.engine;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class RuntimeReflection {
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object get(Object object, String name) throws Exception {
        return field(object.getClass(), name).get(object);
    }

    static void set(Object object, String name, Object value) throws Exception {
        field(object.getClass(), name).set(object, value);
    }

    static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    static Object invoke(Object object, Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(object, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    static Object activityThread() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = invoke(null, method(type, "currentActivityThread"));
        if (thread == null) throw new EngineException("Android application thread is unavailable");
        return thread;
    }

    private RuntimeReflection() { }
}
