package com.baropick.delivery.common;

import java.security.SecureRandom;

public final class Ids {
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private Ids() {}

    public static String next(String prefix) {
        long t = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(prefix).append('-');
        sb.append(Long.toString(t, 36).toUpperCase());
        for (int i = 0; i < 6; i++) sb.append(ALPHA[RND.nextInt(ALPHA.length)]);
        return sb.toString();
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }
}
