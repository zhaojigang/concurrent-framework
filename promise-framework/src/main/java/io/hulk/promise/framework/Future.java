package io.hulk.promise.framework;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author zhaojigang
 * @date 2018/7/16
 */
public interface Future<V> extends java.util.concurrent.Future<V> {
    /**
     * Returns {@code true} if and only if the I/O operation was completed successfully.
     */
    boolean isSuccess();

    /**
     * returns {@code true} if and only if the operation can be cancelled via {@link #cancel(boolean)}.
     */
    boolean isCancellable();

    /**
     * Returns the cause of the failed I/O operation if the I/O operation has failed.
     *
     * @return the cause of the failure. {@code null} if succeeded or this future is not completed yet.
     */
    Throwable cause();

    /**
     * Adds the specified listener to this future.
     * The specified listener is notified when this future is {@linkplain #isDone() done}.
     * If this future is already completed, the specified listener is notified immediately.
     */
    Future<V> addListener(FutureListener<V> listener);

    /**
     * Adds the specified listeners to this future.
     * The specified listeners is notified when this future is {@linkplain #isDone() done}.
     * If this future is already completed, the specified listeners is notified immediately.
     */
    Future<V> addListeners(List<FutureListener<V>> listeners);

    /**
     * Removes the first occurrence of the specified listener from this future.
     * The specified listener is no longer notified when this future is {@linkplain #isDone() done}.
     * If the specified listener is not associated with this future, this method does nothing and returns silently.
     */
    Future<V> removeListener(FutureListener<V> listener);

    /**
     * Removes the first occurrence for each of the listeners from this future.
     * The specified listeners is no longer notified when this future is {@linkplain #isDone() done}.
     * If the specified listeners is not associated with this future, this method does nothing and returns silently.
     */
    Future<V> removeListeners(List<FutureListener<V>> listeners);

    /**
     * Waits for this future until it is done, and rethrows the cause of the failure if this future failed.
     *
     * @throws InterruptedException
     *         if the current thread was interrupted
     */
    Future<V> sync() throws InterruptedException;

    /**
     * Waits for this future until it is done, and rethrows the cause of the failure if this future failed.
     * This method catches an {@link InterruptedException} and discards it silently.
     */
    Future<V> syncUninterruptibly();

    /**
     * Waits for this future to be completed.
     *
     * @throws InterruptedException
     *         if the current thread was interrupted
     */
    Future<V> await() throws InterruptedException;

    /**
     * Waits for this future to be completed without interruption.
     * This method catches an {@link InterruptedException} and discards it silently.
     */
    Future<V> awaitUninterruptibly();

    /**
     * Waits for this future to be completed within the specified time limit.
     *
     * @return {@code true} if and only if the future was completed within the specified time limit
     * @throws InterruptedException if the current thread was interrupted
     */
    boolean await(long timeout, TimeUnit timeUnit) throws InterruptedException;

    /**
     * Waits for this future to be completed within the specified time limit.
     *
     * @return {@code true} if and only if the future was completed within the specified time limit without interruption.
     * This method catches an {@link InterruptedException} and discards it silently.
     */
    boolean awaitUninterruptibly(long timeout, TimeUnit timeUnit);

    /**
     * Waits for this future to be completed within the specified time limit.
     *
     * @return {@code true} if and only if the future was completed within the specified time limit
     * @throws InterruptedException if the current thread was interrupted
     */
    boolean await(long timeoutMillis) throws InterruptedException;

    /**
     * Waits for this future to be completed within the specified time limit.
     *
     * @return {@code true} if and only if the future was completed within the specified time limit without interruption.
     * This method catches an {@link InterruptedException} and discards it silently.
     */
    boolean awaitUninterruptibly(long timeoutMillis);

    /**
     * Return the result without blocking. If the future is not done yet this will return {@code null}.
     *
     * As it is possible that a {@code null} value is used to mark the future as successful you also need to check
     * if the future is really done with {@link #isDone()} and not relay on the returned {@code null} value.
     */
    V getNow();

    /**
     * {@inheritDoc}
     *
     * If the cancellation was successful it will fail the future with an {@link java.util.concurrent.CancellationException}.
     *
     */
    @Override
    boolean cancel(boolean mayInterruptIfRunning); // {@inheritDoc} 用在一个@Override的方法上，表示为父类的方法添加详细的注释
}
