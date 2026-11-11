/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.utils;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/** Classifies omnibox input without relying on a fixed list of TLDs. */
public final class SmartAddress {
    private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");
    private SmartAddress() {}

    public static boolean isAddress(String raw) {
        if (raw == null) return false;
        String input = raw.trim();
        if (input.isEmpty() || containsWhitespace(input)) return false;

        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://") ||
                lower.startsWith("file://") || lower.startsWith("ftp://") ||
                lower.startsWith("about:") || lower.startsWith("data:")) return true;
        if (input.startsWith("//")) return isHostAddress(input.substring(2));
        // Unknown explicit protocols should not be treated as web domains.
        if (input.contains("://")) return false;
        return isHostAddress(input);
    }

    public static String normalize(String raw) {
        String input = raw.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://") ||
                lower.startsWith("file://") || lower.startsWith("ftp://") ||
                lower.startsWith("about:") || lower.startsWith("data:")) {
            return input;
        }
        if (input.startsWith("//")) return "https:" + input;
        // Local development hosts rarely serve HTTPS. An explicitly typed http://
        // continues to work for other sites, but public domains default to HTTPS.
        String host = hostPart(input);
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".local") ||
                isIpv4(host) || (host.startsWith("[") && host.endsWith("]"))) {
            return "http://" + input;
        }
        return "https://" + input;
    }

    private static boolean isHostAddress(String input) {
        String host = hostPart(input);
        if (host.isEmpty()) return false;
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".local")) return true;
        if (host.startsWith("[") && host.endsWith("]") && host.contains(":")) return true;
        if (isIpv4(host)) return true;
        try {
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            if (ascii.length() > 253 || !ascii.contains(".")) return false;
            String[] labels = ascii.split("\\.", -1);
            if (labels.length < 2) return false;
            for (String label : labels) {
                if (label.isEmpty() || label.length() > 63 ||
                        !LABEL.matcher(label).matches()) return false;
            }
            String suffix = labels[labels.length - 1];
            return suffix.length() >= 2 && !suffix.matches("[0-9]+");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isIpv4(String host) {
        if (!IPV4.matcher(host).matches()) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    private static String hostPart(String input) {
        String hostPort = input.split("[/?#]", 2)[0];
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) return ""; // Never infer a URL from embedded credentials.
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0) return "";
            if (end == hostPort.length() - 1) return hostPort;
            String port = hostPort.substring(end + 1);
            return validPort(port) ? hostPort.substring(0, end + 1) : "";
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            String port = hostPort.substring(colon);
            return validPort(port) ? hostPort.substring(0, colon) : "";
        }
        return hostPort;
    }

    private static boolean validPort(String value) {
        if (value.length() < 2 || value.length() > 6 || value.charAt(0) != ':') return false;
        try {
            int port = Integer.parseInt(value.substring(1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return true;
        }
        return false;
    }
}
