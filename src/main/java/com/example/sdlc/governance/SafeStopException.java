package com.example.sdlc.governance;

/**
 * Raised by the governance layer to stop a run at a node boundary (kill switch, autonomy budget exhausted).
 * The last checkpoint is preserved so a human can inspect and resume.
 */
public class SafeStopException extends RuntimeException {
    public SafeStopException(String message) {
        super(message);
    }
}
