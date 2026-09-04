// File: app/src/main/java/android/os/IAccModeManager.java
package android.os;

/**
 * Stub interface for BYD AccModeManager.
 * This matches the "android.os.IAccModeManager" token seen in system logs.
 * 
 * Used to whitelist apps from power management killing during ACC OFF.
 */
public interface IAccModeManager extends android.os.IInterface {
    
    /**
     * Whitelist an app from power management killing.
     * Transaction ID is likely 5 based on research.
     */
    void setPkg2AccWhiteList(String packageName) throws android.os.RemoteException;

    /**
     * Helper Stub class to match AIDL structure.
     * Required for "IAccModeManager.Stub.asInterface()" to work.
     */
    abstract class Stub extends android.os.Binder implements android.os.IAccModeManager {
        
        private static final String DESCRIPTOR = "android.os.IAccModeManager";
        
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }
        
        public static android.os.IAccModeManager asInterface(android.os.IBinder obj) {
            if (obj == null) {
                return null;
            }
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof android.os.IAccModeManager)) {
                return (android.os.IAccModeManager) iin;
            }
            return new Proxy(obj);
        }
        
        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
        
        private static class Proxy implements android.os.IAccModeManager {
            private android.os.IBinder mRemote;
            
            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }
            
            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }
            
            @Override
            public void setPkg2AccWhiteList(String packageName) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(packageName);
                    // Transaction code 5 based on research - may need adjustment
                    boolean _status = mRemote.transact(5, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
