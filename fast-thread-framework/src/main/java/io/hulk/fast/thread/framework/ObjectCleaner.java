package io.hulk.fast.thread.framework;

import io.hulk.common.ConcurrentSet;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * from netty4.1
 */
public class ObjectCleaner {
    /**
     * 不可达的Object的队列
     */
    private static final ReferenceQueue<Object> REFERENCE_QUEUE = new ReferenceQueue<>();
    /**
     * 设置从REFERENCE_QUEUE阻塞获取值的等待时长，在等待期间，不会释放cpu资源，所以会占用一个核。
     * 这个时间设置的太短，会进行空循环；设置的太长会占用核，所以netty提供了参数来进行设置:
     * io.netty.util.internal.ObjectCleaner.refQueuePollTimeout，默认为10s
     */
    private static final int REFERENCE_QUEUE_POLL_TIMEOUT_MS = 10000;
    /**
     * 存活的 AutomaticCleanerReference 对象
     * <p>
     * 为什么需要并发SET?
     * 如果是同一个线程的set多个ThreadLocal一定不会有问题，因为同一个线程是顺序执行；
     * 如果是两个线程同时set各自的FastThreadLocal，就会同时调用ObjectCleaner#register方法，由于LIVE_SET是一个类变量，即是一个共享变量，
     * 此时就可能发生并发问题。
     */
    private static Set<Object> LIVE_SET = new ConcurrentSet<>();
    /**
     * cleaner线程是否已经在运行中
     * 尽量保证在整个系统的运行中，只有一个CLEAN_TASK在运行（类似于gc线程）
     */
    private static final AtomicBoolean CLEANER_RUNNING = new AtomicBoolean();
    /**
     * 清理线程的名称
     */
    private static final String CLEANUP_THREAD_NAME = ObjectCleaner.class.getSimpleName() + "Thread";
    /**
     * 清理线程
     */
    private static final Runnable CLEAN_TASK = () -> {
        boolean interrupted = false;
        for (; ; ) {
            while (!LIVE_SET.isEmpty()) {
                AutomaticCleanerReference reference;
                try {
                    // 1、从REFERENCE_QUEUE阻塞获取不可达的reference
                    reference = (AutomaticCleanerReference) REFERENCE_QUEUE.remove(REFERENCE_QUEUE_POLL_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    continue;
                }
                if (reference != null) {
                    try {
                        // 2、执行reference的清理操作：
                        // 清理当前的线程的InternalThreadLocalMap中注册时的FastThreadLocal的值 和 其在Set<FastThreadLocal<?>>的值
                        /**
                         * 执行清理线程（当referent是一个线程对象时，此时表示这个线程对象已经不可达了，
                         *
                         * 就会对该线程对象中的InternalThreadLocalMap中的Object[]中删除index(FastThreadLocal标识)位置的value元素，
                         * 然后从Set<FastThreadLocal<?>>中删除当前的FastThreadLocal对象
                         *
                         * 注意：注册到ObjectCleaner是每一个FastThreadLocal做的事儿，所以这里的删除也是对每一个FastThreadLocal进行操作，
                         * 而不会对线程的InternalThreadLocalMap整体或者其内的所有FastThreadLocal做操作。
                         * 另外，要注意：LIVE_SET存储的key不是当前线程，而是一个AutomaticCleanerReference，该对象在每次register的时候都会进行new，
                         * 所以同一个线程的InternalThreadLocalMap中不同的ThreadLocal会分别被封装为一个AutomaticCleanerReference
                         */
                        reference.cleanup();
                    } catch (Exception e) {
                    }
                    // 3、将reference从存活列表中删除
                    LIVE_SET.remove(reference);
                }
            }
            // 4、设置清理线程的运行状态为false
            CLEANER_RUNNING.set(false);

            // 5、再次检测（优化）
            // 假设此处又来了一个任务，LIVE_SET中添加了这个任务；这时候设置CLEANER_RUNNING由false变为true，
            // 如果设置成功，则继续进行clean操作，就不需要再创建一个线程来执行CLEANER_TASK任务了；（这也是外层for(;;)循环和此处的if判断的意义所在）
            // 如果设置失败，说明，已经有线程开始执行CLEANER_TASK任务了，那么当前线程直接退出就ok了
            if (LIVE_SET.isEmpty() || !CLEANER_RUNNING.compareAndSet(false, true)) {
                break;
            }
        }

        if (interrupted) {
            // As we caught the InterruptedException above we should mark the Thread as interrupted.
            Thread.currentThread().interrupt();
        }
    };

    /**
     * Register the given {@link Object} for which the {@link Runnable} will be executed once there are no references
     * to the object anymore.
     *
     * This should only be used if there are no other ways to execute some cleanup once the Object is not reachable
     * anymore because it is not a cheap way to handle the cleanup.
     */
    public static void register(Thread current, Runnable runnable) {
        // 1、创建 AutomaticCleanerReference
        AutomaticCleanerReference reference = new AutomaticCleanerReference(current, runnable);
        // 2、将当前的 AutomaticCleanerReference 添加到LIVE_SET
        LIVE_SET.add(reference);
        // 3、cas启动cleaner线程:确保只有一个清理线程在run
        if (CLEANER_RUNNING.compareAndSet(false, true)) {
            Thread cleanupThread = new FastThreadLocalThread(CLEAN_TASK);
            cleanupThread.setName(CLEANUP_THREAD_NAME);
            cleanupThread.setPriority(Thread.MIN_PRIORITY);
            cleanupThread.setDaemon(true);
            cleanupThread.start();
        }
    }

    private static final class AutomaticCleanerReference extends WeakReference<Object> {
        private final Runnable cleanupTask;

        /**
         * 将object包裹为referent，并关联ReferenceQueue为REFERENCE_QUEUE；
         * 当referent不可达时，整个reference对象会进入REFERENCE_QUEUE，之后我们对REFERENCE_QUEUE进行一些操作
         */
        public AutomaticCleanerReference(Object referent, Runnable cleanupTask) {
            super(referent, REFERENCE_QUEUE);
            this.cleanupTask = cleanupTask;
        }

        public void cleanup() {
            cleanupTask.run();
        }

        @Override
        public Thread get() {
            return null;
        }

        @Override
        public void clear() {
            LIVE_SET.remove(this);
            super.clear();
        }
    }
}
