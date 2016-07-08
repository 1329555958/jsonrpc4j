package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

public class Util {

    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String DEFAULT_HOSTNAME = "0.0.0.0";

    static boolean hasNonNullObjectData(final ObjectNode node, final String key) {
        return hasNonNullData(node, key) && node.get(key).isObject();
    }

    static boolean hasNonNullData(final ObjectNode node, final String key) {
        return node.has(key) && !node.get(key).isNull();
    }

    static boolean hasNonNullTextualData(final ObjectNode node, final String key) {
        return hasNonNullData(node, key) && node.get(key).isTextual();
    }

    /**
     * 将完整的类路径转换成路径 即.变成/
     *
     * @param className 完整名称
     * @return 转换后的路径
     */
    public static String className2Path(String className) {
        return className.replaceAll("\\.", "/");
    }

    public static void main(String[] args) {
        System.out.println(className2Path(Util.class.getName()));
    }
}
