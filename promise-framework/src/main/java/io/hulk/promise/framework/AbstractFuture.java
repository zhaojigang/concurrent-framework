package io.hulk.promise.framework;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author zhaojigang
 * @date 2018/7/18
 */
public abstract class AbstractFuture<V> implements Future<V>{
    @Override
    public V get() throws InterruptedException, ExecutionException {
        /**
         * 阻塞等到await()调用完成，即失败或返回结果
         */
        await();
        /**
         * 获取失败异常信息
         */
        Throwable cause = cause();
        /**
         * 如果异常信息为null，直接获取响应结果
         */
        if (cause == null) {
            return getNow();
        }
        /**
         * 如果返回结果result == CancellationException（即执行了cancel()），则抛出该异常
         * 否则，抛出ExecutionException
         */
        if (cause instanceof CancellationException) {
            throw (CancellationException)cause;
        }
        throw new ExecutionException(cause);
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if(await(timeout, unit)) {
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }
            if (cause instanceof CancellationException) {
                throw (CancellationException)cause;
            }
            throw new ExecutionException(cause);
        }
        /**
         * 如果没有在指定的时间内await没有完成，抛出超时异常
         */
        throw new TimeoutException();

    }
}
