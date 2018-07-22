package io.hulk.promise.framework;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author zhaojigang
 * @date 2018/7/19
 */
public class DefaultPromiseTest {

    /**
     * 经典用法之一：listeners回调
     */
    @Test
    public void testListenerNotifyLater() {
        int numListenersBefore = 2; // 设置结果前设置两个listener
        int numListenersAfter = 3; // 设置结果后设置三个listener

        CountDownLatch latch = new CountDownLatch(numListenersBefore + numListenersAfter);
        DefaultPromise<Void> promise = new DefaultPromise<>();

        for (int i = 0; i < numListenersBefore; i++) {
            promise.addListener(new FutureListener<Void>() {
                @Override
                public void operationComplete(Future<Void> future) throws Exception {
                    latch.countDown();
                }
            });
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                promise.setSuccess(null);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < numListenersAfter; i++) {
                            promise.addListener(future -> {
                                latch.countDown();
                            });
                        }
                    }
                }).start();
            }
        }).start();

        try {
            Assert.assertTrue(latch.await(100, TimeUnit.SECONDS), "expect notify " + (numListenersBefore + numListenersAfter) + " listeners");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 经典用法之二：future阻塞get
     */
    @Test
    private void testFutureStyleWithWaitNotifyAll() throws ExecutionException, InterruptedException {
        Promise<Model> promise = new DefaultPromise<>();

        /**
         * 一个线程在执行get()，进行wait()
         */
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Object result = promise.get();// 等待条件
                    // 之后做相应的业务逻辑
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // sleep 2s 使第一个线程先等待着
        Thread.sleep(2000);

        /**
         * 另外一个线程在设置值，notifyAll唤醒wait()线程
         */
        new Thread(new Runnable() {
            @Override
            public void run() {
                promise.setSuccess(new Model(1L));
            }
        }).start();
    }

    @Test
    private void testListenerAddWhenCompleteFailure() throws InterruptedException {
        testListenerAddWhenComplete(new RuntimeException("失败"));
    }

    @Test
    private void testListenerAddWhenCompleteSuccess() throws InterruptedException {
        testListenerAddWhenComplete(null);
    }

    @Test(expectedExceptions = {CancellationException.class})
    public void testCancellationExceptionIsThrownWhenBlockingGet() throws ExecutionException, InterruptedException {
        Promise<Void> promise = new DefaultPromise<>();
        // 设置result为CancellationException
        promise.cancel(false);
        // get时发现是CancellationException，直接抛错
        promise.get();
    }

    @Test(expectedExceptions = {CancellationException.class})
    public void testCancellationExceptionIsThrownWhenBlockingGetWithTimeout() throws ExecutionException, InterruptedException, TimeoutException {
        Promise<Void> promise = new DefaultPromise<>();
        // 设置result为CancellationException
        promise.cancel(false);
        // get时发现是CancellationException，直接抛错
        promise.get(1, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testNotifyListenerFIFOOrder() {
        final Promise<Void> promise = new DefaultPromise<>();
        Queue<FutureListener<Void>> listeners = new ArrayBlockingQueue<>(4);

        final FutureListener<Void> listener1 = new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                listeners.add(this);
                System.out.println("invoke listener1");
            }
        };

        final FutureListener<Void> listener2 = new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                listeners.add(this);
                System.out.println("invoke listener2");
            }
        };

        final FutureListener<Void> listener4 = new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                listeners.add(this);
                System.out.println("invoke listener4");
            }
        };

        final FutureListener<Void> listener3 = new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                listeners.add(this);
                future.addListener(listener4); // 此时的future就是上边的promise属性
                System.out.println("invoke listener3");
            }
        };

        new Thread(new Runnable() {
            @Override
            public void run() {
                promise.setSuccess(null); // 首先设置结果
            }
        }).start();

        /**
         * 由于添加到promise的listener的顺序为listener1/listener2/listener3/在listener3中的回调函数中添加了listener4，
         * 所以执行顺序为：listener1~4
         *
         * 其中listener3的回调中添加listener4的时候，listener4的回调是不会执行的，要等listener3的先执行完（通过boolean notifyingListeners来控制）
         */
        promise.addListener(listener1).addListener(listener2).addListener(listener3);
        Assert.assertSame(listener1, listeners.poll());
        Assert.assertSame(listener2, listeners.poll());
        Assert.assertSame(listener3, listeners.poll());
        Assert.assertSame(listener4, listeners.poll());
    }

    private void testListenerAddWhenComplete(Throwable cause) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        DefaultPromise<Void> promise = new DefaultPromise<>();
        promise.addListener(future -> {
            System.out.println("xxxxx");
            latch.countDown();
        });

        if (cause == null) {
            promise.setSuccess(null);
        } else {
            promise.setFailure(cause);
        }

        latch.await();
    }

    class Model {
        private Long id;
        private String name;

        public Model(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "id" + id;
        }
    }
}
