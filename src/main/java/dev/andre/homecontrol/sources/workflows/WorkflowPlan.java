package dev.andre.homecontrol.sources.workflows;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/**
 * Which call needs which, when each call runs, and what a refresh or a Play executes. A call may use only values of
 * calls above it, so the dependencies can never form a cycle.
 */
public final class WorkflowPlan {
    public enum Phase {
        REFRESH("Runs at refresh"),
        PLAY("Runs at Play"),
        REFRESH_AND_PLAY("Runs at refresh and Play"),
        UNUSED("Nothing uses this call");

        private final String label;

        Phase(String label) { this.label = label; }

        public String label() { return label; }
    }

    private final WorkflowDraft draft;
    /** Variable name → the call that defines it, in declaration order. Entry values belong to the entry source. */
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final Map<String, Boolean> sensitive = new HashMap<>();
    private final Set<String> listingVariables = new HashSet<>();
    private final Set<String> entryVariables = new HashSet<>();
    private final Map<String, Set<String>> uses = new HashMap<>();
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Set<String> castUses;
    private final Set<String> refresh;
    private final Set<String> play;
    private final Set<String> playRun;

    private WorkflowPlan(WorkflowDraft draft) {
        this.draft = draft;
        index();
        Map<String, Integer> position = IntStream.range(0, draft.calls().size()).boxed()
                .collect(Collectors.toMap(i -> draft.calls().get(i).name(), i -> i, (first, last) -> last));
        for (Call call : draft.calls()) link(call, position);
        castUses = new WorkflowTemplate(draft.cast().template(), owners.keySet()).references();
        checkDisplayFields();
        refresh = draft.mode() == Mode.GENERATED ? closure(refreshRoots()) : Set.of();
        play = closure(playRoots());
        Set<String> unused = draft.calls().stream().map(Call::name)
                .filter(name -> !refresh.contains(name) && !play.contains(name))
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> playRoots = new HashSet<>(play);
        playRoots.addAll(unused);
        playRun = closure(playRoots);
    }

    public static WorkflowPlan of(WorkflowDraft draft) {
        return new WorkflowPlan(draft);
    }

    private void index() {
        for (Call call : draft.calls()) {
            for (Variable variable : call.variables()) {
                owners.put(variable.name(), call.name());
                sensitive.put(variable.name(), variable.sensitive());
                if (call.scope() == CallScope.ENTRY) entryVariables.add(variable.name());
            }
        }
        if (draft.listing() != null) {
            for (Variable variable : draft.listing().variables()) {
                owners.put(variable.name(), draft.listing().call());
                sensitive.put(variable.name(), variable.sensitive());
                listingVariables.add(variable.name());
                entryVariables.add(variable.name());
            }
        }
    }

    private void link(Call call, Map<String, Integer> position) {
        Set<String> used = new HashSet<>(new WorkflowTemplate(call.url(), owners.keySet(), "call URL",
                WorkflowException.Stage.WORKFLOW).references());
        for (Header header : call.headers()) used.addAll(new WorkflowHeaderTemplate(header.value()).references());
        Set<String> needed = new HashSet<>();
        // Sorted, so the same draft always reports the same first problem.
        for (String name : new java.util.TreeSet<>(used)) {
            String owner = owners.get(name);
            if (owner == null) fail(call, "uses {" + name + "}, which no call defines");
            if (position.get(owner) >= position.get(call.name())) fail(call, "uses {" + name + "}, which is defined by a call further down");
            if (entryVariables.contains(name) && call.scope() != CallScope.ENTRY) {
                fail(call, "uses the entry value {" + name + "}; make it a per-entry call");
            }
            needed.add(owner);
        }
        uses.put(call.name(), Set.copyOf(used));
        dependencies.put(call.name(), Set.copyOf(needed));
    }

    private void checkDisplayFields() {
        if (draft.listing() == null) return;
        check(draft.listing().subtitle(), "entry subtitle");
        check(draft.listing().artwork(), "entry artwork");
    }

    private void check(Field field, String label) {
        if (field == null || field.variable() == null) return;
        if (Boolean.TRUE.equals(sensitive.get(field.variable()))) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW,
                    label + " variable {" + field.variable() + "} is marked sensitive");
        }
    }

    private Set<String> displayVariables() {
        if (draft.listing() == null) return new HashSet<>();
        return Stream.of(draft.listing().subtitle(), draft.listing().artwork())
                .filter(field -> field != null && field.variable() != null)
                .map(Field::variable)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<String> refreshRoots() {
        Set<String> roots = new HashSet<>();
        roots.add(draft.listing().call());
        displayVariables().stream().map(owners::get).forEach(roots::add);
        return roots;
    }

    private Set<String> playRoots() {
        Set<String> roots = new HashSet<>();
        castUses.stream().map(owners::get).forEach(roots::add);
        if (draft.mode() == Mode.GENERATED) roots.add(draft.listing().call());
        return roots;
    }

    private Set<String> closure(Set<String> roots) {
        Set<String> all = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String call = todo.pop();
            if (all.add(call)) todo.addAll(dependencies.get(call));
        }
        return Set.copyOf(all);
    }

    public Phase phase(String call) {
        boolean atRefresh = refresh.contains(call);
        boolean atPlay = play.contains(call);
        if (atRefresh && atPlay) return Phase.REFRESH_AND_PLAY;
        if (atRefresh) return Phase.REFRESH;
        return atPlay ? Phase.PLAY : Phase.UNUSED;
    }

    /** The calls a refresh runs, in list order. */
    public List<Call> refreshCalls(CallScope scope) {
        return select(scope, refresh::contains);
    }

    /** The calls a Play runs, in list order. A stored definition's unused call also runs, as its v1 original did. */
    public List<Call> playCalls(CallScope scope) {
        return select(scope, playRun::contains);
    }

    private List<Call> select(CallScope scope, Predicate<String> included) {
        return draft.calls().stream().filter(call -> call.scope() == scope && included.test(call.name())).toList();
    }

    public Set<String> dependencies(String call) {
        return dependencies.get(call);
    }

    /** Every variable name, in declaration order. */
    public List<String> variables() {
        return List.copyOf(owners.keySet());
    }

    public boolean sensitive(String variable) {
        return Boolean.TRUE.equals(sensitive.get(variable));
    }

    /** The entry values a refresh reads: those its per-entry calls and tile fields use. */
    public Set<String> refreshEntryValues() {
        Set<String> needed = new HashSet<>(displayVariables());
        for (Call call : refreshCalls(CallScope.ENTRY)) needed.addAll(uses.get(call.name()));
        needed.retainAll(listingVariables);
        return Set.copyOf(needed);
    }

    /** The entry values a Play reads: those its per-entry calls and the media URL use. */
    public Set<String> playEntryValues() {
        Set<String> needed = new HashSet<>(castUses);
        for (Call call : playCalls(CallScope.ENTRY)) needed.addAll(uses.get(call.name()));
        needed.retainAll(listingVariables);
        return Set.copyOf(needed);
    }

    private static void fail(Call call, String detail) {
        throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "call " + call.name() + ": " + detail);
    }
}
