package com.l7defense.util;

public final class FirewallProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("flood")) {
            byte[] output = new byte[1024 * 1024];
            System.out.write(output);
            System.err.write(output);
        } else if (args[0].equals("sleep")) {
            Thread.sleep(60_000L);
        } else {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), args[2] + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }
}
