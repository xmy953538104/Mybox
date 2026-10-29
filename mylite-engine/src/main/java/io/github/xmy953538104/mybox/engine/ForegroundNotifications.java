package io.github.xmy953538104.mybox.engine;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

/** Rebuilds supported platform templates using host resources, without forwarding guest URIs. */
final class ForegroundNotifications {
    static Notification copy(Context host, Context guest, Notification source) {
        if (source == null || source.getSmallIcon() == null) throw new IllegalArgumentException("A notification with a small icon is required");
        if (source.contentView != null || source.bigContentView != null || source.headsUpContentView != null
                || source.tickerView != null) {
            throw new UnsupportedOperationException("Notification custom views are not implemented");
        }
        if (Build.VERSION.SDK_INT >= 29 && source.getBubbleMetadata() != null) throw new UnsupportedOperationException("Notification bubbles are not implemented");
        if (source.getShortcutId() != null || (Build.VERSION.SDK_INT >= 29 && source.getLocusId() != null)) {
            throw new UnsupportedOperationException("Notification shortcut and locus capabilities are not implemented");
        }
        Bundle extras = source.extras == null ? Bundle.EMPTY : source.extras;
        String template = extras.getString(Notification.EXTRA_TEMPLATE);
        if (template != null && !template.isEmpty() && !template.equals(Notification.BigTextStyle.class.getName())) {
            throw new UnsupportedOperationException("This notification style is not implemented: " + template);
        }
        Notification.Builder builder = new Notification.Builder(host, source.getChannelId())
                .setSmallIcon(icon(guest, source.getSmallIcon()))
                .setContentTitle(extras.getCharSequence(Notification.EXTRA_TITLE))
                .setContentText(extras.getCharSequence(Notification.EXTRA_TEXT))
                .setSubText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                .setContentInfo(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
                .setTicker(source.tickerText).setWhen(source.when)
                .setShowWhen(extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true))
                .setUsesChronometer(extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                .setChronometerCountDown(extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
                .setCategory(source.category).setNumber(source.number).setColor(source.color)
                .setVisibility(source.visibility).setPriority(source.priority)
                .setOngoing((source.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .setAutoCancel((source.flags & Notification.FLAG_AUTO_CANCEL) != 0)
                .setOnlyAlertOnce((source.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0)
                .setLocalOnly((source.flags & Notification.FLAG_LOCAL_ONLY) != 0)
                .setGroup(source.getGroup()).setSortKey(source.getSortKey())
                .setGroupSummary((source.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                .setGroupAlertBehavior(source.getGroupAlertBehavior()).setTimeoutAfter(source.getTimeoutAfter())
                .setBadgeIconType(source.getBadgeIconType()).setSettingsText(source.getSettingsText())
                .setColorized(extras.getBoolean(Notification.EXTRA_COLORIZED))
                .setContentIntent(source.contentIntent).setDeleteIntent(source.deleteIntent)
                .setFullScreenIntent(source.fullScreenIntent, (source.flags & Notification.FLAG_HIGH_PRIORITY) != 0)
                .setProgress(extras.getInt(Notification.EXTRA_PROGRESS_MAX), extras.getInt(Notification.EXTRA_PROGRESS),
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        if (Build.VERSION.SDK_INT >= 29) builder.setAllowSystemGeneratedContextualActions(source.getAllowSystemGeneratedContextualActions());
        if (Build.VERSION.SDK_INT >= 31) {
            try { builder.setForegroundServiceBehavior(((Number) RuntimeReflection.get(source, "mFgsDeferBehavior")).intValue()); }
            catch (Exception failure) { throw new EngineException("Cannot preserve foreground notification display policy", failure); }
        }
        if (source.getLargeIcon() != null) builder.setLargeIcon(icon(guest, source.getLargeIcon()));
        if (source.actions != null) for (Notification.Action action : source.actions) {
            if (action == null) throw new IllegalArgumentException("Null notification action");
            if (action.getRemoteInputs() != null && action.getRemoteInputs().length != 0
                    || action.getDataOnlyRemoteInputs() != null && action.getDataOnlyRemoteInputs().length != 0) {
                throw new UnsupportedOperationException("Notification RemoteInput delivery has not been implemented");
            }
            Notification.Action.Builder copy = new Notification.Action.Builder(
                    action.getIcon() == null ? null : icon(guest, action.getIcon()), action.title, action.actionIntent)
                    .addExtras(new Bundle(action.getExtras())).setAllowGeneratedReplies(action.getAllowGeneratedReplies());
            if (Build.VERSION.SDK_INT >= 28) copy.setSemanticAction(action.getSemanticAction());
            if (Build.VERSION.SDK_INT >= 29) copy.setContextual(action.isContextual());
            if (Build.VERSION.SDK_INT >= 31) copy.setAuthenticationRequired(action.isAuthenticationRequired());
            builder.addAction(copy.build());
        }
        if (Notification.BigTextStyle.class.getName().equals(template)) {
            Notification.BigTextStyle style = new Notification.BigTextStyle(builder)
                    .bigText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (extras.containsKey(Notification.EXTRA_TITLE_BIG)) style.setBigContentTitle(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
            if (extras.containsKey(Notification.EXTRA_SUMMARY_TEXT)) style.setSummaryText(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        }
        if (source.publicVersion != null) {
            if (source.publicVersion.publicVersion != null) throw new IllegalArgumentException("Nested public notifications are not supported");
            builder.setPublicVersion(copy(host, guest, source.publicVersion));
        }
        return builder.build();
    }

    static Icon icon(Context guest, Icon icon) {
        int type;
        try { type = ((Number) RuntimeReflection.invoke(icon, RuntimeReflection.method(Icon.class, "getType"))).intValue(); }
        catch (Exception failure) { throw new EngineException("Cannot identify notification icon", failure); }
        if (type != 1 && type != 2 && type != 3 && type != 5) throw new UnsupportedOperationException("Notification URI icons are not implemented");
        Drawable drawable = icon.loadDrawable(guest);
        if (drawable == null) throw new IllegalArgumentException("Guest notification icon cannot be loaded");
        int width = Math.max(1, Math.min(256, drawable.getIntrinsicWidth()));
        int height = Math.max(1, Math.min(256, drawable.getIntrinsicHeight()));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height); drawable.draw(new Canvas(bitmap));
        return Icon.createWithBitmap(bitmap);
    }
    private ForegroundNotifications() { }
}
