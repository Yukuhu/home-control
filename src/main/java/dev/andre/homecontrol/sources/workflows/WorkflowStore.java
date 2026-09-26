package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Encrypted workflow definitions and the revision boundary used by catalog and Cast dispatch. */
public final class WorkflowStore {
    private static final String PREFIX = "workflow.";
    private static final int MAX_WORKFLOWS = 50;
    private static final int ID_ATTEMPTS = 128;
    private static final int LOCK_STRIPE_COUNT = 64;
    private static final String CHANGED = "Workflow changed; reopen this item";
    private static final String INVALID = "Stored definition cannot be loaded";

    private final SecretStore secrets;
    private final LoginService login;
    private final WorkflowCodec codec;
    private final ApplicationEventPublisher events;
    private final SecureRandom random;
    /** Serializes snapshot replacement and identity/capacity allocation. */
    private final ReentrantLock writes = new ReentrantLock();
    /** A bounded set of locks also covers the final dispatch callback. */
    private final ReentrantLock[] stripes = new ReentrantLock[LOCK_STRIPE_COUNT];
    private volatile Snapshot snapshot;

    public WorkflowStore(SecretStore secrets, LoginService login, WorkflowCodec codec,
                         ApplicationEventPublisher events, SecureRandom random) {
        this.secrets = secrets;
        this.login = login;
        this.codec = codec;
        this.events = events;
        this.random = random;
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
        this.snapshot = load();
    }

    public List<WorkflowDefinition> all() {
        List<WorkflowDefinition> result = new ArrayList<>(snapshot.definitions.values());
        result.sort(Comparator.comparing(WorkflowDefinition::id));
        return List.copyOf(result);
    }

    public Map<String, String> problems() {
        return snapshot.problems;
    }

    public Optional<WorkflowDefinition> find(String id) {
        return Optional.ofNullable(snapshot.definitions.get(id));
    }

