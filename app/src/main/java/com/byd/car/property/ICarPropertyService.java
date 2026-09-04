package com.byd.car.property;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import com.byd.datasource.feature.Response;
import com.byd.datasource.feature.Status;

import java.util.List;

/**
 * Mirror of {@code com.byd.car.property.ICarPropertyService} from DiCarServer.apk.
 *
 * <p>Only the client-side proxy is implemented — we never host this service,
 * we only call into it. Transaction codes match the system service exactly:
 * {@code setProperties=1, getProperty=2, getProperties=3, …}.
 *
 * <p>The interface descriptor MUST match the system service ({@code
 * com.byd.car.property.ICarPropertyService}) byte-for-byte; otherwise
 * {@link android.os.Parcel#enforceInterface} on the server side rejects.
 */
public interface ICarPropertyService extends IInterface {

    Response getProperty(String propertyKey) throws RemoteException;
    Response getProperties(String[] propertyKeys) throws RemoteException;
    List<CarPropertyConfig> getPropertyConfigs(String[] propertyKeys) throws RemoteException;
    Status setProperties(CarPropertyValue[] values) throws RemoteException;

    abstract class Stub extends Binder implements ICarPropertyService {
        public static final String DESCRIPTOR = "com.byd.car.property.ICarPropertyService";
        public static final int TRANSACTION_setProperties = 1;
        public static final int TRANSACTION_getProperty = 2;
        public static final int TRANSACTION_getProperties = 3;
        public static final int TRANSACTION_getPropertyConfigs = 4;

        public static ICarPropertyService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ICarPropertyService) {
                return (ICarPropertyService) local;
            }
            return new Proxy(binder);
        }
    }

    final class Proxy implements ICarPropertyService {
        private final IBinder mRemote;
        Proxy(IBinder remote) { this.mRemote = remote; }

        @Override public IBinder asBinder() { return mRemote; }

        @Override
        public Response getProperty(String propertyKey) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR);
                data.writeString(propertyKey);
                mRemote.transact(Stub.TRANSACTION_getProperty, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0 ? Response.CREATOR.createFromParcel(reply) : null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public Response getProperties(String[] propertyKeys) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR);
                data.writeStringArray(propertyKeys);
                mRemote.transact(Stub.TRANSACTION_getProperties, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0 ? Response.CREATOR.createFromParcel(reply) : null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public List<CarPropertyConfig> getPropertyConfigs(String[] propertyKeys) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR);
                data.writeStringArray(propertyKeys);
                mRemote.transact(Stub.TRANSACTION_getPropertyConfigs, data, reply, 0);
                reply.readException();
                return reply.createTypedArrayList(CarPropertyConfig.CREATOR);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public Status setProperties(CarPropertyValue[] values) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR);
                data.writeTypedArray(values, 0);
                mRemote.transact(Stub.TRANSACTION_setProperties, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0 ? Status.CREATOR.createFromParcel(reply) : null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
