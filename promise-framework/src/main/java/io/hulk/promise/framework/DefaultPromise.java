package io.hulk.promise.framework;

import io.hulk.common.utils.ObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * from netty4.1
 * <p>
 * 一、DefaultPromise状态转换图：
 * A {@link DefaultPromise} is either <em>uncompleted</em> or <em>completed</em>.
 * When an I/O operation begins, a new future object is created.
 * The new future is uncompleted initially - it is neither succeeded, failed, nor cancelled
 * because the I/O operation is not finished yet.
 * If the I/O operation is finished either successfully, with failure, or by cancellation,
 * the future is marked as completed with more specific information, such as the cause of the
 * failure.
 * Please note that even failure and cancellation belong to the completed state.
 * <pre>
 *                                      +---------------------------+
 *                                      | Completed successfully    |
 *                                      +---------------------------+
 *                                 +---->      isDone() = true      |
 * +--------------------------+    |    |   isSuccess() = true      |
 * |        Uncompleted       |    |    +===========================+
 * +--------------------------+    |    | Completed with failure    |
 * |      isDone() = false    |    |    +---------------------------+
 * |   isSuccess() = false    |----+---->      isDone() = true      |
 * | isCancelled() = false    |    |    |       cause() = non-null  |
 * |       cause() = null     |    |    +===========================+
 * +--------------------------+    |    | Completed by cancellation |
 *                                 |    +---------------------------+
 *                                 +---->      isDone() = true      |
 *                                      | isCancelled() = true      |
 *                                      +---------------------------+
 * </pre>
 * <p>
 * <p>
 * 二、DefaultPromise实现了两种执行机制：
 * 1、future：wait/notify实现，可能要阻塞，使用方最终调用到DefaultPromise父类AbstractFuture#get或者DefaultPromise#syncXxx
 * 2、listener：其实就是callback实现，不需要阻塞，当setSuccess/trySuccess/setFailure/tryFailure/cancel会直接调用listener（回调函数）当然如果有等待条件的其他线程，也会notifyAll
 * <p>
 * 推荐使用第二种，完全异步的。
 */
