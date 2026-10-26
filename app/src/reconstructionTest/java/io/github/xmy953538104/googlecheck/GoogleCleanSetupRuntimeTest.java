package io.github.xmy953538104.googlecheck;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.xmy953538104.mybox.MainActivity;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.WebInputRuntimeTest;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.FileOutputStream;

/** Clean AOSP, public setup UI and real DocumentsUI; no direct install or picker-result injection. */
public final class GoogleCleanSetupRuntimeTest extends InstrumentationTestCase {
    public void testCleanDeviceImportsStoreThroughTheRealPickerAndOpensIt() throws Throwable {
        Context host=getInstrumentation().getTargetContext(); UiAutomation ui=getInstrumentation().getUiAutomation();
        for(String name:new String[]{"com.google.android.gms","com.android.vending","com.gbox.android"}) {
            absent(host,name); assertNull("Setup must start without a guest Google package",Engine.get().packages().get(name));
        }
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();int flags=info.flags;
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        try {
            openHome(host); touch(ui,"准备 Google 服务与商店",false);
            awaitText(ui,"操作未完成：microG 已安装",120000);
            InstalledPackage microg=Engine.get().packages().get("com.google.android.gms");assertNotNull(microg);
            assertNull(Engine.get().packages().get("com.android.vending")); retain(ui,host,"source-required");
            touch(ui,"从已有 GBox 安装包导入 Play",false); chooseFile(ui,"Mybox-invalid-source.apk");
            awaitText(ui,"操作未完成：",30000); assertNull(Engine.get().packages().get("com.android.vending"));
            assertEquals(microg.baseApk,Engine.get().packages().get("com.google.android.gms").baseApk);
            retain(ui,host,"rejected");
            touch(ui,"从已有 GBox 安装包导入 Play",false); chooseFile(ui,"Mybox-Play-source.apk");
            awaitText(ui,"microG 与 Play 商店已安装。",120000);
            InstalledPackage store=Engine.get().packages().get("com.android.vending");assertNotNull(store);
            assertEquals("The selected sample contains the known Google Play 40 generation",84001300L,store.versionCode);
            assertEquals("Reference engine or companion packages were imported",2,googlePackages());
            absent(host,"com.gbox.android"); retain(ui,host,"installed");
            openHome(host);touch(ui,"准备 Google 服务与商店",false);
            awaitText(ui,"microG 与 Play 商店已安装。",30000);
            assertEquals("Retry replaced microG",microg.baseApk,Engine.get().packages().get("com.google.android.gms").baseApk);
            assertEquals("Retry replaced Play",store.baseApk,Engine.get().packages().get("com.android.vending").baseApk);
            touch(ui,"Google Play 商店",false);
            new GoogleRuntimeTest.ActivityInspector().awaitResumed(null,store);
            awaitText(ui,"Sign in",45000);retain(ui,host,"store-opened");
            for(String name:new String[]{"com.google.android.gms","com.android.vending","com.gbox.android"}) absent(host,name);
            System.out.println("MYLITE_CLEAN_SETUP noSystemGoogle=true realPicker=true badSourceRejected=true retryPreservedGenerations=true actualHomeStoreTouch=true authenticationVerified=false downloadVerified=false");
        } catch(Throwable failure) {
            try {retain(ui,host,"failure");} catch(Throwable diagnostic) {failure.addSuppressed(diagnostic);}
            throw failure;
        } finally { info.flags=flags;ui.setServiceInfo(info); }
    }
    private static int googlePackages() throws Exception {
        int count=0;
        for(InstalledPackage value:Engine.get().packages().list()) {
            if(value.packageName.equals("com.google.android.gms") || value.packageName.equals("com.android.vending")) count++;
            assertFalse("Original reference companion was imported",value.packageName.startsWith("com.vlite.")
                    || value.packageName.equals("com.gbox.android") || value.packageName.equals("com.android.vending.mgc")
                    || value.packageName.startsWith("com.huawei."));
        }
        return count;
    }
    private static void absent(Context host,String name) throws Exception {
        try { host.getPackageManager().getPackageInfo(name,0);fail("A physical package invalidates clean-device acceptance: "+name); }
        catch(PackageManager.NameNotFoundException expected) { }
    }
    private static void openHome(Context host) {
        host.startActivity(new Intent(host,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
    private static void chooseFile(UiAutomation ui,String file) throws Exception {
        if(bounds(ui,file,false)==null) {
            touch(ui,"Show roots",true);touch(ui,"Downloads",false);
        }
        touch(ui,file,false);
    }
    private static void awaitText(UiAutomation ui,String text,long timeout) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;
        do {if(bounds(ui,text,false)!=null)return;Thread.sleep(100);} while(SystemClock.elapsedRealtime()<until);
        fail("Real UI text absent: "+text);
    }
    private static void touch(UiAutomation ui,String text,boolean description) throws Exception {
        long until=SystemClock.elapsedRealtime()+12000;Rect target=null;
        do {
            target=bounds(ui,text,description);if(target!=null)break;
            Thread.sleep(100);
        } while(SystemClock.elapsedRealtime()<until);
        if(target==null && !description) for(int i=0;i<4 && target==null;i++) {
            swipeUp(ui);Thread.sleep(200);target=bounds(ui,text,false);
        }
        assertNotNull("Actual UI touch target absent: "+text,target);
        WebInputRuntimeTest.syncInput(ui);
        target=bounds(ui,text,description);assertNotNull("UI target moved before touch: "+text,target);
        WebInputRuntimeTest.tap(ui,target.centerX(),target.centerY(),50);
    }
    private static Rect bounds(UiAutomation ui,String text,boolean description) {
        for(AccessibilityWindowInfo window:ui.getWindows()) {
            AccessibilityNodeInfo root=window.getRoot();window.recycle();if(root==null)continue;
            try {Rect found=find(root,text,description);if(found!=null)return found;} finally {root.recycle();}
        }
        return null;
    }
    private static Rect find(AccessibilityNodeInfo node,String text,boolean description) {
        CharSequence actual=description ? node.getContentDescription() : node.getText();
        if(node.isVisibleToUser() && node.isEnabled() && actual!=null
                && (text.contentEquals(actual) || actual.toString().startsWith(text))) {
            Rect result=new Rect();node.getBoundsInScreen(result);if(!result.isEmpty())return result;
        }
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try {Rect found=find(child,text,description);if(found!=null)return found;} finally {child.recycle();}
        }
        return null;
    }
    private static void swipeUp(UiAutomation ui) throws Exception {
        android.graphics.Bitmap image=ui.takeScreenshot();assertNotNull(image);
        int x=image.getWidth()/2,top=image.getHeight()/3,bottom=image.getHeight()*4/5;image.recycle();
        long start=SystemClock.uptimeMillis();
        for(int i=0;i<=10;i++) {
            MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:i==10?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,x,bottom-(bottom-top)*i/10f,0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {assertTrue(ui.injectInputEvent(event,true));}finally {event.recycle();}Thread.sleep(20);
        }
    }
    private static void retain(UiAutomation ui,Context host,String phase) throws Exception {
        android.graphics.Bitmap image=ui.takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=host.openFileOutput("google-setup-"+phase+".png",Context.MODE_PRIVATE)) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));
        }finally {image.recycle();}
    }
}
