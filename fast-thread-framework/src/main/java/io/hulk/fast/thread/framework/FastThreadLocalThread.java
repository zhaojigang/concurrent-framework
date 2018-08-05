package io.hulk.fast.thread.framework;

/**
 * from netty4.1
 */
public class FastThreadLocalThread extends Thread {

    /**
     * 类比Thread的ThreadLocal.ThreadLocalMap threadLocals属性
     */
    private InternalThreadLocalMap threadLocalMap;

    /**
     * ThreadLocal与线程池问题：
     * 线程池中的线程由于会被复用，所以线程池中的每一条线程在执行task结束后，要清理掉其InternalThreadLocalMap和其内的FastThreadLocal信息，
     * 否则，当这条线程在下一次被复用的时候，其ThreadLocalMap信息还存储着上一次被使用的时的信息；
     * 另外，假设这条线程不再被使用，但是这个线程有可能不会被销毁（与线程池的类型和配置相关），那么其InternalThreadLocalMap和其内的FastThreadLocal信息将发生了资源泄露。
     *
     * 所以，如果一个Runnable任务被FastThreadLocalRunnable包裹，那么其InternalThreadLocalMap和其内的FastThreadLocal信息表示会被自动清理，此时：cleanupFastThreadLocals==true
     * 否则，cleanupFastThreadLocals==false，此时线程需要注册到ObjectCleaner上，当线程不可达时，由清理线程清理 其InternalThreadLocalMap和其内的FastThreadLocal信息
     * 值得注意的是，如果在netty中如果普通线程执行任务（不会被FastThreadLocalRunnable包裹），还是要注意"ThreadLocal与线程池问题"，
     * netty对于普通线程仅仅是当线程不可达时才会进行清理操作。
     */
    private final boolean          cleanupFastThreadLocals;

    public FastThreadLocalThread() {
        cleanupFastThreadLocals = false;
    }

    public FastThreadLocalThread(Runnable runnable) {
        super(FastThreadLocalRunnable.wrap(runnable));
        cleanupFastThreadLocals = true;
    }

    public FastThreadLocalThread(Runnable runnable, ThreadGroup threadGroup, String threadName) {
        super(threadGroup, FastThreadLocalRunnable.wrap(runnable), threadName);
        cleanupFastThreadLocals = true;
    }

    public void setThreadLocalMap(InternalThreadLocalMap threadLocalMap) {
        this.threadLocalMap = threadLocalMap;
    }

    public InternalThreadLocalMap threadLocalMap() {
        return this.threadLocalMap;
    }

    /**
     * 是否会自动清理当前线程的"InternalThreadLocalMap和其内的FastThreadLocal信息"
     */
    public boolean willCleanupFastThreadLocals() {
        return this.cleanupFastThreadLocals;
    }

    /**
     * 只有FastThreadLocalThread会作自动清理操作，其他类型的线程不会，
     * 因为只有FastThreadLocalThread有InternalThreadLocalMap threadLocalMap属性，而我们的自动清理操作也是针对该属性做的
     */
    public static boolean willCleanupFastThreadLocals(Thread current) {
        return current instanceof FastThreadLocalThread
               && ((FastThreadLocalThread) current).willCleanupFastThreadLocals();
    }
}