package org.eclipse.theia.cloud.operator.ingress;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Handles adding/removing route rules for sessions.
 */
@Singleton
public class IngressManager {

    private static final Logger LOGGER = LogManager.getLogger(IngressManager.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    @Inject
    private IngressPathProvider pathProvider;

    /**
     * Specification for a route rule to be added.
     */
    public static class IngressRuleSpec {
        private final String serviceName;
        private final int port;
        private final String path;
        private final List<String> hosts;

        private IngressRuleSpec(Builder builder) {
            this.serviceName = builder.serviceName;
            this.port = builder.port;
            this.path = builder.path;
            this.hosts = builder.hosts;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String serviceName;
            private int port;
            private String path;
            private List<String> hosts = new ArrayList<>();

            public Builder serviceName(String serviceName) {
                this.serviceName = serviceName;
                return this;
            }

            public Builder port(int port) {
                this.port = port;
                return this;
            }

            public Builder path(String path) {
                this.path = path;
                return this;
            }

            public Builder host(String host) {
                this.hosts.add(host);
                return this;
            }

            public Builder hosts(List<String> hosts) {
                this.hosts.addAll(hosts);
                return this;
            }

            public IngressRuleSpec build() {
                return new IngressRuleSpec(this);
            }
        }
    }

    /**
     * Gets the HTTPRoute for an app definition.
     */
    public Optional<GenericKubernetesResource> getIngress(AppDefinition appDefinition, String correlationId) {
        return K8sUtil.getExistingHttpRoute(
                client.kubernetes(),
                client.namespace(),
                appDefinition.getMetadata().getName(),
                appDefinition.getMetadata().getUid());
    }

    /**
     * Adds a route rule for a session using eager start (prewarmed instance).
     * 
     * @return the full URL for the session
     */
    public String addRuleForEagerSession(
            GenericKubernetesResource route,
            Service service,
            AppDefinition appDefinition,
            int instance,
            String correlationId) {

        String instancesHost = arguments.getInstancesHost();
        String path = pathProvider.getPath(appDefinition, instance);
        int port = appDefinition.getSpec().getPort();

        // Include hostname prefixes (e.g. *.webview.) for eager sessions too
        List<String> hosts = new ArrayList<>();
        hosts.add(instancesHost);
        List<String> prefixes = appDefinition.getSpec().getIngressHostnamePrefixes();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                hosts.add(prefix + instancesHost);
            }
        }

        addRule(route, IngressRuleSpec.builder()
                .serviceName(service.getMetadata().getName())
                .port(port)
                .path(path)
                .hosts(hosts)
                .build(), correlationId);

