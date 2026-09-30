package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.security.LoginRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one mapping from core exceptions to a status and a plain-text body, for every controller. The dashboard shows the
 * body as it is. A controller's own handler still wins, e.g. for an {@code IllegalArgumentException} that means bad
 * input there.
 */
@RestControllerAdvice
public class ErrorAdvice {

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<String> notFound(DeviceNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(DeviceOfflineException.class)
    public ResponseEntity<String> offline(DeviceOfflineException e) {
        return text(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler({UnsupportedActionException.class, UnroutableException.class})
    public ResponseEntity<String> cannot(RuntimeException e) {
        return text(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler({ActionFailedException.class, ContentSourceException.class})
    public ResponseEntity<String> failed(RuntimeException e) {
        return text(HttpStatus.BAD_GATEWAY, e);
    }

    @ExceptionHandler(LoginRequiredException.class)
    public ResponseEntity<String> loginRequired(LoginRequiredException e) {
        return text(HttpStatus.UNAUTHORIZED, e);
    }

    private static ResponseEntity<String> text(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
}
