package com.byd.car.property;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of {@code com.byd.car.property.CarPropertyValue} from DiCarServer.apk.
 *
 * <p>The wire format is class-name + value: writer emits
 * {@code Class.getName()} (e.g. {@code "java.lang.Integer"}, {@code "[I"}) then
 * the raw bytes for that type. Reader switches on the name string. We mirror
 * the writer logic exactly so the system service can deserialize.
 */
public class CarPropertyValue<V> implements Parcelable {
    public String mPropertyId;
    public final String mPropertyKey;
    public V mValue;

    public CarPropertyValue(String propertyKey, V value) {
        this.mPropertyKey = propertyKey;
        this.mValue = value;
    }

    public String getPropertyId() { return mPropertyId; }
    public String getPropertyKey() { return mPropertyKey; }
    public V getValue() { return mValue; }
    public void setPropertyId(String propertyId) { this.mPropertyId = propertyId; }
    public void setValue(V value) { this.mValue = value; }

    public int getIntValue() {
        V v = this.mValue;
        if (v instanceof Integer) return ((Integer) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); }
            catch (Throwable ignore) { return Integer.MIN_VALUE; }
        }
        if (v instanceof Double) return ((Double) v).intValue();
        if (v instanceof Float) return ((Float) v).intValue();
        return Integer.MIN_VALUE;
    }

    public boolean getBooleanValue() {
        V v = this.mValue;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) {
            String s = (String) v;
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        return false;
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(this.mPropertyKey);
        parcel.writeString(this.mPropertyId);
        V v = this.mValue;
        Class<?> cls = v == null ? null : v.getClass();
        parcel.writeString(cls != null ? cls.getName() : null);
        if (cls == null) return;
        if (String.class.equals(cls)) {
            parcel.writeString((String) this.mValue);
            return;
        }
        if (byte[].class.equals(cls)) {
            parcel.writeByteArray((byte[]) this.mValue);
            return;
        }
        if (Integer.TYPE.equals(cls) || Integer.class.equals(cls)) {
            parcel.writeInt(((Number) this.mValue).intValue());
            return;
        }
        if (Long.TYPE.equals(cls) || Long.class.equals(cls)) {
            parcel.writeLong(((Number) this.mValue).longValue());
            return;
        }
        if (Float.TYPE.equals(cls) || Float.class.equals(cls)) {
            parcel.writeFloat(((Number) this.mValue).floatValue());
            return;
        }
        if (Double.TYPE.equals(cls) || Double.class.equals(cls)) {
            parcel.writeDouble(((Number) this.mValue).doubleValue());
            return;
        }
        if (Boolean.TYPE.equals(cls) || Boolean.class.equals(cls)) {
            parcel.writeInt(((Boolean) this.mValue).booleanValue() ? 1 : 0);
            return;
        }
        if (cls.equals(int[].class)) {
            int[] arr = (int[]) this.mValue;
            parcel.writeInt(arr.length);
            parcel.writeIntArray(arr);
            return;
        }
        if (float[].class.equals(cls)) {
            float[] arr = (float[]) this.mValue;
            parcel.writeInt(arr.length);
            parcel.writeFloatArray(arr);
            return;
        }
        if (long[].class.equals(cls)) {
            long[] arr = (long[]) this.mValue;
            parcel.writeInt(arr.length);
            for (long j : arr) parcel.writeLong(j);
            return;
        }
        parcel.writeParcelable((Parcelable) this.mValue, flags);
    }

    @SuppressWarnings("unchecked")
    public CarPropertyValue(Parcel parcel) {
        this.mPropertyKey = parcel.readString();
        this.mPropertyId = parcel.readString();
        String cls = parcel.readString();
        if (cls == null) { this.mValue = null; return; }
        if ("java.lang.String".equals(cls)) {
            this.mValue = (V) parcel.readString();
        } else if ("[B".equals(cls)) {
            this.mValue = (V) parcel.createByteArray();
        } else if ("java.lang.Integer".equals(cls)) {
            this.mValue = (V) Integer.valueOf(parcel.readInt());
        } else if ("java.lang.Long".equals(cls)) {
            this.mValue = (V) Long.valueOf(parcel.readLong());
        } else if ("java.lang.Float".equals(cls)) {
            this.mValue = (V) Float.valueOf(parcel.readFloat());
        } else if ("java.lang.Double".equals(cls)) {
            this.mValue = (V) Double.valueOf(parcel.readDouble());
        } else if ("java.lang.Boolean".equals(cls)) {
            this.mValue = (V) Boolean.valueOf(parcel.readInt() != 0);
        } else if ("[I".equals(cls)) {
            parcel.readInt();
            this.mValue = (V) parcel.createIntArray();
        } else if ("[F".equals(cls)) {
            parcel.readInt();
            this.mValue = (V) parcel.createFloatArray();
        } else if ("[J".equals(cls)) {
            int n = parcel.readInt();
            long[] arr = new long[n];
            for (int i = 0; i < n; i++) arr[i] = parcel.readLong();
            this.mValue = (V) arr;
        } else {
            this.mValue = (V) parcel.readParcelable(getClass().getClassLoader());
        }
    }

    @Override
    public String toString() {
        return "CarPropertyValue{mPropertyKey=" + mPropertyKey
            + ", mPropertyId=" + mPropertyId + ", mValue=" + mValue + "}";
    }

    public static final Parcelable.Creator<CarPropertyValue> CREATOR = new Parcelable.Creator<CarPropertyValue>() {
        @Override public CarPropertyValue createFromParcel(Parcel in) { return new CarPropertyValue(in); }
        @Override public CarPropertyValue[] newArray(int size) { return new CarPropertyValue[size]; }
    };
}
