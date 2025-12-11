package org.eclipse.theia.cloud.common.k8s.resource.session;

/**
 * Response object for data injection operations.
 */
public class DataInjectionResponse {

    private final boolean success;
    private final String message;
    private final String error;

    /**
     * Creates a data injection response.
     * 
     * @param success Whether the injection was successful
     * @param message Success message (null if failed)
     * @param error   Error message (null if successful)
     */
    public DataInjectionResponse(boolean success, String message, String error) {
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
    public static DataInjectionResponse success(String message) {
        return new DataInjectionResponse(true, message, null);
    }

    /**
     * Creates a failure response.
     * 
     * @param error Error message
     * @return Response indicating failure
     */
    public static DataInjectionResponse failure(String error) {
        return new DataInjectionResponse(false, null, error);
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
        return "DataInjectionResponse{" + "success=" + success + ", message='" + message + '\'' + ", error='"
                + error + '\'' + '}';
    }
}

