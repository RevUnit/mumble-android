package org.pcgod.mumbleclient.service;

import android.util.Log;

import org.pcgod.mumbleclient.Globals;

import java.io.IOException;

/**
 * Provides the general structure for the socket readers.
 *
 * @author Rantanen
 */
public abstract class MumbleSocketReader implements Runnable {
    private final Object monitor;
    private boolean running;
    private final Thread thread;

    @Override
    public void run() {
        try {
            while (isRunning()) {
                process();
            }
        } catch (final IOException e) {
            // If we aren't running, exeption is expected
            if (isRunning()) {
                Log.e(Globals.LOG_TAG, "Error reading socket", e);
                // restart socket
            }
        } finally {
            setRunning(false);
            synchronized (getMonitor()) {
                getMonitor().notifyAll();
            }
        }
    }

//	protected Runnable runnable = new Runnable() {
//		@Override
//		public void run() {
//			try {
//				while (isRunning()) {
//					process();
//				}
//			} catch (final IOException ex) {
//				// If we aren't running, exception is expected.
//				if (isRunning()) {
//					Log.e(Globals.LOG_TAG, "Error reading socket", ex);
//                    // restart socket
//				}
//			} finally {
//				running = false;
//				synchronized (monitor) {
//					monitor.notifyAll();
//				}
//			}
//		}
//	};

    /**
     * Constructs a new Reader instance
     *
     * @param monitor The monitor that should be signaled when the thread is
     *                quitting.
     */
    public MumbleSocketReader(final Object monitor, final String name) {
        this.monitor = monitor;
        this.running = true;
        this.thread = new Thread(this, name);
    }

    /**
     * The condition that must be fulfilled for the reader to continue running.
     *
     * @return True while the reader should keep processing the socket.
     */
    public boolean isRunning() {
        return running && thread.isAlive();
    }

    public void setRunning(final boolean running) {
        this.running = running;
    }

    public Object getMonitor() {
        return monitor;
    }

    public void start() {
        this.thread.start();
    }

    public void stop() {
        this.thread.interrupt();
        try {
            this.thread.join();
        } catch (final InterruptedException e) {
            Log.w(Globals.LOG_TAG, e);
        }
    }

    /**
     * A single processing step that reads and processes a message from the
     * socket.
     *
     * @throws IOException
     */
    protected abstract void process() throws IOException;
}
