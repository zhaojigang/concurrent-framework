package io.hulk.common.utils;

/**
 * @author zhaojigang
 * @date 2018/7/17
 */
public class ObjectUtil {

    public static <T> T checkNotNull(T arg, String text){
        if (arg == null) {
            throw new NullPointerException(text);
        }
        return arg;
    }

}
