package com.miniwsa.generator;

import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent.GeoLocation;
import com.miniwsa.model.SecurityEvent.Rule;
import com.miniwsa.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The realistic values generated events are built from: customers, paths, WAF rules, user agents, locations and
 * client IPs. Every list has a fixed order, so the same random numbers always pick the same values.
 */
final class ReferenceData {

    /** A client on the internet; it keeps the same location in every event it sends. */
    record Client(String clientIp, GeoLocation geoLocation) {
    }

    // The customers: the same index in these three lists is one customer, so a configId always comes with its
    // own policyId and hostname. Hostnames use the reserved example.* domains.
    static final List<Long> CONFIG_IDS = List.of(14227L, 21503L, 30871L);
    static final List<String> POLICY_IDS = List.of("pol_web1", "pol_shop", "pol_api");
    static final List<String> HOSTNAMES = List.of("www.example.com", "shop.example.org", "api.example.net");

    /** Ordinary pages and API calls. */
    static final List<String> NORMAL_PATHS = List.of(
            "/", "/index.html", "/products", "/products/1842", "/products/77?color=blue",
            "/search?q=running+shoes", "/cart", "/checkout", "/account", "/api/v1/products?page=2",
            "/api/v1/orders/55120", "/api/v1/users/me", "/static/js/app.js", "/images/logo.png",
            "/blog/2026/11/black-friday", "/help/shipping", "/robots.txt", "/favicon.ico");

    /** Paths the threat score treats as sensitive: they contain {@code /login} or {@code /admin}. */
    static final List<String> SENSITIVE_PATHS = List.of(
            "/login", "/api/v1/login", "/account/login", "/admin", "/admin/users", "/admin/config");

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE");

    /** Two WAF rules per category, with different severities. Ids follow the OWASP CRS numbering style. */
    static final List<Rule> RULES = List.of(
            new Rule("942100", "SQL_INJECTION", "SQL Injection Attack Detected", Severity.CRITICAL, Category.INJECTION),
            new Rule("932100", "COMMAND_INJECTION", "Remote Command Execution: Unix Command Injection", Severity.HIGH, Category.INJECTION),
            new Rule("941100", "XSS_SCRIPT_TAG", "XSS Attack Detected via script tag", Severity.HIGH, Category.XSS),
            new Rule("941160", "XSS_HTML_INJECTION", "HTML Injection in request parameter", Severity.MEDIUM, Category.XSS),
            new Rule("920100", "INVALID_REQUEST_LINE", "Invalid HTTP Request Line", Severity.MEDIUM, Category.PROTOCOL_VIOLATION),
            new Rule("920350", "HOST_IS_IP", "Host header is a numeric IP address", Severity.LOW, Category.PROTOCOL_VIOLATION),
            new Rule("951220", "SQL_ERROR_LEAK", "SQL error message leakage in response", Severity.HIGH, Category.DATA_LEAKAGE),
            new Rule("950100", "SERVER_ERROR_LEAK", "Server error page with stack trace in response", Severity.MEDIUM, Category.DATA_LEAKAGE),
            new Rule("913110", "CREDENTIAL_STUFFING", "Repeated failed logins from one client", Severity.MEDIUM, Category.BOT),
            new Rule("913100", "SCANNER_DETECTED", "User-Agent associated with a security scanner", Severity.LOW, Category.BOT),
            new Rule("912100", "HTTP_FLOOD", "Request rate from client exceeds DoS threshold", Severity.HIGH, Category.DOS),
            new Rule("912120", "SLOW_REQUEST", "Slow request body detected", Severity.MEDIUM, Category.DOS),
            new Rule("971110", "LOGIN_RATE_LIMIT", "Too many login attempts per minute", Severity.MEDIUM, Category.RATE_LIMIT),
            new Rule("971100", "RATE_LIMIT_EXCEEDED", "Client exceeded the configured request rate", Severity.LOW, Category.RATE_LIMIT));

    static final List<String> BROWSER_USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");

    /** Scripts and scanners, typical of attack traffic. */
    static final List<String> TOOL_USER_AGENTS = List.of(
            "python-requests/2.32.3",
            "curl/8.10.1",
            "Go-http-client/1.1",
            "sqlmap/1.8.11#stable (https://sqlmap.org)",
            "Nikto/2.5.0",
            "Mozilla/5.0 zgrab/0.x");

    static final List<GeoLocation> LOCATIONS = List.of(
            new GeoLocation("US", "New York"),
            new GeoLocation("US", "San Jose"),
            new GeoLocation("GB", "London"),
            new GeoLocation("DE", "Frankfurt"),
            new GeoLocation("FR", "Paris"),
            new GeoLocation("NL", "Amsterdam"),
            new GeoLocation("IL", "Tel Aviv"),
            new GeoLocation("IN", "Mumbai"),
            new GeoLocation("JP", "Tokyo"),
            new GeoLocation("BR", "Sao Paulo"),
            new GeoLocation("CN", "Beijing"),
            new GeoLocation("RU", "Moscow"),
            new GeoLocation("VN", "Hanoi"),
            new GeoLocation("NG", "Lagos"),
            new GeoLocation("UA", "Kyiv"));

    /** First octets {@link #randomPublicIp} never uses. */
    private static final Set<Integer> EXCLUDED_FIRST_OCTETS = Set.of(10, 100, 127, 169, 172, 192, 198, 203);

    private ReferenceData() {
    }

    static List<Rule> rulesFor(Category category) {
        return RULES.stream().filter(r -> r.category() == category).toList();
    }

    /**
     * {@code numOfClients} new clients with different IPs, none of them in {@code taken}; their IPs are added to
     * {@code taken}, so later calls never hand out the same IP again. This keeps attacker IPs and background IPs
     * apart.
     */
    static List<Client> newClients(Random rng, int numOfClients, Set<String> taken) {
        List<Client> clients = new ArrayList<>(numOfClients);
        while (clients.size() < numOfClients) {
            String ip = randomPublicIp(rng);
            if (taken.add(ip)) {
                clients.add(new Client(ip, LOCATIONS.get(rng.nextInt(LOCATIONS.size()))));
            }
        }
        return clients;
    }

    /**
     * A random IPv4 address that looks like a client on the internet. First octets left out: 0, 10, 127, 224 and
     * up (special, private, loopback, multicast), 100, 169, 172, 192 (they hold the shared, link-local and other
     * private ranges), and 198, 203 (documentation ranges, used by the tests).
     */
    private static String randomPublicIp(Random rng) {
        int a;
        do {
            a = 1 + rng.nextInt(223);
        } while (EXCLUDED_FIRST_OCTETS.contains(a));
        return a + "." + rng.nextInt(256) + "." + rng.nextInt(256) + "." + (1 + rng.nextInt(254));
    }
}