        return instancesHost + path + "/";
    }

    /**
     * Adds a route rule for a session using lazy start.
     * Supports multiple hosts (for hostname prefixes).
     * 
     * @return the full URL for the session
     */
    public String addRuleForLazySession(
            GenericKubernetesResource route,
            Service service,
            Session session,
            AppDefinition appDefinition,
            String correlationId) {

        String instancesHost = arguments.getInstancesHost();
        String path = pathProvider.getPath(appDefinition, session);
        int port = appDefinition.getSpec().getPort();

        // Build list of all hosts
        List<String> hosts = new ArrayList<>();
        hosts.add(instancesHost);

        List<String> prefixes = appDefinition.getSpec().getIngressHostnamePrefixes();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                hosts.add(prefix + instancesHost);
            }
        }

        addRule(route, IngressRuleSpec.builder()
                .serviceName(service.getMetadata().getName())
                .port(port)
                .path(path)
                .hosts(hosts)
                .build(), correlationId);

        return instancesHost + path + "/";
    }

    /**
     * Adds HTTPRoute rules according to the specification.
     */
    public synchronized void addRule(GenericKubernetesResource route, IngressRuleSpec spec, String correlationId) {
        try {
            client.httpRoutes().edit(correlationId, route.getMetadata().getName(), routeToUpdate -> {
                Map<String, Object> specMap = getSpec(routeToUpdate);

                List<String> hostnames = getStringList(specMap, "hostnames");
                for (String host : spec.hosts) {
                    if (!hostnames.contains(host)) {
                        hostnames.add(host);
                    }
                }

                List<Map<String, Object>> rules = getRuleList(specMap);
                rules.add(createRouteRule(spec.serviceName, spec.port, spec.path));
            });
            LOGGER.info(formatLogMessage(correlationId,
                    "Added HTTPRoute rule for path " + spec.path + " to " + route.getMetadata().getName()));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to add HTTPRoute rule to " + route.getMetadata().getName()), e);
            throw e;
        }
    }

    /**
     * Removes a route rule for an eager session.
     */
    public void removeRuleForEagerSession(
            GenericKubernetesResource route,
            AppDefinition appDefinition,
            int instance,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, instance);
        removeRuleByPath(route, path, correlationId);
    }

    /**
     * Removes route rules for a lazy session (handles multiple hosts).
     */
    public boolean removeRulesForLazySession(
            GenericKubernetesResource route,
            Session session,
            AppDefinition appDefinition,
            String correlationId) {

        String instancesHost = arguments.getInstancesHost();
        String path = pathProvider.getPath(appDefinition, session);

        List<String> hosts = new ArrayList<>();
        hosts.add(instancesHost);

        List<String> prefixes = appDefinition.getSpec().getIngressHostnamePrefixes();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                hosts.add(prefix + instancesHost);
            }
        }

        return removeRulesByPathAndHosts(route, path, hosts, correlationId);
    }

    /**
     * Removes HTTPRoute rule by path.
     */
    public synchronized void removeRuleByPath(GenericKubernetesResource route, String path, String correlationId) {
        try {
            client.httpRoutes().resource(route.getMetadata().getName()).edit(routeToUpdate -> {
                Map<String, Object> specMap = getSpec(routeToUpdate);
                List<Map<String, Object>> rules = getRuleList(specMap);
                rules.removeIf(rule -> hasPathMatch(rule, path));
                return routeToUpdate;
            });
            LOGGER.info(formatLogMessage(correlationId,
                    "Removed HTTPRoute rule for path " + path + " from " + route.getMetadata().getName()));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to remove HTTPRoute rule from " + route.getMetadata().getName()), e);
            throw e;
        }
    }

    /**
     * Removes HTTPRoute rules matching path and specific hosts.
     */
    public synchronized boolean removeRulesByPathAndHosts(
            GenericKubernetesResource route,
            String path,
            List<String> hosts,
            String correlationId) {

        try {
            client.httpRoutes().resource(route.getMetadata().getName()).edit(routeToUpdate -> {
                Map<String, Object> specMap = getSpec(routeToUpdate);
                List<Map<String, Object>> rules = getRuleList(specMap);
                rules.removeIf(rule -> hasPathMatch(rule, path));
                return routeToUpdate;
            });
            LOGGER.info(formatLogMessage(correlationId,
                    "Removed HTTPRoute rules for path " + path + " from " + route.getMetadata().getName()));
            return true;
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to remove HTTPRoute rules from " + route.getMetadata().getName()), e);
            throw e;
        }
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

    private Map<String, Object> createRouteRule(String serviceName, int port, String path) {
        Map<String, Object> rule = new HashMap<>();

        Map<String, Object> match = new HashMap<>();
        Map<String, Object> matchPath = new HashMap<>();
        matchPath.put("type", "PathPrefix");
        matchPath.put("value", path);
        match.put("path", matchPath);

        List<Map<String, Object>> matches = new ArrayList<>();
        matches.add(match);
        rule.put("matches", matches);

        Map<String, Object> rewritePath = new HashMap<>();
        rewritePath.put("type", "ReplacePrefixMatch");
        rewritePath.put("replacePrefixMatch", "/");

        Map<String, Object> urlRewrite = new HashMap<>();
        urlRewrite.put("path", rewritePath);

        Map<String, Object> filter = new HashMap<>();
        filter.put("type", "URLRewrite");
        filter.put("urlRewrite", urlRewrite);

        List<Map<String, Object>> filters = new ArrayList<>();
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
            if (path.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