    public WorkflowDefinition create(WorkflowDraft draft, String password, String confirmation,
                                     HttpServletRequest request) {
        WorkflowDefinition created;
        writes.lock();
        try {
            Snapshot current = snapshot;
            if (login.loginRequired()) requireLogin(request);
            if (current.keys.size() >= MAX_WORKFLOWS) {
                throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "workflow limit reached");
            }
            String id = newId(current.keys);
            created = new WorkflowDefinition(1, id, 1, draft);
            String encoded = codec.encode(created);
            login.storeSecrets(Map.of(secretName(id), encoded), password, confirmation, request);
            snapshot = current.withCreated(created);
        } finally {
            writes.unlock();
        }
        changed();
        return created;
    }

    public WorkflowDefinition update(String id, long expectedRevision, WorkflowDraft draft,
                                     HttpServletRequest request) {
        String key = secretName(id);
        WorkflowDefinition updated;
        ReentrantLock stripe = stripe(id);
        stripe.lock();
        try {
            writes.lock();
            try {
                requireLogin(request);
                Snapshot current = snapshot;
                WorkflowDefinition previous = currentDefinition(current, id, expectedRevision);
                updated = new WorkflowDefinition(1, id, nextRevision(previous.revision()), draft);
                String encoded = codec.encode(updated);
                login.storeSecrets(Map.of(key, encoded), null, null, request);
                snapshot = current.withUpdated(updated);
            } finally {
                writes.unlock();
            }
        } finally {
            stripe.unlock();
        }
        changed();
        return updated;
    }

    public void setEnabled(String id, long expectedRevision, boolean enabled, HttpServletRequest request) {
        String key = secretName(id);
        ReentrantLock stripe = stripe(id);
        stripe.lock();
        try {
            writes.lock();
            try {
                requireLogin(request);
                Snapshot current = snapshot;
                WorkflowDefinition previous = currentDefinition(current, id, expectedRevision);
                WorkflowDraft draft = previous.draft();
                WorkflowDraft changedDraft = new WorkflowDraft(draft.name(), enabled, draft.mode(), draft.kind(),
                        draft.fetch(), draft.listing(), draft.tile(), draft.variables(), draft.cast());
                WorkflowDefinition updated = new WorkflowDefinition(1, id, nextRevision(previous.revision()), changedDraft);
                String encoded = codec.encode(updated);
                login.storeSecrets(Map.of(key, encoded), null, null, request);
                snapshot = current.withUpdated(updated);
            } finally {
                writes.unlock();
            }
        } finally {
            stripe.unlock();
        }
        changed();
    }

    public void remove(String id, long expectedRevision, HttpServletRequest request) {
        String key = secretName(id);
        ReentrantLock stripe = stripe(id);
        stripe.lock();
        try {
            writes.lock();
            try {
                requireLogin(request);
                Snapshot current = snapshot;
                currentDefinition(current, id, expectedRevision);
                login.removeSecrets(List.of(key));
                snapshot = current.without(id, key);
            } finally {
                writes.unlock();
            }
        } finally {
            stripe.unlock();
        }
        changed();
    }

    /** Removes only a key displayed by problems(), without interpreting its damaged value. */
    public void removeInvalid(String id, HttpServletRequest request) {
        if (id == null) throw new IllegalArgumentException("Unknown workflow");
        ReentrantLock stripe = stripe(id);
        stripe.lock();
        try {
            writes.lock();
            try {
                requireLogin(request);
                Snapshot current = snapshot;
                String key = current.invalidKeys.get(id);
                if (key == null) throw new IllegalArgumentException("Unknown workflow");
                login.removeSecrets(List.of(key));
                snapshot = current.without(id, key);
            } finally {
                writes.unlock();
            }
        } finally {
            stripe.unlock();
        }
        changed();
    }

    /**
     * Keeps an authorized catalog publication or final Cast dispatch atomic with edits/removal.
     * The callback must not fetch upstream data or invoke store mutation methods.
     */
    public void ifCurrent(String id, long revision, Runnable operation) {
        ReentrantLock stripe = stripe(id);
        stripe.lock();
        try {
            WorkflowDefinition definition = snapshot.definitions.get(id);
            if (definition == null || definition.revision() != revision || !definition.draft().enabled()) {
                throw changedWorkflow();
            }
            operation.run();
        } finally {
            stripe.unlock();
        }
    }

    static String secretName(String id) {
        if (id == null || !id.matches("w-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Unknown workflow");
        }
        return PREFIX + id;
    }

    private Snapshot load() {
        Map<String, WorkflowDefinition> definitions = new HashMap<>();
        Map<String, String> problems = new HashMap<>();
        Map<String, String> invalidKeys = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (String key : secrets.names()) {
            if (!key.startsWith(PREFIX)) continue;
            keys.add(key);
            String id = key.substring(PREFIX.length());
            try {
                if (!secretName(id).equals(key)) throw new IllegalArgumentException("Invalid key");
                WorkflowDefinition definition = codec.decode(secrets.secret(key).orElseThrow());
                if (!definition.id().equals(id)) throw new IllegalArgumentException("ID mismatch");
                definitions.put(id, definition);
            } catch (RuntimeException invalid) {
                problems.put(id, INVALID);
                invalidKeys.put(id, key);
            }
        }
        return new Snapshot(definitions, problems, invalidKeys, keys);
    }

    private String newId(Set<String> keys) {
        byte[] bytes = new byte[6];
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            random.nextBytes(bytes);
            StringBuilder id = new StringBuilder("w-");
            for (byte value : bytes) id.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            if (!keys.contains(secretName(id.toString()))) return id.toString();
        }
        throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "could not allocate workflow ID");
    }

    private void requireLogin(HttpServletRequest request) {
        if (!login.isAuthenticated(request)) throw new LoginRequiredException();
    }

    private ReentrantLock stripe(String id) {
        if (id == null) throw new IllegalArgumentException("Unknown workflow");
        return stripes[Math.floorMod(id.hashCode(), stripes.length)];
    }

    private WorkflowDefinition currentDefinition(Snapshot current, String id, long revision) {
        WorkflowDefinition definition = current.definitions.get(id);
        if (definition == null || definition.revision() != revision) throw changedWorkflow();
        return definition;
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) throw changedWorkflow();
        return revision + 1;
    }

    private static WorkflowException changedWorkflow() {
        return new WorkflowException(WorkflowException.Stage.WORKFLOW, CHANGED);
    }

    private void changed() {
        events.publishEvent(new ContentChangedEvent("workflows"));
    }

    private record Snapshot(Map<String, WorkflowDefinition> definitions, Map<String, String> problems,
                            Map<String, String> invalidKeys, Set<String> keys) {
        Snapshot {
            definitions = Map.copyOf(definitions);
            problems = Map.copyOf(problems);
            invalidKeys = Map.copyOf(invalidKeys);
            keys = Set.copyOf(keys);
        }

        Snapshot withCreated(WorkflowDefinition definition) {
            Map<String, WorkflowDefinition> nextDefinitions = new HashMap<>(definitions);
            nextDefinitions.put(definition.id(), definition);
            Set<String> nextKeys = new HashSet<>(keys);
            nextKeys.add(secretName(definition.id()));
            return new Snapshot(nextDefinitions, problems, invalidKeys, nextKeys);
        }

        Snapshot withUpdated(WorkflowDefinition definition) {
            Map<String, WorkflowDefinition> nextDefinitions = new HashMap<>(definitions);
            nextDefinitions.put(definition.id(), definition);
            return new Snapshot(nextDefinitions, problems, invalidKeys, keys);
        }

        Snapshot without(String id, String key) {
            Map<String, WorkflowDefinition> nextDefinitions = new HashMap<>(definitions);
            Map<String, String> nextProblems = new HashMap<>(problems);
            Map<String, String> nextInvalidKeys = new HashMap<>(invalidKeys);
            Set<String> nextKeys = new HashSet<>(keys);
            nextDefinitions.remove(id);
            nextProblems.remove(id);
            nextInvalidKeys.remove(id);
            nextKeys.remove(key);
            return new Snapshot(nextDefinitions, nextProblems, nextInvalidKeys, nextKeys);
        }
    }
}
