package com.byd.car.property;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of {@code com.byd.car.property.CarPropertyConfig} from DiCarServer.apk.
 *
 * <p>Wire format (matches the framework's writer byte-for-byte):
 * <pre>int access | long featureId | String typeName | String providerName |
 * String readPermission | String writePermission</pre>
 *
 * <p>The provider name is a class name (e.g. {@code AbsBYDAutoDevice}
 * subclass). In our process those classes don't exist, so we tolerate
 * {@code ClassNotFoundException} and leave {@link #mProvider}/{@link #mType}
 * null — we only care about the permission strings here.
 */
public class CarPropertyConfig<T> implements Parcelable {
    public static final int ACCESS_READ = 1;
    public static final int ACCESS_WRITE = 2;
    public static final int ACCESS_READ_WRITE = 3;

    public int mAccess;
    public long mFeatureId;
    public String mTypeName;
    public String mProviderName;
    public String mReadPermission;
    public String mWritePermission;

    // Kept for source-compat with the framework class — never set on our side.
    public Class<?> mType;
    public T mProvider;

    public CarPropertyConfig() {}

    public int getAccess() { return mAccess; }
    public long getFeatureId() { return mFeatureId; }
    public String getReadPermission() { return mReadPermission; }
    public String getWritePermission() { return mWritePermission; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAccess);
        dest.writeLong(mFeatureId);
        dest.writeString(mTypeName);
        dest.writeString(mProviderName);
        dest.writeString(mReadPermission);
        dest.writeString(mWritePermission);
    }

    public CarPropertyConfig(Parcel parcel) {
        mAccess = parcel.readInt();
        mFeatureId = parcel.readLong();
        mTypeName = parcel.readString();
        mProviderName = parcel.readString();
        mReadPermission = parcel.readString();
        mWritePermission = parcel.readString();
    }

    public static final Parcelable.Creator<CarPropertyConfig> CREATOR =
            new Parcelable.Creator<CarPropertyConfig>() {
                @Override public CarPropertyConfig createFromParcel(Parcel in) { return new CarPropertyConfig(in); }
                @Override public CarPropertyConfig[] newArray(int size) { return new CarPropertyConfig[size]; }
            };

    @Override
    public String toString() {
        String accessStr = mAccess == ACCESS_READ ? "R"
                : mAccess == ACCESS_WRITE ? "W"
                : mAccess == ACCESS_READ_WRITE ? "RW"
                : "?(" + mAccess + ")";
        return "CarPropertyConfig{access=" + accessStr
                + ", fid=" + mFeatureId
                + ", type=" + mTypeName
                + ", provider=" + mProviderName
                + ", readPerm=" + mReadPermission
                + ", writePerm=" + mWritePermission + "}";
    }
}
