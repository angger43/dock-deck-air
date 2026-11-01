package com.gathin.tvhome;

/** Pure state mapping kept separate so DNS status can be verified with plain JVM tests. */
final class DnsProtectionState {
    enum Kind { OFF, PROTECTED, CONNECTION_ERROR, OFFLINE, OTHER_PROVIDER }

    static final class Result {
        final Kind kind;
        final boolean adGuardSelected;
        final String title;
        final String detail;

        Result(Kind kind, boolean adGuardSelected, String title, String detail) {
            this.kind = kind;
            this.adGuardSelected = adGuardSelected;
            this.title = title;
            this.detail = detail;
        }
    }

    private DnsProtectionState() {}

    static Result evaluate(String mode, String specifier, boolean networkConnected,
            boolean privateDnsActive, String activeServer) {
        boolean hostnameMode = "hostname".equals(mode);
        boolean adGuard = hostnameMode && AdBlockingManager.HOST.equalsIgnoreCase(clean(specifier));
        if (adGuard) {
            if (!networkConnected) {
                return new Result(Kind.OFFLINE, true, "Waiting for network",
                        "AdGuard DNS is selected and will reconnect automatically.");
            }
            boolean matchingServer = activeServer == null || activeServer.isEmpty()
                    || AdBlockingManager.HOST.equalsIgnoreCase(clean(activeServer));
            if (privateDnsActive && matchingServer) {
                return new Result(Kind.PROTECTED, true, "Protection active",
                        "Encrypted DNS is connected through AdGuard DNS.");
            }
            return new Result(Kind.CONNECTION_ERROR, true, "Connection error",
                    "AdGuard DNS could not be validated. Check the network and try again.");
        }
        if (hostnameMode && !clean(specifier).isEmpty()) {
            return new Result(Kind.OTHER_PROVIDER, false, "Another Private DNS is active",
                    clean(specifier));
        }
        return new Result(Kind.OFF, false, "Protection off",
                "The TV is using its original DNS configuration.");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
