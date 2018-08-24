package io.hulk.recycler.framework;

import io.hulk.common.utils.MathUtil;
import io.hulk.fast.thread.framework.FastThreadLocal;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * from netty
 * 对象回收池
 * 1、因为具有抽象方法，所以Recycler是抽象类
 * 2、几乎不需要锁
 * 3、线程A创建的对象X只能由线程A进行get，线程B无法get，但是假设线程A将对象X传递给线程B进行使用，线程B使用结束后，可以进行recycle到WeakOrderQueue中；
 * 之后线程A进行get的时候会将对象X转移到自己的Stack中
 *
 * 同步问题：
 * （1）假设线程A进行get，线程B也进行get，无锁（二者各自从自己的stack或者从各自的weakOrderQueue中进行获取）
 * （2）假设线程A进行get对象X，线程B进行recycle对象X，无锁（假设A无法直接从其Stack获取，从WeakOrderQueue进行获取，由于stack.head是volatile的，线程Brecycle的对象X可以被线程A立即获取）
 * （3）假设线程C和线程Brecycle线程A的对象X，此时需要加锁（具体见Stack.setHead()）
 */
public abstract class Recycler<T> {
    /**
     * 唯一ID生成器
     * 用在两处：
     * 1、当前线程ID
     * 2、WeakOrderQueue的id
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * static变量, 生成并获取一个唯一id.
     * 用于pushNow()中的item.recycleId和item.lastRecycleId的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 表示一个不需要回收的包装对象，用于在禁止使用Recycler功能时进行占位的功能
     * 仅当io.netty.recycler.maxCapacityPerThread<=0时用到
     */
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    /**
     * 每个Stack默认的最大容量
     * 注意：
     * 1、当io.netty.recycler.maxCapacityPerThread<=0时，禁用回收功能（在netty中，只有=0可以禁用，<0默认使用4k）
     * 2、Recycler中有且只有两个地方存储DefaultHandle对象（Stack和Link），
     * 最多可存储MAX_CAPACITY_PER_THREAD + 最大可共享容量 = 4k + 4k/2 = 6k
     *
     * 实际上，在netty中，Recycler提供了两种设置属性的方式
     * 第一种：-Dio.netty.recycler.ratio等jvm启动参数方式
     * 第二种：Recycler(int maxCapacityPerThread)构造器传入方式
     */
    private static final int MAX_CAPACITY_PER_THREAD = Math.max(Integer.parseInt(System.getProperty("io.netty.recycler.maxCapacityPerThread", String.valueOf(4 * 1024))), 0);
    /**
     * 每个Stack默认的初始容量，默认为256
     * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
     */
    private static final int INITIAL_CAPACITY = Math.min(256, MAX_CAPACITY_PER_THREAD);
    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR = Math.max(2, Integer.valueOf(System.getProperty("io.netty.recycler.maxSharedCapacityFactor", String.valueOf(2))));
    /**
     * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
     * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
     */
    private static final int MAX_DELAYE_DQUEUES_PERTHREAD = Math.max(0, Integer.parseInt(System.getProperty("io.netty.recycler.maxDelayedQueuesPerThread", String.valueOf(2 * Runtime.getRuntime().availableProcessors()))));
    /**
     * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
     * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
     * 所有的Link加起来的容量要<=最大可共享容量。
     */
    private static final int LINK_CAPACITY = MathUtil.safeFindNextPositivePowerOfTwo(Integer.parseInt(System.getProperty("io.netty.recycler.linkCapacity", String.valueOf(16))));
    /**
     * 回收因子，默认为8。
     * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
     */
    private static final int RATIO = MathUtil.safeFindNextPositivePowerOfTwo(Integer.parseInt(System.getProperty("io.netty.recycler.ratio", String.valueOf(8))));
    /**
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED = new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() throws Exception {
            return new WeakHashMap<>();
        }
    };
    /**
     * 1、每个Recycler对象都有一个threadLocal
     * 原因：因为一个Stack要指明存储的对象泛型T，而不同的Recycler<T>对象的T可能不同，所以此处的FastThreadLocal是对象级别
     * 2、每条线程都有一个Stack<T>对象
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() throws Exception {
            return new Stack<>(Thread.currentThread(), MAX_CAPACITY_PER_THREAD, MAX_DELAYE_DQUEUES_PERTHREAD, MAX_SHARED_CAPACITY_FACTOR, MathUtil.safeFindNextPositivePowerOfTwo(RATIO) - 1);
        }

        @Override
        protected void onRemoved(Stack<T> stack) throws Exception {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (stack.threadRef.get() == Thread.currentThread() && DELAYED_RECYCLED.isSet()) {
                DELAYED_RECYCLED.get().remove(stack);
            }
        }
    };

    /**
     * 创建一个对象
     * 1、由子类进行复写，所以使用protected修饰
     * 2、传入Handle对象，对创建出来的对象进行回收操作
     */
    protected abstract T newObject(Handle<T> handle);

