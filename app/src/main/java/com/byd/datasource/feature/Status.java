package com.byd.datasource.feature;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of {@code com.byd.datasource.feature.Status} from DiCarServer.apk.
 * Same package name because Parcelable is read by classloader-name dispatch
 * inside the ContentProvider boundary.
 */
public class Status implements Parcelable {
    public static final int STATUS_BLOCKING = -2147482647;
    public static final int STATUS_FAILED = -2147482648;
    public static final int STATUS_INVALID_ARG = -2147482645;
    public static final int STATUS_NONE = -1;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_TIMEOUT = -2147482646;
    public static final int STATUS_UNAVAILABLE = -10011;
    public static final int STATUS_UNKNOWN_ERROR = Integer.MIN_VALUE;

    public final int code;
    public final String description;

    public Status(int statusCode) {
        this.code = statusCode;
        this.description = "";
    }

    public Status(int statusCode, String description) {
        this.code = statusCode;
        this.description = description == null ? "" : description;
    }

    public Status(Parcel in) {
        this.code = in.readInt();
        this.description = in.readString();
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.code);
        dest.writeString(this.description);
    }

    public static final Parcelable.Creator<Status> CREATOR = new Parcelable.Creator<Status>() {
        @Override public Status createFromParcel(Parcel in) { return new Status(in); }
        @Override public Status[] newArray(int size) { return new Status[size]; }
    };
}