public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPromise.class);
    /**
     * 返回结果result的原子更新器
     */
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER
            = AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    /**
     * 返回结果
     */
    private volatile Object result;
    /**
     * 成功的返回标记
     */
    private static final Object SUCCESS = new Object();
    /**
     * 不可取消的标记
     */
    private static final Object UNCANCELLABLE = new Object();
    /**
     * wait线程的数量，注意该参数的修改要进行同步（恰好该参数的所有修改地方都在一个synchronized中）
     */
    private short waiters;
    /**
     * cancel()时要将此项异常塞入result
     */
    private static final Throwable CANCELLATION_CAUSE = new CancellationException(DefaultPromise.class.getName() + " invoked cancel()");
    /**
     * Threading - synchronized(this) 事件监听器列表
     * If {@code empty}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     * 也就是说 一个listener通知过一次就会被删除，不会再通知第二次
     */
    private List<FutureListener<V>> listeners;
    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification.
     */
    private boolean notifyingListeners;

    /**
     * future返回是否成功
     *
     * @return {@code true} 结果不为空 && 结果不是Throwable（失败）&& 结果不是UNCANCELLABLE（不能取消）
     */
    @Override
    public boolean isSuccess() {
        /**
         * 将成员变量result转换为局部变量进行操作的原因？
         *
         * 一、是因为 在下面的代码中会多次调用this.result，当外界的this.result引用发生变化时，由于this.result是被volatile修饰的，如果直接使用this.result将会导致多次获取的result不同，
         * 但是this.result引用发生变化时，局部变量result不会发生变化（注意修改的是this.result引用的值，而不是this.result指向的地址的值，类似下边的程序）
         * <pre>
         *         public static void main(String[] args) {
         *          DefaultPromiseTest test = new DefaultPromiseTest();
         *
         *          Model m2 = test.m;
         *          System.out.println(m2);
         *
         *          test.m = new Model(200L); // 注意：这里不是this.m.setId(300)，所以下面的m2不变
         *          System.out.println(m2);
         *        }
         *
         * </pre>
         *
         * 二、由于this.result是被volatile修饰的，每次获取都要强制从主存中获取，无法从工作线程直接获取，所以代价较大，而且将频繁操作的成员变量局部化更方便JIT优化
         * https://blog.csdn.net/shaomingliang499/article/details/50549306
         */
        Object result = this.result;
        return result != null && !(result instanceof Throwable) && result != UNCANCELLABLE;
    }

    /**
     * 等待线程是否可取消
     *
     * @return {@code true} 如果返回结果result为null，表示没有返回成功，也没有返回失败，也没有设置不可取消，此时可以取消
     */
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    /**
     * 查询cause：如果result instanceof Throwable，那么表示返回结果出错了，否则 cause = null，表示一定没有错误
     *
     * @return
     */
    @Override
    public Throwable cause() {
        Object result = this.result;
        return result instanceof Throwable ? (Throwable) result : null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean setUncancellable() {
        Object result = this.result;
        /**
         * 从uncompleted设置为UNCANCELLABLE，如果设置成功，直接返回
         */
        if (result == UNCANCELLABLE || RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }

        /**
         * 如果completed 而且又没被cancel()，此时返回true
         */
        return isDone0(result) && !isCancelled();
    }

    @Override
    public Promise<V> addListener(FutureListener<V> listener) {
        ObjectUtil.checkNotNull(listener, "listener");
        /**
         * 防止多个线程同时操作listeners队列
         */
        synchronized (this) {
            if (listeners == null) {
                listeners = new ArrayList<>();
            }
            listeners.add(listener);
        }

        /**
         * 如果该listener是后加入的，则直接唤醒
         */
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(List<FutureListener<V>> listeners) {
        ObjectUtil.checkNotNull(listeners, "listeners");
        synchronized (this) {
            if (this.listeners == null) {
                this.listeners = new ArrayList<>();
            }
            this.listeners.addAll(listeners);
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> removeListener(FutureListener<V> listener) {
        ObjectUtil.checkNotNull(listeners, "listeners");
        ObjectUtil.checkNotNull(listener, "listener");
        synchronized (this) {
            listeners.remove(listener);
        }
        return this;
    }

    @Override
    public Promise<V> removeListeners(List<FutureListener<V>> listeners) {
        ObjectUtil.checkNotNull(this.listeners, "listeners");
        ObjectUtil.checkNotNull(listeners, "listeners");
        synchronized (this) {
            this.listeners.removeAll(listeners);
        }
        return this;
    }

    @Override
    public Promise<V> sync() throws InterruptedException {
        /**
         * 如果await()发生了异常，这里正好直接抛出
         */
        await();
        /**
         * 如果await()返回了错误，也直接抛出
         */
        rethrowIfFailed();
        return this;
    }

    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }
        throw (RuntimeException) cause;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        if (isDone()) {
            return this;
        }

        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        /**
         * wait()/notify()机制：
         * 前提：每个对象都有一个锁 + 一个锁等待队列 + 一个条件等待队列。
         * 线程协作：wait()/notify()通常都是由两个线程来协作的，一个wait()等待条件，另一个notify()唤醒等待线程
         * 为什么加锁：wait()/notify()是必须加锁执行的（内部执行机制），否则会抛出异常IllegalMonitorStateException，锁对象是当前实例。
         *
         * wait内部执行机制：
         * 1、把当前线程放入锁对象的条件等待队列，之后释放锁（注意：一定会释放锁，否则notify的线程将无法获取该对象锁），进入阻塞状态WAITING或TIMED_WAITING
         * 2、当等待时间到了或者被其他线程notify/notifyAll了，则等待的当前线程从条件等待队列中移除出来，之后再尝试获取锁，如果没有获取锁，进入锁等待队列，线程状态改为BLOCKED；如果获取了锁，从wait调用中返回
         *
         * 为什么要写成:
         * <pre>
         *    while (!isDone()) {
         *      wait();
         *    }
         * </pre>
         * 而不是
         * <pre>
         *     if(!isDone()) {
         *         wait();
         *     }
         * </pre>
         *
         * wait()表示阻塞等待，正常情况下while和if形式是等价的，但是为了防止wait()被意外唤醒，所以需要在wait()之后继续进行判断
         */
        synchronized (this) {
            while (!isDone()) {
                /**
                 * 执行wait()之前：waiters加1
                 */
                incWaiters();
                try {
                    wait();
                } finally {
                    /**
                     * wait()结束之后，waiters减1
                     */
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        /**
         * 捕获了中断异常，默默执行中断
         */
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit timeUnit) throws InterruptedException {
        return await0(timeUnit.toNanos(timeout), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit timeUnit) {
        try {
            return await0(timeUnit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            throw new InternalError();
        }
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(TimeUnit.MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(TimeUnit.MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            throw new InternalError();
        }
    }

    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof Throwable || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    /**
     * 查看java.util.concurrent.Future#cancel()的注释，
     * This attempt will fail if the task has already completed（成功 || 失败 || 已被取消）, has already been cancelled,
     * or could not be cancelled for some other reason
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     * @return
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE)) {
            checkNotifyWaiters();
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return result instanceof CancellationException;
    }

    @Override
    public boolean isDone() {
        return isDone0(this.result);
    }

    /**
     * 分析并发问题：
     * 1、假设没有notifyingListeners：
     * 当前线程A执行到while(true)的时候，假设另一条线程B也添加了FutureListener并进入了第一个同步块，此时线程B也进入了while(true)，
     * B开始执行后来的这些FutureListeners，之后A才开始执行一开始的FutureListeners，这样就不能保证FIFO的执行FutureListener
     * <p>
     * 2、加入notifyingListeners：
     * 在线程A执行到第二个synchronized块中的if (this.listeners == null)中之前，线程B进入第一个同步块，由于notifyingListeners = true，则直接返回了，
     * 而B后来添加的FutureListeners，A会在第二个同步快判断的时候发现当前的this.listeners.size>0，会继续赋值给本地变量继续第二轮循环.
     * <p>
     * 这里有一个疑问：当外界的this.listeners发生变化时，temListeners是否变化，假设A执行到while(true)，B执行了addListener，则此时外界的this.listener改变了值，temListener是否发生变化
     */
    private void notifyListeners() {
        List<FutureListener<V>> temListeners;
        synchronized (this) {
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            notifyingListeners = true;
            temListeners = this.listeners;
            this.listeners = null; // 通知完之后就置空，不再通知第二次
        }

        while (true) {
            notifyListeners0(temListeners);
            synchronized (this) {
                if (this.listeners == null) {
                    notifyingListeners = false;
                    return;
                }
                temListeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(List<FutureListener<V>> listeners) {
        for (FutureListener<V> listener : listeners) {
            try {
                listener.operationComplete(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 设置成功标志
     * result != null ? result : SUCCESS
     */
    private boolean setSuccess0(V result) {
        return setValue0(result != null ? result : SUCCESS);
    }

    /**
     * 设置失败标志
     * result == cause
     */
    private boolean setFailure0(Throwable cause) {
        return setValue0(ObjectUtil.checkNotNull(cause, "cause"));
    }

    private boolean setValue0(Object result) {
        /**
         * 更新result结果，唤醒所有阻塞线程
         * 将result从null置为result 或者 从UNCANCELLABLE置为result（因为有可能是先将result置为UNCANCELLABLE的）
         */
        if (RESULT_UPDATER.compareAndSet(this, null, result) ||
                RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, result)) {
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private void checkNotifyWaiters() {
        synchronized (this) {
            if (waiters > 0) {
                notifyAll();
            }
        }
    }

    public boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private void incWaiters() {
        if (++waiters > Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters :" + this);
        }
    }

    private void decWaiters() {
        --waiters;
    }

    /**
     * 关于中断：
     * <p>
     * 1、前提：线程有六种状态，{@link Thread#getState()}
     * NEW：A thread that has not yet started is in this state.
     * RUNNABLE：A thread executing in the Java virtual machine is in this state.
     * it may be waiting for other resources from the operating system such as processor.
     * BLOCKED：A thread that is blocked waiting for a monitor lock is in this state.
     * A thread in the blocked state is waiting for a monitor lock
     * to enter a synchronized block/method or
     * reenter a synchronized block/method after calling {@link Object#wait() Object.wait}.
     * WAITING：A thread that is waiting indefinitely for another thread to perform a particular action is in this state.
     * TIMED_WAITING：A thread that is waiting for another thread to perform an action for up to a specified waiting time is in this state.
     * TERMINATED：A thread that has exited is in this state.
     * <p>
     * 2、在不同阶段调用中断Thread.currentThread().interrupt()
     * NEW/TERMINATED：interrupt()中断没有任何效果，中断位isInterrupted=false
     * RUNNABLE: interrupt()中断没有效果，中断位isInterrupted=true，在run()方法中自己选择合适的点去处理
     * BLOCKED：interrupt()中断位isInterrupted=true，不会使当前线程跳出锁等待队列，也就是说依然在等待锁
     * WAITING/TIMED_WAITING: interrupt()抛出InterruptedException，设置isInterrupted=false，所以根据需要，需要自己去设置中断位
     *
     * @param timeoutNanos  纳秒级别的超时时间
     * @param interruptable 是否可中断
     * @return
     * @throws InterruptedException
     */
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        /**
         * 如果completed，直接返回
         */
        if (isDone()) {
            return true;
        }

        /**
         * 如果传入的超时时间<=0，直接result
         */
        if (timeoutNanos <= 0) {
            return isDone();
        }

        /**
         * 如果可中断 && 线程已被中断，抛出中断异常
         */
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        boolean interrupted = false;
        long startTimeNanos = System.nanoTime();
        try {
            while (true) {
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    incWaiters();
                    try {
                        wait(timeoutNanos / 1000000, (int) timeoutNanos % 1000000);
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            /**
                             * 对于中断来讲，抛出了中断异常时，Thread.currentThread().isInterrupted() == false，即不会设置中断标志位。
                             * 需要通过Thread.currentThread().interrupt()来设置中断标志位，来使外界自己根据中断位去做一些事
                             * Waits for this future to be completed without interruption. 所以在最后的finally才会中断
                             */
                            interrupted = true;
                        }
                    } finally {
                        decWaiters();
                    }
                }

                if (isDone()) {
                    return true;
                }

                /**
                 * 防护性判断
                 */
                if (System.nanoTime() - startTimeNanos >= timeoutNanos) {
                    return isDone();
                }
            }
        } finally {
            if (interrupted) {
                /**
                 * 此时线程处于RUNNABLE状态，执行interrupt()设置中断标志位
                 */
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(this.getClass().getSimpleName()).append("@").append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            builder.append("success");
        } else if (result == UNCANCELLABLE) {
            builder.append("uncancellable");
        } else if (result instanceof Throwable) {
            builder.append(result);
        } else if (result != null) {
            builder.append("success " + result);
        } else {
            builder.append("incompleted");
        }

        return builder.toString();
    }
}