    /**
     * 获取对象
     * 由于get()方法只会由Recycler的子类进行调用，所以此处使用protected修饰，而不是public
     */
    public final T get() {
        /**
         * 0、如果maxCapacityPerThread == 0，禁止回收功能
         * 创建一个对象，其Recycler.Handle<User> handle属性为NOOP_HANDLE，该对象的recycle(Object object)不做任何事情，即不做回收
         */
        if (MAX_CAPACITY_PER_THREAD == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        /**
         * 1、获取当前线程的Stack<T>对象
         */
        Stack<T> stack = threadLocal.get();
        /**
         * 2、从Stack<T>对象中获取DefaultHandle<T>
         */
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            /**
             * 3、 新建一个DefaultHandle对象 -> 然后新建T对象 -> 存储到DefaultHandle对象
             * 此处会发现一个DefaultHandle对象对应一个Object对象，二者相互包含。
             */
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        /**
         * 4、返回value
         */
        return handle.value;
    }

    static final class Stack<T> {
        /**
         * 该Stack所属的线程
         * why WeakReference?
         * 假设该线程对象在外界已经没有强引用了，那么实际上该线程对象就可以被回收了。但是如果此处用的是强引用，那么虽然外界不再对该线程有强引用，
         * 但是该stack对象还持有强引用（假设用户存储了DefaultHandle对象，然后一直不释放，而DefaultHandle对象又持有stack引用），导致该线程对象无法释放。
         *
         * from netty:
         * The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all
         * if the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear it in a timely manner)
         */
        private WeakReference<Thread> threadRef;
        /**
         * Stack底层数据结构，真正的用来存储数据
         */
        private DefaultHandle<T>[] elements;
        /**
         * elements中的元素个数，同时也可作为操作数组的下标
         * 数组只有elements.length来计算数组容量的函数，没有计算当前数组中的元素个数的函数，所以需要我们去记录，不然需要每次都去计算
         */
        private int size;
        /**
         * elements最大的容量：默认最大为4k，4096
         */
        private int maxCapacity;
        /**
         * 可用的共享内存大小，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的Stack是线程A的，则其他线程B~X等去回收线程A创建的对象时，可回收最多A创建的多少个对象
         * 注意：那么实际上线程A创建的对象最终最多可以被回收maxCapacity + availableSharedCapacity个，默认为6k个
         *
         * why AtomicInteger?
         * 当线程B和线程C同时创建线程A的WeakOrderQueue的时候，会同时分配内存，需要同时操作availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        private AtomicInteger availableSharedCapacity;
        /**
         * DELAYED_RECYCLED中最多可存储的{Stack，WeakOrderQueue}键值对个数
         */
        private int maxDelayedQueues;
        /**
         * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉
         */
        private int ratioMask;
        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的handleRecycleCount=handleRecycleCount+1=0
         * 与ratioMask配合，用来决定当前的元素是被回收还是被drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被drop
         */
        private int handleRecycleCount = -1;
        /**
         * 该值是当线程B回收线程A创建的对象时，线程B会为线程A的Stack对象创建一个WeakOrderQueue对象，
         * 该WeakOrderQueue指向这里的head，用于后续线程A对对象的查找操作
         * Q: why volatile?
         * A: 假设线程A正要读取对象X，此时需要从其他线程的WeakOrderQueue中读取，假设此时线程B正好创建Queue，并向Queue中放入一个对象X；假设恰好次Queue就是线程A的Stack的head
         * 使用volatile可以立即读取到该queue。
         *
         * 对于head的设置，具有同步问题。具体见此处的volatile和synchronized void setHead(WeakOrderQueue queue)
         */
        private volatile WeakOrderQueue head;
        /**
         * cursor：当前操作的WeakOrderQueue
         * prev：cursor的前一个WeakOrderQueue
         */
        private WeakOrderQueue cursor, prev;

        public Stack(Thread threadRef,
                     int maxCapacity,
                     int maxDelayedQueues,
                     int maxSharedCapacityFactor,
                     int ratioMask) {
            this.elements = new DefaultHandle[Math.min(maxCapacity, INITIAL_CAPACITY)];
            this.threadRef = new WeakReference<>(threadRef);
            this.maxCapacity = maxCapacity;
            this.maxDelayedQueues = maxDelayedQueues;
            this.availableSharedCapacity = new AtomicInteger(Math.max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            this.ratioMask = ratioMask;
        }

        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                /**
                 * 由于在transfer(Stack<?> dst)的过程中，可能会将其他线程的WeakOrderQueue中的DefaultHandle对象传递到当前的Stack,
                 * 所以size发生了变化，需要重新赋值
                 */
                size = this.size;
            }
            /**
             * 注意：因为一个Recycler<T>只能回收一种类型T的对象，所以element可以直接使用操作size来作为下标来进行获取
             */
            size--;
            DefaultHandle<T> ret = elements[size];
            // 置空
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycledId) {
                throw new IllegalStateException("recycled multiple times");
            }
            this.size = size;
            // 置位
            ret.recycledId = ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            if (scavengeSome()) {
                return true;
            }
            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        private boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                // 如果head==null，表示当前的Stack对象没有WeakOrderQueue，直接返回
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                // 遍历下一个WeakOrderQueue
                WeakOrderQueue next = cursor.next;
                // 做清理操作
                if (cursor.owner.get()==null) {
                    /**
                     * 如果当前的WeakOrderQueue的线程已经不可达了，则
                     * 1、如果该WeakOrderQueue中有数据，则将其中的数据全部转移到当前Stack中
                     * 2、将当前的WeakOrderQueue的前一个节点prev指向当前的WeakOrderQueue的下一个节点，即将当前的WeakOrderQueue从Queue链表中移除。方便后续GC
                     */
                    if (cursor.hasFinalData()){
                       for (;;){
                           if (cursor.transfer(this)) {
                               success =true;
                           } else {
                               break;
                           }
                       }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;
            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<T> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                pushNow(item);
            } else {
                pushLater(item, currentThread);
            }
        }

        /**
         * 立刻将item元素压入Stack中
         */
        private void pushNow(DefaultHandle<T> item) {
            // (item.recycleId | item.lastRecycleId) != 0 等价于 item.recycleId!=0 && item.lastRecycleId!=0
            // 当item开始创建时item.recycleId==0 && item.lastRecycleId==0
            // 当item被recycle时，item.recycleId==x，item.lastRecycleId==y 进行赋值
            // 当item被poll之后， item.recycleId = item.lastRecycleId = 0
            // 所以当item.recycleId 和 item.lastRecycleId 任何一个不为0，则表示回收过
            if ((item.recycledId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycledId = item.lastRecycledId = OWN_THREAD_ID;
            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                return;
            }
            // stack中的elements扩容两倍，复制元素，将新数组赋值给stack.elements
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, Math.min(size << 1, maxCapacity));
            }
            // 放置元素
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 先将item元素加入WeakOrderQueue，后续再从WeakOrderQueue中将元素压入Stack中
         */
        private void pushLater(DefaultHandle<T> item, Thread currentThread) {
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，则后续的无法回收 - 内存保护
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                if ((queue = WeakOrderQueue.allocate(this, currentThread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }
            queue.add(item);
        }

        /**
         * 两个drop的时机
         * 1、pushNow：当前线程将数据push到Stack中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        private boolean dropHandle(DefaultHandle<T> item) {
            if (!item.hasBeenRecycled) {
                // 每8个对象：扔掉7个，回收一个
                // 回收的索引：handleRecycleCount - 0/8/16/24/32/...
                if ((++handleRecycleCount & ratioMask) != 0) {
                    return true;
                }
                // 设置已经被回收了的标志，实际上此处还没有被回收，在pushNow(DefaultHandle<T> item)接下来的逻辑就会进行回收
                // 对于pushNow(DefaultHandle<T> item)：该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，重复回收的操作由item.recycleId | item.lastRecycledId来阻止
                item.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<>(this);
        }

        /**
         * 假设线程B和线程C同时回收线程A的对象时，有可能会同时newQueue，就可能同时setHead，所以这里需要加锁
         * 以head==null的时候为例，
         * 加锁：
         * 线程B先执行，则head = 线程B的queue；之后线程C执行，此时将当前的head也就是线程B的queue作为线程C的queue的next，组成链表，之后设置head为线程C的queue
         * 不加锁：
         * 线程B先执行queue.setNext(head);此时线程B的queue.next=null->线程C执行queue.setNext(head);线程C的queue.next=null
         * -> 线程B执行head = queue;设置head为线程B的queue -> 线程C执行head = queue;设置head为线程C的queue
         *
         * 注意：此时线程B和线程C的queue没有连起来，则之后的poll()就不会从B进行查询。（B就是资源泄露）
         */
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            // 获取旧数组长度
            int newCapacity = elements.length;
            // 获取最大长度
            int maxCapacity = this.maxCapacity;
            // 不断扩容（每次扩容2倍），直到达到expectedCapacity或者新容量已经大于等于maxCapacity
            do {
                // 扩容2倍
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);
            // 上述的扩容有可能使新容量newCapacity>maxCapacity，这里取最小值
            newCapacity = Math.min(newCapacity, maxCapacity);
            // 如果新旧容量不相等，进行实际扩容
            if (newCapacity != elements.length) {
                // 创建新数组，复制旧数组元素到新数组，并将新数组赋值给Stack.elements
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }
    }

    /**
     * 提供对象的回收功能，由子类进行复写
     * 目前该接口只有两个实现：NOOP_HANDLE和DefaultHandle
     */
    public interface Handle<T> {
        void recycle(T object);
    }


    static final class DefaultHandle<T> implements Handle<T> {
        /**
         * 真正的对象，value与Handle一一对应
         */
        private T value;
        /**
         * 标记是否已经被回收：
         * 该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
         * 重复回收的操作由item.recycleId | item.lastRecycledId来阻止
         */
        private boolean hasBeenRecycled;
        /**
         * 只有在pushNow()中会设置值OWN_THREAD_ID
         * 在poll()中置位0
         */
        private int recycledId;
        /**
         * pushNow() = OWN_THREAD_ID
         * 在pushLater中的add(DefaultHandle handle)操作中 == id（当前的WeakOrderQueue的唯一ID）
         * 在poll()中置位0
         */
        private int lastRecycledId;
        /**
         * 当前的DefaultHandle对象所属的Stack
         */
        private Stack<T> stack;

        DefaultHandle(Stack<T> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(T object) {
            // 防护性判断
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            if (this.lastRecycledId != this.recycledId) {
                throw new IllegalStateException("recycled multiple times");
            }
            /**
             * 回收对象，this指的是当前的DefaultHandle对象
             */
            stack.push(this);
        }
    }


    private static class WeakOrderQueue {
        /**
         * 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，
         * 对于后续的Stack，其对应的WeakOrderQueue设置为DUMMY，
         * 后续如果检测到DELAYED_RECYCLED中对应的Stack的value是WeakOrderQueue.DUMMY时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();
        /**
         * WeakOrderQueue的唯一标记
         */
        private final int id = ID_GENERATOR.incrementAndGet();
        private final Head head;
        private Link tail;
        /**
         * pointer to another queue of delayed items for the same stack
         */
        private WeakOrderQueue next;
        /**
         * 1、why WeakReference？与Stack相同。
         * 2、作用是在poll的时候，如果owner不存在了，则需要将该线程所包含的WeakOrderQueue的元素释放，然后从链表中删除该Queue。
         */
        private final WeakReference<Thread> owner;

        private <T> WeakOrderQueue(Stack<T> stack, Thread currentThread) {
            // 创建有效Link节点，恰好是尾节点
            tail = new Link();
            // 创建Link链表头节点，只是占位符
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            owner = new WeakReference<>(currentThread);
        }

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        private static <T> WeakOrderQueue allocate(Stack<T> stack, Thread currentThread) {
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY) ? newQueue(stack, currentThread) : null;
        }

        private static <T> WeakOrderQueue newQueue(Stack<T> stack, Thread currentThread) {
            // 创建WeakOrderQueue
            WeakOrderQueue queue = new WeakOrderQueue(stack, currentThread);
            // 将该queue赋值给stack的head属性
            stack.setHead(queue);

            /**
             * 将新建的queue添加到Cleaner中，当queue不可达时，
             * 调用head中的run()方法回收内存availableSharedCapacity，否则该值将不会增加，影响后续的Link的创建
             */
//            final Head head = queue.head;
//            ObjectCleaner.register(queue, head);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        private <T> void add(DefaultHandle<T> item) {
            item.lastRecycledId = id;
            Link tail = this.tail;
            int writeIndex;
            // 判断一个Link对象是否已经满了：
            // 如果没满，直接添加；
            // 如果已经满了，创建一个新的Link对象，之后重组Link链表，然后添加元素的末尾的Link（除了这个Link，前边的Link全部已经满了）
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // drop item
                    return;
                }
                /**
                 * 此处创建一个Link，会将该Link作为新的tail-Link，之前的tail-Link已经满了，成为正常的Link了。重组Link链表
                 * 之前是HEAD -> tail-Link，重组后HEAD -> 之前的tail-Link -> 新的tail-Link
                 */
                tail = this.tail = tail.next = new Link();
                writeIndex = tail.get(); // 0
            }
            tail.elements[writeIndex] = item;
            /**
             * 如果使用者在将DefaultHandle对象压入队列后，
             * 将Stack设置为null，但是此处的DefaultHandle是持有stack的强引用的，则Stack对象无法回收；
             * 而且由于此处DefaultHandle是持有stack的强引用，WeakHashMap中对应stack的WeakOrderQueue也无法被回收掉了，导致内存泄漏。
             */
            item.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // tail本身继承于AtomicInteger，所以此处直接对tail进行+1操作
            tail.lazySet(writeIndex + 1);
//            tail.set(writeIndex + 1);
        }

        public <T> boolean transfer(Stack<T> dst) {
            // 寻找第一个Link（Head不是Link）
            Link head = this.head.link;
            // head == null，表示只有Head一个节点，没有存储数据的节点，直接返回
            if (head == null) {
                return false;
            }
            // 如果第一个Link节点的readIndex索引已经到达该Link对象的DefaultHandle[]的尾部，
            // 则判断当前的Link节点的下一个节点是否为null，如果为null，说明已经达到了Link链表尾部，直接返回，
            // 否则，将当前的Link节点的下一个Link节点赋值给head和this.head.link，进而对下一个Link节点进行操作
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }
            // 获取Link节点的readIndex,即当前的Link节点的第一个有效元素的位置
            int srcStart = head.readIndex;
            // 获取Link节点的writeIndex，即当前的Link节点的最后一个有效元素的位置
            int srcEnd = head.get();
            // 计算Link节点中可以被转移的元素个数
            int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
            // 获取转移元素的目的地Stack中当前的元素个数
            final int dstSize = dst.size;
            // 计算期盼的容量
            final int expectedCapacity = dstSize + srcSize;
            /**
             * 如果expectedCapacity大于目的地Stack的长度
             * 1、对目的地Stack进行扩容
             * 2、计算Link中最终的可转移的最后一个元素的下标
             */
            if (expectedCapacity > dst.elements.length) {
                int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = Math.min(srcEnd, actualCapacity - dstSize + srcStart);
            }

            if (srcStart == srcEnd) {
                // The destination stack is full already.
                return false;
            } else {
                // 获取Link节点的DefaultHandle[]
                final DefaultHandle[] srcElems = head.elements;
                // 获取目的地Stack的DefaultHandle[]
                final DefaultHandle[] dstElems = dst.elements;
                // dst数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    final DefaultHandle element = srcElems[i];
                    /**
                     * 设置element.recycleId 或者 进行防护性判断
                     */
                    if (element.recycledId == 0) {
                        element.recycledId = element.lastRecycledId;
                    } else if (element.recycledId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    // 置空Link节点的DefaultHandle[i]
                    srcElems[i] = null;
                    // 扔掉放弃7/8的元素
                    if (dst.dropHandle(element)) {
                        continue;
                    }
                    // 将可转移成功的DefaultHandle元素的stack属性设置为目的地Stack
                    element.stack = dst;
                    // 将DefaultHandle元素转移到目的地Stack的DefaultHandle[newDstSize ++]中
                    dstElems[newDstSize++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    this.head.reclaimSpace(LINK_CAPACITY);
                    // 将Head指向下一个Link，也就是将当前的Link给回收掉了
                    // 假设之前为Head -> Link1 -> Link2，回收之后为Head -> Link2
                    this.head.link = head.next;
                }
                // 重置readIndex
                head.readIndex = srcEnd;
                // 表示没有被回收任何一个对象，直接返回
                if (dst.size == newDstSize) {
                    return false;
                }
                // 将新的newDstSize赋值给目的地Stack的size
                dst.size = newDstSize;
                return true;
            }
        }

        private boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // Head仅仅作为head-Link的占位符，仅用于ObjectCleaner回收操作
        static final class Head {
            private final AtomicInteger availableSharedCapacity;
            /**
             * 指定读操作的Link节点，
             * eg. Head -> Link1 -> Link2
             * 假设此时的读操作在Link2上进行时，则此处的link == Link2，见transfer(Stack dst),
             * 实际上此时Link1已经被读完了，Link1变成了垃圾（一旦一个Link的读指针指向了最后，则该Link不会被重复利用，而是被GC掉，
             * 之后回收空间，新建Link再进行操作）
             */
            private Link link;

            private Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * 这里可以理解为一次内存的批量分配，每次从availableSharedCapacity中分配space个大小的内存。
             * 如果一个Link中不是放置一个DefaultHandle[]，而是只放置一个DefaultHandle，那么此处的space==1，这样的话就需要频繁的进行内存分配
             */
            private boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space > 0;
                // cas + 无限重试进行 分配
                for (; ; ) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    // 注意：这里使用的是AtomicInteger，当这里的availableSharedCapacity发生变化时，实际上就是改变的stack.availableSharedCapacity的int value属性的值
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }

            private void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * 在该对象被真正的回收前，执行该方法
             * 循环释放当前的WeakOrderQueue所占用的所有空间。
             * 将新建的queue添加到Cleaner中，当queue不可达时，
             * 调用head中的run()方法回收内存availableSharedCapacity，否则该值将不会增加，影响后续的Link的创建
             */
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    // Unlink to help GC
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }
        }

        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];
            /**
             * Link的下一个节点
             */
            private Link next;
            public int readIndex;
        }
    }
}
