    package org.eclipse.theia.cloud.service.admin.appdefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.service.ApplicationProperties;
import org.eclipse.theia.cloud.service.K8sUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

@QuarkusTest
class AppDefinitionAdminResourceTests {
    private static final String APP_DEF = "java-17-latest";

    @InjectMock
    ApplicationProperties applicationProperties;

    @InjectMock
    K8sUtil k8sUtil;

    @Inject
    AppDefinitionAdminResource fixture;

    @BeforeEach
    void mockApplicationProperties() {
        Mockito.when(applicationProperties.isUseKeycloak()).thenReturn(true);
    }

    @Test
    void list_returnsScalingSettings() {
        AppDefinition first = appDefinition("java", 1, 3);
        AppDefinition second = appDefinition("python", 0, 10);
        Mockito.when(k8sUtil.listAppDefinitionResources()).thenReturn(List.of(first, second));

        List<AppDefinitionScaling> result = fixture.list();

        assertEquals(2, result.size());
        assertEquals("java", result.get(0).appDefinitionName);
        assertEquals(1, result.get(0).minInstances);
        assertEquals(3, result.get(0).maxInstances);
        assertEquals("python", result.get(1).appDefinitionName);
        assertEquals(0, result.get(1).minInstances);
        assertEquals(10, result.get(1).maxInstances);
    }

    @Test
    void get_existingAppDefinition_returnsScalingSettings() {
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 2, 8)));

        AppDefinitionScaling result = fixture.get(APP_DEF);

        assertEquals(APP_DEF, result.appDefinitionName);
        assertEquals(2, result.minInstances);
        assertEquals(8, result.maxInstances);
    }

    @Test
    void get_missingAppDefinition_throwsNotFound() {
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> fixture.get(APP_DEF));
    }

    @Test
    void update_negativeMinInstances_throwsBadRequest() {
        AppDefinitionUpdateRequest request = request(-1, 5);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 5)));

        assertThrows(BadRequestException.class, () -> fixture.update(APP_DEF, request));
    }

    @Test
    void update_negativeMaxInstances_throwsBadRequest() {
        AppDefinitionUpdateRequest request = request(1, -1);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 5)));

        assertThrows(BadRequestException.class, () -> fixture.update(APP_DEF, request));
    }

    @Test
    void update_minGreaterThanMax_throwsBadRequest() {
        AppDefinitionUpdateRequest request = request(6, 5);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 10)));

        assertThrows(BadRequestException.class, () -> fixture.update(APP_DEF, request));
    }

    @Test
    void update_partialUpdateViolatesMinMax_throwsBadRequest() {
        AppDefinitionUpdateRequest request = request(8, null);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 5)));

        assertThrows(BadRequestException.class, () -> fixture.update(APP_DEF, request));
    }

    @Test
    void update_emptyPatch_throwsBadRequest() {
        AppDefinitionUpdateRequest request = request(null, null);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 5)));

        assertThrows(BadRequestException.class, () -> fixture.update(APP_DEF, request));
    }

    @Test
    void update_validPatch_editsAppDefinition() {
        AppDefinitionUpdateRequest request = request(4, 7);
        Mockito.when(k8sUtil.getAppDefinition(APP_DEF)).thenReturn(Optional.of(appDefinition(APP_DEF, 1, 10)));
        Mockito.when(k8sUtil.editAppDefinition(anyString(), eq(APP_DEF), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AppDefinition> editOperation = invocation.getArgument(2, Consumer.class);
            AppDefinition appDefinition = appDefinition(APP_DEF, 1, 10);
            editOperation.accept(appDefinition);
            return appDefinition;
        });

        AppDefinition result = fixture.update(APP_DEF, request);

        assertEquals(4, result.getSpec().getMinInstances());
        assertEquals(7, result.getSpec().getMaxInstances());
    }

    private AppDefinition appDefinition(String name, int minInstances, Integer maxInstances) {
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);

        AppDefinitionSpec spec = new AppDefinitionSpec();
        spec.setMinInstances(minInstances);
        spec.setMaxInstances(maxInstances);

        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setMetadata(metadata);
        appDefinition.setSpec(spec);
        return appDefinition;
    }

    private AppDefinitionUpdateRequest request(Integer minInstances, Integer maxInstances) {
        AppDefinitionUpdateRequest request = new AppDefinitionUpdateRequest();
        request.minInstances = minInstances;
        request.maxInstances = maxInstances;
        return request;
    }
}
