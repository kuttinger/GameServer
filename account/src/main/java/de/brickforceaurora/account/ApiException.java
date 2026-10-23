package de.brickforceaurora.account;

public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public ApiException(final int status, final String code, final String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(final String code, final String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "Missing or invalid credentials");
    }

    public static ApiException forbidden(final String message) {
        return new ApiException(403, "forbidden", message);
    }

    public static ApiException notFound(final String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException conflict(final String code, final String message) {
        return new ApiException(409, code, message);
    }

}
