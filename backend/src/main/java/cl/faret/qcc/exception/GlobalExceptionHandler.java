package cl.faret.qcc.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import cl.faret.qcc.controldocumental.exception.ValidacionDocumentoException;
import cl.faret.qcc.noconformidades.exception.ValidacionNoConformidadException;
import cl.faret.qcc.usuarios.exception.OperacionNoPermitidaException;
import cl.faret.qcc.usuarios.exception.UsuarioDuplicadoException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(
            ResourceNotFoundException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

        problem.setTitle("Recurso no encontrado");
        problem.setDetail(exception.getMessage());
        problem.setType(
                URI.create("https://api.faret.cl/problems/not-found"));

        return problem;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            NoResourceFoundException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

        problem.setTitle("Recurso no encontrado");
        problem.setDetail("La ruta solicitada no existe.");
        problem.setType(
                URI.create("https://api.faret.cl/problems/not-found"));

        return problem;
    }

    @ExceptionHandler(UsuarioDuplicadoException.class)
    public ProblemDetail handleUsuarioDuplicado(
            UsuarioDuplicadoException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.CONFLICT);

        problem.setTitle("Usuario duplicado");
        problem.setDetail(exception.getMessage());
        problem.setType(
                URI.create("https://api.faret.cl/problems/usuario-duplicado"));

        return problem;
    }

    @ExceptionHandler(OperacionNoPermitidaException.class)
    public ProblemDetail handleOperacionNoPermitida(
            OperacionNoPermitidaException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problem.setTitle("Operación no permitida");
        problem.setDetail(exception.getMessage());
        problem.setType(
                URI.create("https://api.faret.cl/problems/operacion-no-permitida"));

        return problem;
    }

    @ExceptionHandler(ValidacionNoConformidadException.class)
    public ProblemDetail handleValidacionNoConformidad(
            ValidacionNoConformidadException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problem.setTitle("Datos inválidos");
        problem.setDetail(exception.getMessage());
        problem.setType(
                URI.create("https://api.faret.cl/problems/no-conformidad-validacion"));

        return problem;
    }

    @ExceptionHandler(ValidacionDocumentoException.class)
    public ProblemDetail handleValidacionDocumento(
            ValidacionDocumentoException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problem.setTitle("Datos inválidos");
        problem.setDetail(exception.getMessage());
        problem.setType(
                URI.create("https://api.faret.cl/problems/documento-validacion"));

        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problem.setTitle("Datos inválidos");
        problem.setDetail(
                "La solicitud contiene campos inválidos.");
        problem.setType(
                URI.create("https://api.faret.cl/problems/validation"));

        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        LOGGER.error("Error inesperado no controlado", exception);

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR);

        problem.setTitle("Error interno");
        problem.setDetail(
                "Ocurrió un error inesperado al procesar la solicitud.");
        problem.setType(
                URI.create(
                        "https://api.faret.cl/problems/internal-error"));

        return problem;
    }
}