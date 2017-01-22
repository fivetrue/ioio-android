package ioio.lib.android.util;

import ioio.lib.android.api.IOIO;
import ioio.lib.android.api.IOIOFactory;
import ioio.lib.android.api.exception.ConnectionLostException;
import ioio.lib.android.api.exception.IncompatibilityException;
import ioio.lib.android.spi.IOIOConnectionFactory;
import ioio.lib.android.spi.Log;

public abstract class IOIOBaseApplicationHelper implements IOIOConnectionManager.IOIOConnectionThreadProvider {
	private static final String TAG = "IOIOBaseApplicationHelper";
	protected final IOIOLooperProvider looperProvider_;

	public IOIOBaseApplicationHelper(IOIOLooperProvider provider) {
		looperProvider_ = provider;
	}

	/**
	 * A thread, dedicated for communication with a single physical IOIO device.
	 */
	protected static class IOIOThread extends IOIOConnectionManager.Thread {
		protected IOIO ioio_;
		private boolean abort_ = false;
		private boolean connected_ = false;
		private final IOIOLooper looper_;
		private final IOIOConnectionFactory connectionFactory_;

		IOIOThread(IOIOLooper looper, IOIOConnectionFactory factory) {
			looper_ = looper;
			connectionFactory_ = factory;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see ioio.lib.util.IOIOConnectionThread#run()
		 */
		@Override
		public final void run() {
			super.run();
			while (!abort_) {
				try {
					synchronized (this) {
						if (abort_) {
							break;
						}
						ioio_ = IOIOFactory.create(connectionFactory_
								.createConnection());
					}
				} catch (Exception e) {
					Log.e(TAG, "Failed to create IOIO, aborting IOIOThread!");
					return;
				}
				// if we got here, we have a ioio_!
				try {
					ioio_.waitForConnect();
					connected_ = true;
					looper_.setup(ioio_);
					while (!abort_ && ioio_.getState() == IOIO.State.CONNECTED) {
						looper_.loop();
					}
				} catch (ConnectionLostException e) {
				} catch (InterruptedException e) {
					ioio_.disconnect();
				} catch (IncompatibilityException e) {
					Log.e(TAG, "Incompatible IOIO firmware", e);
					looper_.incompatible(ioio_);
					// nothing to do - just wait until physical
					// disconnection
				} catch (Exception e) {
					Log.e(TAG, "Unexpected exception caught", e);
					ioio_.disconnect();
					break;
				} finally {
					try {
						ioio_.waitForDisconnect();
					} catch (InterruptedException e1) {
					}
					synchronized (this) {
						ioio_ = null;
					}
					if (connected_) {
						looper_.disconnected();
						connected_ = false;
					}
				}
			}
			Log.d(TAG, "IOIOThread is exiting");
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see ioio.lib.util.IOIOConnectionThread#abort()
		 */
		@Override
		public synchronized final void abort() {
			abort_ = true;
			if (ioio_ != null) {
				ioio_.disconnect();
			}
			if (connected_) {
				interrupt();
			}
		}
	}

	@Override
	public IOIOConnectionManager.Thread createThreadFromFactory(IOIOConnectionFactory factory) {
		IOIOLooper looper = looperProvider_.createIOIOLooper(factory.getType(),
				factory.getExtra());
		if (looper == null) {
			return null;
		}
		return new IOIOThread(looper, factory);
	}

}