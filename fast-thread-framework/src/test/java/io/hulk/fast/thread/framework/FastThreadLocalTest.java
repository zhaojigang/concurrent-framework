package io.hulk.fast.thread.framework;

import java.util.concurrent.Executor;

import org.testng.annotations.Test;

/**
 * @author zhaojigang
 * @date 2018/7/29
 */
public class FastThreadLocalTest {

    private static final FastThreadLocal<Integer> fastThreadLocal1 = new FastThreadLocal<Integer>(){
        @Override
        protected Integer initialValue() throws Exception {
            return 100;
        }

        @Override
        protected void onRemoved(Integer value) throws Exception {
            System.out.println(value + ":我被删除了");
        }
    };

    private static final FastThreadLocal<String> fastThreadLocal2 = new FastThreadLocal<String>(){
        @Override
        protected String initialValue() throws Exception {
            return "haha";
        }

        @Override
        protected void onRemoved(String value) throws Exception {
            System.out.println(value + ":我被删除了");
        }
    };


    @Test
    public void testSetAndGetByCommonThread() {
        Integer x = fastThreadLocal1.get();
        String s = fastThreadLocal2.get();
        fastThreadLocal1.set(200);
        fastThreadLocal2.set("hehe");
        Integer x1 = fastThreadLocal1.get();
        String s1 = fastThreadLocal2.get();
    }

    @Test
    public void testSetAndGetByFastThreadLocalThread() {
        new FastThreadLocalThread(()->{
            Integer x = fastThreadLocal1.get();
            String s = fastThreadLocal2.get();
            fastThreadLocal1.set(200);
            fastThreadLocal2.set("hehe");
            Integer x1 = fastThreadLocal1.get();
            String s1 = fastThreadLocal2.get();
        }).start();
    }

    private static final Executor executor = FastThreadExecutors.newCachedFastThreadPool("test");

    @Test
    public void testSetAndGetByFastThreadLocalThreadExecutor() {
        executor.execute(()->{
            Integer x = fastThreadLocal1.get();
            String s = fastThreadLocal2.get();
            fastThreadLocal1.set(200);
            fastThreadLocal2.set("hehe");
            Integer x1 = fastThreadLocal1.get();
            String s1 = fastThreadLocal2.get();
        });
    }



    @Test
    public void testRemove() {

    }


}
