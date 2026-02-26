package org.eclipse.theia.cloud.operator.ingress;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.util.K8sUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * Centralized manager for HTTPRoute operations (Gateway API).
 *
 * Caller intent:
 * - find the route for an app definition
 * - expose a session path via redirect + backend route rule
 * - unexpose a session path during cleanup
 */
@Singleton
public class IngressManager {

    private static final Logger LOGGER = LogManager.getLogger(IngressManager.class);
    /**
     * Envoy Gateway runtime expression for the current request path.
     * This value is interpreted by Envoy; other Gateway API implementations may
     * treat it as a plain literal string.
     */
    private static final String ENVOY_REQUEST_PATH_EXPRESSION = "%REQ(:PATH)%";
    private static final int HTTP_CONFLICT = 409;
    private static final int ROUTE_EDIT_MAX_RETRIES = 5;
    private static final long ROUTE_EDIT_RETRY_BACKOFF_MS = 200L;
    private static final long ROUTE_EDIT_RETRY_JITTER_MS = 100L;

    @Inject
    private TheiaCloudClient client;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    @Inject
    private IngressPathProvider pathProvider;

    /**
     * Gets the HTTPRoute for an app definition.
     */
    public Optional<GenericKubernetesResource> getIngress(AppDefinition appDefinition, String correlationId) {
        Optional<GenericKubernetesResource> route = K8sUtil.getExistingHttpRoute(
                client.kubernetes(),
                client.namespace(),
                appDefinition.getMetadata().getName(),
                appDefinition.getMetadata().getUid());
        if (route.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "No HTTPRoute found for app definition " + appDefinition.getMetadata().getName()));
        }
        return route;
    }

    /**
     * Exposes an eager session by adding/updating path rules in the shared HTTPRoute.
     *
     * @return the full URL for the session
     */
    public String addRuleForSession(
            GenericKubernetesResource route,
            Service service,
            AppDefinition appDefinition,
            int instance,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, instance);
        return upsertRulesForPath(route, service, appDefinition, path, correlationId);
    }

    /**
     * Exposes a lazy session by adding/updating path rules in the shared HTTPRoute.
     *
     * @return the full URL for the session
     */
    public String addRuleForSession(
            GenericKubernetesResource route,
            Service service,
            AppDefinition appDefinition,
            Session session,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, session);
        return upsertRulesForPath(route, service, appDefinition, path, correlationId);
    }

    /**
     * Removes rules for an eager session path from the shared HTTPRoute.
     */
    public void removeRulesForSession(
            GenericKubernetesResource route,
            AppDefinition appDefinition,
            int instance,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, instance);
        removeRulesForPath(route, path, correlationId);
    }

    /**
     * Removes rules for a lazy session path from the shared HTTPRoute.
     */
    public void removeRulesForSession(
            GenericKubernetesResource route,
            AppDefinition appDefinition,
            Session session,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, session);
        removeRulesForPath(route, path, correlationId);
    }

    private String upsertRulesForPath(
            GenericKubernetesResource route,
            Service service,
            AppDefinition appDefinition,
            String path,
            String correlationId) {

        String routeName = route.getMetadata().getName();
        String serviceName = service.getMetadata().getName();
        int port = appDefinition.getSpec().getPort();
        List<String> hosts = buildRouteHosts(appDefinition);

        try {
            editRouteWithRetry(routeName, routeToUpdate -> {
                Map<String, Object> specMap = getSpec(routeToUpdate);
                ensureHostnamesPresent(specMap, hosts);

                List<Map<String, Object>> rules = getRuleList(specMap);
                removeRulesMatchingPath(rules, path);

                rules.add(createRedirectRule(path));
                rules.add(createRouteRule(serviceName, port, path));
            }, correlationId);

            LOGGER.info(formatLogMessage(correlationId,
                    "Configured HTTPRoute " + routeName + " for path " + path
                            + " and backend service " + serviceName));
            return arguments.getInstancesHost() + ensureTrailingSlash(path);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to configure HTTPRoute " + routeName + " for path " + path), e);
            throw e;
        }
    }

    private void removeRulesForPath(GenericKubernetesResource route, String path, String correlationId) {
        String routeName = route.getMetadata().getName();

        try {
            int[] removedRuleCount = new int[] { 0 };
            editRouteWithRetry(routeName, routeToUpdate -> {
                Map<String, Object> specMap = getSpec(routeToUpdate);
                List<Map<String, Object>> rules = getRuleList(specMap);
                removedRuleCount[0] = removeRulesMatchingPath(rules, path);
                // hostnames are route-wide and shared across multiple session paths;
                // removing rules for one path must not delete shared hostnames.
            }, correlationId);

            if (removedRuleCount[0] > 0) {
                LOGGER.info(formatLogMessage(correlationId,
                        "Removed " + removedRuleCount[0] + " HTTPRoute rule(s) for path " + path + " from "
                                + routeName));
            } else {
                LOGGER.debug(formatLogMessage(correlationId,
                        "No HTTPRoute rules found for path " + path + " in " + routeName));
            }
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to remove HTTPRoute rules for path " + path + " from " + routeName), e);
            throw e;
        }
    }

    private List<String> buildRouteHosts(AppDefinition appDefinition) {
        String instancesHost = arguments.getInstancesHost();
        List<String> hosts = new ArrayList<>();
        hosts.add(instancesHost);

        List<String> prefixes = appDefinition.getSpec().getIngressHostnamePrefixes();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                hosts.add(prefix + instancesHost);
            }
        }

        return hosts;
    }

    private void ensureHostnamesPresent(Map<String, Object> spec, List<String> hosts) {
        List<String> hostnames = getStringList(spec, "hostnames");
        for (String host : hosts) {
            if (!hostnames.contains(host)) {
                hostnames.add(host);
            }
        }
    }

    private int removeRulesMatchingPath(List<Map<String, Object>> rules, String path) {
        int initialSize = rules.size();
        rules.removeIf(rule -> hasPathMatch(rule, path));
        return initialSize - rules.size();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSpec(GenericKubernetesResource route) {
        Map<String, Object> additional = route.getAdditionalProperties();
        if (additional == null) {
            additional = new HashMap<>();
            route.setAdditionalProperties(additional);
        }
        return (Map<String, Object>) additional.computeIfAbsent("spec", key -> new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> spec, String key) {
        Object existing = spec.get(key);
        if (existing instanceof List) {
            return (List<String>) existing;
        }
        List<String> list = new ArrayList<>();
        spec.put(key, list);
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRuleList(Map<String, Object> spec) {
        Object existing = spec.get("rules");
        if (existing instanceof List) {
            return (List<Map<String, Object>>) existing;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        spec.put("rules", list);
        return list;
    }

    /**
     * Creates the backend routing rule for a session path.
     *
     * The X-Forwarded-Uri header uses an Envoy Gateway runtime expression and
     * therefore requires Envoy Gateway for correct behavior.
     */
    private Map<String, Object> createRouteRule(String serviceName, int port, String path) {
        Map<String, Object> rule = new HashMap<>();

        Map<String, Object> match = new HashMap<>();
        Map<String, Object> matchPath = new HashMap<>();
        matchPath.put("type", "PathPrefix");
        matchPath.put("value", ensureTrailingSlash(path));
        match.put("path", matchPath);

        List<Map<String, Object>> matches = new ArrayList<>();
        matches.add(match);
        rule.put("matches", matches);

        Map<String, Object> forwardedUriHeader = new HashMap<>();
        forwardedUriHeader.put("name", "X-Forwarded-Uri");
        forwardedUriHeader.put("value", ENVOY_REQUEST_PATH_EXPRESSION);
        List<Map<String, Object>> setHeaders = new ArrayList<>();
        setHeaders.add(forwardedUriHeader);

        Map<String, Object> requestHeaderModifier = new HashMap<>();
        requestHeaderModifier.put("set", setHeaders);

        Map<String, Object> requestHeaderFilter = new HashMap<>();
        requestHeaderFilter.put("type", "RequestHeaderModifier");
        requestHeaderFilter.put("requestHeaderModifier", requestHeaderModifier);

        Map<String, Object> rewritePath = new HashMap<>();
        rewritePath.put("type", "ReplacePrefixMatch");
        rewritePath.put("replacePrefixMatch", "/");

        Map<String, Object> urlRewrite = new HashMap<>();
        urlRewrite.put("path", rewritePath);

        Map<String, Object> filter = new HashMap<>();
        filter.put("type", "URLRewrite");
        filter.put("urlRewrite", urlRewrite);

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(requestHeaderFilter);
        filters.add(filter);
        rule.put("filters", filters);

        Map<String, Object> backendRef = new HashMap<>();
        backendRef.put("name", serviceName);
        backendRef.put("port", port);

        List<Map<String, Object>> backendRefs = new ArrayList<>();
        backendRefs.add(backendRef);
        rule.put("backendRefs", backendRefs);

        return rule;
    }

    private Map<String, Object> createRedirectRule(String path) {
        Map<String, Object> rule = new HashMap<>();

        Map<String, Object> match = new HashMap<>();
        Map<String, Object> matchPath = new HashMap<>();
        matchPath.put("type", "Exact");
        matchPath.put("value", path);
        match.put("path", matchPath);

        List<Map<String, Object>> matches = new ArrayList<>();
        matches.add(match);
        rule.put("matches", matches);

        Map<String, Object> redirectPath = new HashMap<>();
        redirectPath.put("type", "ReplaceFullPath");
        redirectPath.put("replaceFullPath", ensureTrailingSlash(path));

        Map<String, Object> requestRedirect = new HashMap<>();
        requestRedirect.put("statusCode", 302);
        requestRedirect.put("path", redirectPath);

        Map<String, Object> redirectFilter = new HashMap<>();
        redirectFilter.put("type", "RequestRedirect");
        redirectFilter.put("requestRedirect", requestRedirect);

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(redirectFilter);
        rule.put("filters", filters);

        return rule;
    }

    private String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    @SuppressWarnings("unchecked")
    private boolean hasPathMatch(Map<String, Object> rule, String path) {
        Object matchesObj = rule.get("matches");
        if (!(matchesObj instanceof List)) {
            return false;
        }
        for (Object matchObj : (List<Object>) matchesObj) {
            if (!(matchObj instanceof Map)) {
                continue;
            }
            Map<String, Object> match = (Map<String, Object>) matchObj;
            Object pathObj = match.get("path");
            if (!(pathObj instanceof Map)) {
                continue;
            }
            Map<String, Object> pathMap = (Map<String, Object>) pathObj;
            Object value = pathMap.get("value");
            if (value instanceof String
                    && normalizePath(path).equals(normalizePath((String) value))) {
                return true;
            }
        }
        return false;
    }

    private void editRouteWithRetry(String routeName, Consumer<GenericKubernetesResource> editor, String correlationId) {
        for (int attempt = 1; attempt <= ROUTE_EDIT_MAX_RETRIES; attempt++) {
            try {
                client.httpRoutes().resource(routeName).edit(routeToUpdate -> {
                    editor.accept(routeToUpdate);
                    return routeToUpdate;
                });
                return;
            } catch (KubernetesClientException e) {
                if (e.getCode() != HTTP_CONFLICT || attempt == ROUTE_EDIT_MAX_RETRIES) {
                    throw e;
                }
                LOGGER.warn(formatLogMessage(correlationId,
                        "HTTPRoute edit conflict for " + routeName + " (attempt "
                                + attempt + "/" + ROUTE_EDIT_MAX_RETRIES + "). Retrying."));
                try {
                    long jitter = ThreadLocalRandom.current()
                            .nextLong(-ROUTE_EDIT_RETRY_JITTER_MS, ROUTE_EDIT_RETRY_JITTER_MS + 1);
                    long backoffMs = Math.max(0, ROUTE_EDIT_RETRY_BACKOFF_MS + jitter);
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private String normalizePath(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 1 && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
