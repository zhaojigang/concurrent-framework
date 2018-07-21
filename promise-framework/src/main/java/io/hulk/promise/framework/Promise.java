package io.hulk.promise.framework;

import java.util.List;

/**
 * Special {@link Future} which is writable.
 *
 * 添加设置操作
 * 将Future中返回值为Future的全部override为Promise
 * @author zhaojigang
 * @date 2018/7/16
 */
public interface Promise<V> extends Future<V> {
    /**
     * Marks this future as a success and notifies all listeners.
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setSuccess(V result);

    /**
     * Marks this future as a success and notifies all listeners.
     *
     * @return {@code true} if and only if successfully marked this future as a success.
     *         Otherwise {@code false} because this future is already marked as either a success or a failure.
     */
    boolean trySuccess(V result);

    /**
     * Marks this future as a failure and notifies all listeners.
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setFailure(Throwable cause);

    /**
     * Marks this future as a failure and notifies all listeners.
     *
     * @return {@code true} if and only if successfully marked this future as a failure.
     *         {@code false} because this future is already marked as either a success or a failure.
     */
    boolean tryFailure(Throwable cause);

    /**
     * Make this future impossible to cancel.
     *
     * @return {@code true} if and only if successfully marked this future as uncancellable
     *                      or it is already done without being cancelled.
     *         {@code false} if this future has been cancelled already.
     */
    boolean setUncancellable();

    @Override
    Promise<V> addListener(FutureListener<V> listener);

    @Override
    Promise<V> addListeners(List<FutureListener<V>> listeners);

    @Override
    Promise<V> removeListener(FutureListener<V> listener);

    @Override
    Promise<V> removeListeners(List<FutureListener<V>> listeners);

    @Override
    Promise<V> sync() throws InterruptedException;

    @Override
    Promise<V> syncUninterruptibly();

    @Override
    Promise<V> await() throws InterruptedException;

    @Override
    Promise<V> awaitUninterruptibly();
}
