package com.miniwsa.generator;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent.GeoLocation;
import com.miniwsa.model.Severity;

import java.util.List;

/**
 * The values generated events are built from, one list per field. {@link EventGenerator} picks every field of an
 * event at random from its own list, independently of the other fields.
 */
final class ReferenceData {

    static final List<Long> CONFIG_IDS = List.of(14227L, 21503L, 30871L, 16527L, 19877L, 14427L, 34227L);

    static final List<String> POLICY_IDS = List.of("pol_web1", "pol_shop", "pol_api", "pol_aab1", "pol_bb1", "pol_cc1");

    /** Public-looking IPv4 addresses; none in private ranges or the 198.x / 203.x documentation ranges the tests use. */
    static final List<String> CLIENT_IPS = List.of(
            "23.45.112.8", "31.13.72.36", "45.83.64.12", "52.14.201.77", "61.177.172.140",
            "77.88.55.60", "85.214.132.117", "91.240.118.172", "103.152.220.44", "109.70.100.23",
            "118.25.6.39", "125.64.94.138", "141.98.11.62", "151.101.1.140", "162.142.125.9",
            "176.111.174.85", "185.220.101.4", "193.32.162.70", "212.83.150.120", "222.186.42.7");

    /** Hostnames under the reserved example.* domains. */
    static final List<String> HOSTNAMES = List.of("www.example.com", "shop.example.org", "api.example.net");

    static final List<String> PATHS = List.of(
            "/", "/index.html", "/products", "/products/1842", "/search?q=running+shoes", "/cart", "/checkout",
            "/account", "/api/v1/products?page=2", "/api/v1/orders/55120", "/api/v1/users/me", "/static/js/app.js",
            "/robots.txt", "/login", "/api/v1/login", "/account/login", "/admin", "/admin/users", "/admin/config");

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE");

    static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            "python-requests/2.32.3",
            "curl/8.10.1",
            "sqlmap/1.8.11#stable (https://sqlmap.org)",
            "Nikto/2.5.0");

    static final List<String> RULE_NAMES = List.of(
            "SQL_INJECTION", "COMMAND_INJECTION", "XSS_SCRIPT_TAG", "INVALID_REQUEST_LINE", "SQL_ERROR_LEAK",
            "CREDENTIAL_STUFFING", "HTTP_FLOOD", "RATE_LIMIT_EXCEEDED");

    static final List<String> RULE_MESSAGES = List.of(
            "SQL Injection Attack Detected",
            "Remote Command Execution: Unix Command Injection",
            "XSS Attack Detected via script tag",
            "Invalid HTTP Request Line",
            "SQL error message leakage in response",
            "Repeated failed logins from one client",
            "Request rate from client exceeds DoS threshold",
            "Client exceeded the configured request rate");

    static final List<Severity> SEVERITIES = List.of(Severity.values());

    static final List<Category> CATEGORIES = List.of(Category.values());

    static final List<Action> ACTIONS = List.of(Action.values());

    static final List<GeoLocation> GEO_LOCATIONS = List.of(
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
            new GeoLocation("NG", "Lagos"),
            new GeoLocation("UA", "Kyiv"));

    private ReferenceData() {
    }
}
