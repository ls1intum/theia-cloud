package org.eclipse.theia.cloud.common.k8s.resource.session;

/**
 * Response object for credential injection operations.
 */
public class CredentialInjectionResponse {

    private final boolean success;
    private final String message;
    private final String error;

    /**
     * Creates a credential injection response.
     * 
     * @param success Whether the injection was successful
     * @param message Success message (null if failed)
     * @param error   Error message (null if successful)
     */
    public CredentialInjectionResponse(boolean success, String message, String error) {
        this.success = success;
        this.message = message;
        this.error = error;
    }

    /**
     * Creates a successful response.
     * 
     * @param message Success message
     * @return Response indicating success
     */
    public static CredentialInjectionResponse success(String message) {
        return new CredentialInjectionResponse(true, message, null);
    }

    /**
     * Creates a failure response.
     * 
     * @param error Error message
     * @return Response indicating failure
     */
    public static CredentialInjectionResponse failure(String error) {
        return new CredentialInjectionResponse(false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "CredentialInjectionResponse{" + "success=" + success + ", message='" + message + '\'' + ", error='"
                + error + '\'' + '}';
    }
}

