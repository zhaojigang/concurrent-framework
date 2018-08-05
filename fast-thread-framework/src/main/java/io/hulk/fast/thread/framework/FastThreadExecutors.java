package io.hulk.fast.thread.framework;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 类比 {@link java.util.concurrent.Executors}
 *
 * 线程池与Promise的结合使用见 promise-framework io.hulk.promise.framework.DefaultPromiseTest
 *
 * @author zhaojigang
 * @date 2018/8/2
 */
public class FastThreadExecutors {

    /**
     * 创建一个线程数固定（corePoolSize==maximumPoolSize）的线程池
     * 核心线程会一直存在，不被回收
     * 如果一个核心线程由于异常跪了，会新创建一个线程
     * 无界队列LinkedBlockingQueue
     */
    public static Executor newFixedFastThreadPool(int nThreads, String poolName) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new FastThreadLocalThreadFactory(poolName));
    }

    /**
     * corePoolSize==0
     * maximumPoolSize==Integer.MAX_VALUE
     * 队列：SynchronousQueue
     * 创建一个线程池：当池中的线程都处于忙碌状态时，会立即新建一个线程来处理新来的任务
     * 这种池将会在执行许多耗时短的异步任务的时候提高程序的性能
     * 60秒钟内没有使用的线程将会被中止，并且从线程池中移除，因此几乎不必担心耗费资源
     */
    public static Executor newCachedFastThreadPool(String poolName) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new FastThreadLocalThreadFactory(poolName));
    }

    /**
     * 自定义各种参数
     */
    public static Executor newLimitedFastThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                                                    TimeUnit unit, BlockingQueue<Runnable> workQueue, String poolName,
                                                    RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
            new FastThreadLocalThreadFactory(poolName), handler);
    }
}
