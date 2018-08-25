package io.hulk.recycler.framework;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author zhaojigang
 * @date 2018/8/17
 */
public class RecyclerTest {
    private static final Recycler<User> userRecycler = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    @Test
    public void sameThreadGetAndRecycle() {
        // 1、从回收池获取对象
        User user1 = userRecycler.get();
        // 2、设置对象并使用
        user1.setName("hello,java");
        System.out.println(user1);
        // 3、对象恢复出厂设置
        user1.setName(null);
        // 4、回收对象到对象池
        user1.recycle();
        // 5、从回收池获取对象
        User user2 = userRecycler.get();
        Assert.assertSame(user1, user2);
    }

    @Test
    public void differentThreadGetAndRecycle() throws InterruptedException {
        // 1、从回收池获取对象
        User user1 = userRecycler.get();
        // 2、设置对象并使用
        user1.setName("hello,java");

        Thread thread = new Thread(()->{
            System.out.println(user1);
            // 3、对象恢复出厂设置
            user1.setName(null);
            // 4、回收对象到对象池
            user1.recycle();
        });

        thread.start();
        thread.join();

        // 5、从回收池获取对象
        User user2 = userRecycler.get();
        Assert.assertSame(user1, user2);
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testMultipleRecycle() {
        final User user = userRecycler.get();
        user.recycle();
        user.recycle();
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testMultipleRecycleAtDifferentThread() throws InterruptedException {
        final User object = userRecycler.get();
        final AtomicReference<IllegalStateException> exceptionStore = new AtomicReference<>();
        final Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                object.recycle();
            }
        });
        thread1.start();
        thread1.join();

        final Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    object.recycle();
                } catch (IllegalStateException e) {
                    exceptionStore.set(e);
                }
            }
        });
        thread2.start();
        thread2.join();
        IllegalStateException exception = exceptionStore.get();
        if (exception != null) {
            throw exception;
        }
    }

    @Test
    public void testRecycleAtDifferentThread() throws Exception {
        final User o = userRecycler.get(); // o
        final User o2 = userRecycler.get(); // o
        final Thread thread = new Thread() {
            @Override
            public void run() {
                o.recycle();//回收
                o2.recycle();//回收
            }
        };
        thread.start();
        thread.join();

        Assert.assertSame (userRecycler.get(), o);//从WeakOrderQueue中转移数据到Stack，并且扔掉7/8的数据，即扔掉o2
        Assert.assertNotSame(userRecycler.get(), o2);
    }

    @Data
    static final class User {
        private String name;
        private Recycler.Handle<User> handle;

        public User(Recycler.Handle<User> handle) {
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
    }
}