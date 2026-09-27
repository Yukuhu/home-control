package dev.andre.homecontrol.storage;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Says in a few lines, instead of a stack trace, why the app did not start and what to do. */
public final class UnusableDataDirectoryFailureAnalyzer extends AbstractFailureAnalyzer<UnusableDataDirectoryException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, UnusableDataDirectoryException cause) {
        return new FailureAnalysis(cause.getMessage() + ".", cause.remedy(), cause);
    }
}
