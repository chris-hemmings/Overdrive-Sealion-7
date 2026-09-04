package com.byd.datasource.feature;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of {@code com.byd.datasource.feature.Response} from DiCarServer.apk.
 * The {@code result} field is itself a Parcelable (typically a CarPropertyValue).
 */
public class Response<T> implements Parcelable {
    public final Status status;
    public final T result;

    public Response(int statusCode) { this(new Status(statusCode), null); }
    public Response(Status status) { this(status, null); }
    public Response(Status status, T result) {
        this.status = status;
        this.result = result;
    }

    @SuppressWarnings("unchecked")
    public Response(Parcel parcel) {
        this.status = parcel.readParcelable(Status.class.getClassLoader());
        this.result = (T) parcel.readParcelable(getClass().getClassLoader());
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.status, flags);
        dest.writeParcelable((Parcelable) this.result, flags);
    }

    public static final Parcelable.Creator<Response> CREATOR = new Parcelable.Creator<Response>() {
        @Override public Response createFromParcel(Parcel in) { return new Response(in); }
        @Override public Response[] newArray(int size) { return new Response[size]; }
    };
}
