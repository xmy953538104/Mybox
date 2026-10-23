package top.niunaijun.blackbox.core.system.pm;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Milk on 4/27/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageUserState implements Parcelable {
    public boolean installed;
    public boolean stopped;
    public boolean hidden;
    public Map<String, Integer> componentStates = new HashMap<>();

    public BPackageUserState() {
        this.installed = false;
        this.stopped = true;
        this.hidden = false;
    }

    public static BPackageUserState create() {
        BPackageUserState state = new BPackageUserState();
        state.installed = true;
        return state;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(this.installed ? (byte) 1 : (byte) 0);
        dest.writeByte(this.stopped ? (byte) 1 : (byte) 0);
        dest.writeByte(this.hidden ? (byte) 1 : (byte) 0);
        dest.writeMap(componentStates);
    }

    protected BPackageUserState(Parcel in) {
        this.installed = in.readByte() != 0;
        this.stopped = in.readByte() != 0;
        this.hidden = in.readByte() != 0;
        componentStates = in.readHashMap(BPackageUserState.class.getClassLoader());
    }

    public BPackageUserState(BPackageUserState state) {
        this.installed = state.installed;
        this.stopped = state.stopped;
        this.hidden = state.hidden;
        this.componentStates = new HashMap<>(state.componentStates);
    }

    public int componentState(String name) {
        Integer state = componentStates.get(name);
        return state == null ? 0 : state;
    }

    public boolean componentEnabled(String name, boolean manifestEnabled) {
        int state = componentState(name);
        return state == 0 ? manifestEnabled : state == 1;
    }

    public static final Parcelable.Creator<BPackageUserState> CREATOR = new Parcelable.Creator<BPackageUserState>() {
        @Override
        public BPackageUserState createFromParcel(Parcel source) {
            return new BPackageUserState(source);
        }

        @Override
        public BPackageUserState[] newArray(int size) {
            return new BPackageUserState[size];
        }
    };
}
