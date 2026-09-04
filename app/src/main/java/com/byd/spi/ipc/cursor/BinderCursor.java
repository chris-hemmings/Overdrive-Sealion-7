package com.byd.spi.ipc.cursor;

import android.database.MatrixCursor;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of {@code com.byd.spi.ipc.cursor.BinderCursor} from DiCarServer.apk.
 *
 * <p>This is package-name-sensitive — {@code Bundle.getParcelable()} reads the
 * fully-qualified class name off the wire and resolves it via the bundle's
 * classloader. If our daemon process doesn't have a class at this exact path,
 * unmarshalling throws {@code BadParcelableException("ClassNotFoundException
 * when unmarshalling: com.byd.spi.ipc.cursor.BinderCursor$BinderParcelable")}.
 *
 * <p>We only need {@code BinderParcelable} (the inner class) on our side —
 * {@code BinderCursor} itself isn't deserialized. Outer kept as a shell so
 * the inner-class FQN matches.
 *
 * <p>Wire format mirrors the original byte-for-byte: a single
 * {@code writeStrongBinder} / {@code readStrongBinder}.
 */
public class BinderCursor extends MatrixCursor {

    public static final String KEY_BINDER = "binder";

    public BinderCursor(String[] columnNames) { super(columnNames); }

    public static class BinderParcelable implements Parcelable {
        private IBinder mBinder;

        public BinderParcelable(IBinder binder) { this.mBinder = binder; }
        public BinderParcelable(Parcel source) { this.mBinder = source.readStrongBinder(); }

        public IBinder getBinder() { return mBinder; }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongBinder(mBinder);
        }

        public static final Parcelable.Creator<BinderParcelable> CREATOR =
                new Parcelable.Creator<BinderParcelable>() {
                    @Override public BinderParcelable createFromParcel(Parcel source) {
                        return new BinderParcelable(source);
                    }
                    @Override public BinderParcelable[] newArray(int size) {
                        return new BinderParcelable[size];
                    }
                };
    }
}
