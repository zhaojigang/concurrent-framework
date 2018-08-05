package io.hulk.fast.thread.framework;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.BitSet;

/**
 * BitSet测试
 * @author zhaojigang
 * @date 2018/8/1
 */
public class BitSetTest {
    @Test
    public void testCommonOperation() {
        BitSet bitSet = new BitSet();
        bitSet.set(1);
        Assert.assertTrue(bitSet.get(1));
        Assert.assertTrue(!bitSet.get(2));
        bitSet.set(64);
        Assert.assertTrue(bitSet.get(64));
    }

    @Test
    public void testResize(){
        int newCapacity = 8;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++; //1-2 2-4 3-4 4-8 5-8 6-8 7-8 8-16
        System.out.println(newCapacity);
    }
}