package com.yuqiplus.androidsmb;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ClientNetworks {
    private static final Pattern SEPARATOR = Pattern.compile("[,，\\p{javaWhitespace}\\p{Z}\\u0085]+");
    private static final Pattern IPV4 = Pattern.compile(
        "(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})"
            + "(?:/(0|[1-9][0-9]?))?");

    private ClientNetworks() {
    }

    static List<String> split(CharSequence input) {
        return Arrays.stream(SEPARATOR.split(input)).filter(value -> !value.isEmpty()).collect(Collectors.toList());
    }

    static String normalize(String entry) {
        var match = IPV4.matcher(entry);
        if (!match.matches()) {
            throw new IllegalArgumentException("请输入 IPv4 或 CIDR，例如 10.20.30.45 或 10.20.30.0/24。");
        }

        int first = Integer.parseInt(match.group(1));
        int second = Integer.parseInt(match.group(2));
        int third = Integer.parseInt(match.group(3));
        int fourth = Integer.parseInt(match.group(4));
        if (first > 255 || second > 255 || third > 255 || fourth > 255) {
            throw new IllegalArgumentException("IPv4 地址的每一段须为 0–255。");
        }
        int bits = match.group(5) == null ? 32 : Integer.parseInt(match.group(5));
        int minimum = minimumPrefix(first, second);
        if (minimum == 0) {
            throw new IllegalArgumentException("请填写内网 IPv4 地址。");
        }
        if (bits < minimum || bits > 32) {
            throw new IllegalArgumentException("这个地址的前缀长度须在 /" + minimum + " 到 /32 之间。");
        }

        int ip = (first << 24) | (second << 16) | (third << 8) | fourth;
        int network = ip & (-1 << (32 - bits));
        String address = (network >>> 24) + "." + ((network >>> 16) & 255)
            + "." + ((network >>> 8) & 255) + "." + (network & 255);
        return bits == 32 ? address : address + "/" + bits;
    }

    private static int minimumPrefix(int first, int second) {
        if (first == 10) {
            return 8;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return 12;
        }
        if (first == 192 && second == 168) {
            return 16;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return 10;
        }
        return 0;
    }
}
